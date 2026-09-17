package com.mani.orbit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

internal data class NativeMusicState(
    val status: String = "inactive", val id: String = "", val title: String = "", val artist: String = "", val source: String = "",
    val duration: Long = 0, val position: Long = 0, val playing: Boolean = false, val buffering: Boolean = false,
    val canToggle: Boolean = false, val canPrevious: Boolean = false, val canNext: Boolean = false,
    val canSeek: Boolean = false, val canOpen: Boolean = false, val artKey: String = "", val artwork: Bitmap? = null,
    val artLoading: Boolean = false, val error: String? = null,
) {
    val ready get() = status == "ready"
}

/** The existing Android session is authoritative. UI commands never optimistically change playback. */
internal class NativeMusic(private val activity: ComponentActivity) {
    val state = MutableStateFlow(NativeMusicState())
    val allowed = MutableStateFlow(false)
    private val updates = Channel<Unit>(Channel.CONFLATED)
    private val lock = Mutex()
    private val backend = MusicSession(activity, { updates.trySend(Unit) }, true)

    init {
        activity.lifecycleScope.launch {
            activity.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                backend.resume()
                launch { while (isActive) { updates.trySend(Unit); delay(1000) } }
                try { for (update in updates) refresh() }
                finally { backend.suspend(); state.value = NativeMusicState() }
            }
        }
    }

    private suspend fun refresh() = lock.withLock {
        val next = withContext(Dispatchers.IO) {
            try {
                val prior = state.value
                val json = JSONObject(backend.read(prior.artKey))
                val status = json.getString("status")
                if (status != "ready") NativeMusicState(status = status,
                    error = if (status == "error") "Music is temporarily unavailable." else null)
                else {
                    val id = json.getString("id")
                    val artKey = json.getString("artKey")
                    val loading = json.getBoolean("artLoading")
                    val sameSession = prior.id.substringBefore(':') == id.substringBefore(':')
                    // Keep a cover only during the backend's bounded artwork grace, never across players.
                    val image = backend.nativeArtwork(artKey) ?: prior.artwork.takeIf { loading && sameSession }
                    NativeMusicState(status, id, json.getString("title"), json.getString("artist"), json.getString("source"),
                        json.getLong("duration"), json.getLong("position"), json.getBoolean("playing"), json.getBoolean("buffering"),
                        json.getBoolean("canToggle"), json.getBoolean("canPrevious"), json.getBoolean("canNext"), json.getBoolean("canSeek"),
                        json.getBoolean("canOpen"), artKey, image, loading,
                        if (json.getString("playback") == "error") "Playback stopped in the music app." else prior.error.takeIf { prior.id == id })
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { NativeMusicState(status = "error", error = "Music is temporarily unavailable.") }
        }
        state.value = next
        if (next.status != "inactive") allowed.value = activity.getSystemService(android.app.NotificationManager::class.java)
            .isNotificationListenerAccessGranted(android.content.ComponentName(activity, MusicSession.Access::class.java))
    }

    fun command(id: String, action: String, value: Double = 0.0) {
        activity.lifecycleScope.launch {
            lock.withLock {
                val accepted = withContext(Dispatchers.IO) { backend.command(id, action, value) }
                if (state.value.id == id) state.value = state.value.copy(error = if (accepted) null else "This control is no longer available. Try again.")
            }
            updates.trySend(Unit)
        }
    }

    fun connect() = backend.connect()
    fun close() { updates.close(); backend.close() }
}
