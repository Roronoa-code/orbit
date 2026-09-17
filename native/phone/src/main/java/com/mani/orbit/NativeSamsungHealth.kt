package com.mani.orbit

import androidx.lifecycle.lifecycleScope
import com.samsung.android.sdk.health.data.HealthDataService
import com.samsung.android.sdk.health.data.HealthDataStore
import com.samsung.android.sdk.health.data.error.ErrorCode
import com.samsung.android.sdk.health.data.error.HealthDataException
import com.samsung.android.sdk.health.data.permission.Permission
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.LocalDate

/** Native lifecycle owner for read-only Samsung SDK access and the existing committed cache. */
internal class NativeSamsungHealth(private val activity: MainActivity) : AutoCloseable {
    private val live = LiveSamsungSteps(activity, ::changed)
    private var sdk: HealthDataStore? = null
    private var granted = emptySet<Permission>()
    private var visible = false
    private var closed = false
    private var consent = false
    private var polling: Job? = null
    private var importing: Job? = null
    private var loading: Job? = null
    private var selected = LocalDate.now()
    private var data = "null"
    private var revision = 0L
    private var status = "Connecting to Samsung Health…"
    private var available = false
    private var scanned = 0
    private val requested = SamsungDataImport.permissions.values.toSet()
    private fun store() = sdk ?: HealthDataService.getStore(activity).also { sdk = it }
    private fun cache() = HealthRecordStore(activity.getDatabasePath("samsung-health.db"))
    private fun changed() { if (!closed) activity.healthChanged() }

    fun snapshot(known: String): String {
        val info = JSONObject().put("revision", revision.toString()).put("status", status)
            .put("syncing", importing?.isActive == true || consent).put("scanned", scanned)
            .put("available", available).put("permitted", SamsungDataImport.hasReadingsPermission(granted)).put("live", JSONObject(live.snapshot())).toString()
        return info.dropLast(1) + ",\"data\":" + (if (known == revision.toString()) "null" else data) + "}"
    }

    fun connect() {
        if (!visible || closed || consent || importing?.isActive == true) return
        consent = true; status = "Choose readings in Samsung Health"; changed()
        activity.lifecycleScope.launch {
            try {
                // The Samsung consent Activity may pause Orbit. Keep this request alive through that pause.
                granted = store().requestPermissions(requested, activity)
                available = true
                status = if (!SamsungDataImport.hasReadingsPermission(granted)) "No Samsung Health readings allowed" else "Samsung Health connected"
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(error) }
            finally { consent = false; changed() }
            if (!closed && SamsungDataImport.hasReadingsPermission(granted)) sync(false)
        }
    }
    fun permissions() = connect()
    fun connectLive() { if (visible && !closed && !consent) live.connect() }
    fun sync() = sync(false)

    private fun sync(recent: Boolean) {
        if (closed || consent || importing?.isActive == true) return
        importing = activity.lifecycleScope.launch(start = CoroutineStart.LAZY) {
            scanned = 0
            try {
                granted = withTimeout(12_000) { store().getGrantedPermissions(requested) }
                available = true
                if (!SamsungDataImport.hasReadingsPermission(granted)) { status = "Connect Samsung Health"; return@launch }
                status = if (recent) "Refreshing Samsung Health…" else "Importing Samsung Health…"; changed()
                withContext(Dispatchers.IO) {
                    SamsungDataImport.importLock.withLock {
                        cache().use { records -> SamsungDataImport.sync(store(), records, granted, recent) { count ->
                            activity.runOnUiThread { if (!closed) { scanned = count; changed() } }
                        } }
                    }
                }
                status = "Samsung Health saved on this phone"
                PhoneHealthContextWorker.schedule(activity)
                load(selected.toString())
            } catch (timeout: TimeoutCancellationException) { failure(timeout) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure(error) }
            finally { importing = null; changed() }
        }.also { it.start() }
    }

    fun load(value: String) {
        val date = try { LocalDate.parse(value) } catch (_: Exception) { return }
        if (closed || date < LocalDate.of(1970, 1, 1) || date > LocalDate.now()) return
        selected = date; loading?.cancel()
        loading = activity.lifecycleScope.launch {
            try {
                val next = withContext(Dispatchers.IO) { cache().use { HealthProjection.read(it, date, true) } }
                if (closed || date != selected) return@launch
                data = next.toString(); revision++; changed()
                if (visible && SamsungDataImport.permissions["steps"] in granted) {
                    try {
                        val hours = withContext(Dispatchers.IO) { SamsungDataImport.hours(store(), date) }
                        if (!closed && date == selected) { next.put("stepHours", hours); data = next.toString(); revision++; changed() }
                    } catch (timeout: TimeoutCancellationException) { status = "Hourly steps could not be refreshed"; changed() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { status = "Hourly steps could not be refreshed"; changed() }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { status = "Saved readings could not be loaded. Your records are preserved."; changed() }
        }
    }

    fun foreground(value: Boolean) {
        visible = value; live.foreground(value); polling?.cancel()
        if (value && !closed) polling = activity.lifecycleScope.launch {
            while (isActive) {
                if (!consent) sync(true)
                delay(30_000)
            }
        }
    }
    private fun failure(error: Exception) {
        val code = (error as? HealthDataException)?.errorCode
        if (code in setOf(ErrorCode.ERR_NO_USER_PERMISSION, ErrorCode.ERR_ACCESS_CONTROL, ErrorCode.ERR_INVALID_CALLER)) granted = emptySet()
        if (code in setOf(ErrorCode.ERR_PLATFORM_NOT_INSTALLED, ErrorCode.ERR_PLATFORM_DISABLED, ErrorCode.ERR_OLD_VERSION_PLATFORM)) available = false
        status = message(error)
    }
    override fun close() {
        closed = true; visible = false; polling?.cancel(); importing?.cancel(); loading?.cancel(); live.close()
    }
    companion object {
        internal fun message(error: Exception): String = when ((error as? HealthDataException)?.errorCode) {
            ErrorCode.ERR_PLATFORM_NOT_INSTALLED -> "Install Samsung Health to connect your readings"
            ErrorCode.ERR_OLD_VERSION_PLATFORM -> "Update Samsung Health to connect"
            ErrorCode.ERR_PLATFORM_DISABLED, ErrorCode.ERR_PLATFORM_NOT_INITIALIZED -> "Open and set up Samsung Health, then reconnect"
            ErrorCode.ERR_NO_USER_PERMISSION -> "Allow readings in Samsung Health, then import again"
            ErrorCode.ERR_ACCESS_CONTROL, ErrorCode.ERR_INVALID_CALLER -> "Samsung Health has not approved access for this build"
            ErrorCode.ERR_OUT_OF_SPACE -> "Free some phone storage, then import again. Saved records are preserved."
            else -> "Samsung Health could not refresh. Saved records are preserved; retry the import."
        }
    }
}
