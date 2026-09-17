package com.mani.orbit

import android.app.NotificationManager
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class MusicTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val record = WorkoutRecord("music-test", "Running", 1789440000000, null, 129000, 132000, weight = 84.0)
    private fun guard() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun artwork(): Bitmap = Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).apply {
        android.graphics.Canvas(this).apply {
            drawColor(android.graphics.Color.rgb(69, 92, 117))
            val paint = android.graphics.Paint(3).apply { color = android.graphics.Color.rgb(239, 192, 138) }
            drawCircle(430f, 270f, 210f, paint)
            paint.color = android.graphics.Color.rgb(85, 63, 112); drawCircle(180f, 600f, 340f, paint)
        }
    }
    private fun save(name: String) { java.io.File(rule.activity.cacheDir, name).outputStream().use {
        rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    } }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun nativeSessionOwnsArtworkAndRejectsStaleAndUnsupportedCommands() {
        guard()
        val source = artwork()
        val copy = MusicSession.copyNativeArtwork(source)
        assertNotSame(source, copy); assertFalse(copy.isMutable)
        source.eraseColor(android.graphics.Color.RED)
        assertNotEquals(source.getPixel(0, 0), copy.getPixel(0, 0))
        assertArrayEquals(intArrayOf(2048, 1536), MusicSession.artworkSize(4000, 3000))
        assertArrayEquals(intArrayOf(320, 180), MusicSession.artworkSize(320, 180))
        val component = ComponentName(rule.activity, MusicSession.Access::class.java)
        val manager = rule.activity.getSystemService(NotificationManager::class.java)
        val originallyAllowed = manager.isNotificationListenerAccessGranted(component)
        shell("cmd notification allow_listener ${component.flattenToString()}")
        var callback = ""
        var seek = -1L
        val session = MediaSession(rule.activity, "Orbit native verification")
        val backend = MusicSession(rule.activity, {}, true)
        val originalBridge = MusicSession(rule.activity, {})
        var native: NativeMusic? = null
        fun metadata(name: String, cover: Bitmap?) = MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_MEDIA_ID, name)
            .putString(MediaMetadata.METADATA_KEY_TITLE, name).putString(MediaMetadata.METADATA_KEY_ARTIST, "Local test")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, 180000).apply { if (cover != null) putBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART, cover) }.build()
        fun playback(playing: Boolean) = PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, 4000, if (playing) 1f else 0f).build()
        try {
            rule.runOnIdle {
                session.setCallback(object : MediaSession.Callback() {
                    override fun onPause() { callback = "pause"; session.setPlaybackState(playback(false)) }
                    override fun onPlay() { callback = "play"; session.setPlaybackState(playback(true)) }
                    override fun onSeekTo(pos: Long) { seek = pos }
                }, Handler(Looper.getMainLooper()))
                session.setMetadata(metadata("First", copy)); session.setPlaybackState(playback(true)); session.isActive = true
                backend.resume()
                native = NativeMusic(rule.activity)
            }
            var data = JSONObject()
            rule.waitUntil(10000) {
                data = JSONObject(backend.read("")); data.optString("title") == "First" && !data.optBoolean("artLoading", true)
            }
            assertFalse(data.has("art"))
            rule.waitUntil(10000) { native!!.state.value.title == "First" && native!!.state.value.artwork != null }
            assertTrue(native!!.allowed.value)
            val image = backend.nativeArtwork(data.getString("artKey"))
            assertNotNull(image); assertFalse(image!!.isMutable); assertFalse(image.isRecycled)
            originalBridge.resume()
            var original = JSONObject()
            rule.waitUntil(10000) {
                original = JSONObject(originalBridge.read("")); original.optString("art").startsWith("data:image/jpeg;base64,")
            }
            assertTrue(original.getString("art").length <= 1500000)
            originalBridge.suspend()
            val id = data.getString("id")
            assertFalse(backend.command(id, "previous", 0.0)); assertFalse(backend.command(id, "seek", Double.NaN))
            native!!.command(native!!.state.value.id, "pause")
            rule.waitUntil { callback == "pause" }
            rule.waitUntil { !native!!.state.value.playing }
            assertFalse(JSONObject(backend.read("")).getBoolean("playing"))
            rule.runOnIdle { session.setMetadata(metadata("Second", null)) }
            rule.waitUntil { JSONObject(backend.read("")).optString("title") == "Second" }
            assertFalse(backend.command(id, "seek", 60000.0)); assertEquals(-1, seek)
            data = JSONObject(backend.read(""))
            assertTrue(backend.command(data.getString("id"), "seek", 60000.0))
            rule.waitUntil { seek == 60000L }
            rule.waitUntil(8000) { !JSONObject(backend.read("")).getBoolean("artLoading") }
            assertNull(backend.nativeArtwork(JSONObject(backend.read("")).getString("artKey")))
            assertFalse(image.isRecycled)
            shell("cmd notification disallow_listener ${component.flattenToString()}")
            rule.waitUntil { JSONObject(backend.read("")).optString("status") == "permission" }
            rule.waitUntil { native!!.state.value.status == "permission" && !native!!.allowed.value }
            assertFalse(backend.command(id, "play", 0.0))
            backend.suspend(); assertEquals("inactive", JSONObject(backend.read("")).getString("status"))
        } finally {
            native?.close()
            backend.close(); originalBridge.close(); session.release(); source.recycle()
            if (originallyAllowed) shell("cmd notification allow_listener ${component.flattenToString()}")
            else shell("cmd notification disallow_listener ${component.flattenToString()}")
        }
    }

    @Test fun oneCoverAndFixedControlsFollowDragReverseCancelAndStaleSeek() {
        guard()
        var music by mutableStateOf(NativeMusicState(status = "ready", id = "1:1", title = "A quiet morning", artist = "Local artwork fixture",
            source = "Test music", duration = 180000, position = 45000, playing = true, canToggle = true, canNext = true,
            canPrevious = true, canSeek = true, canOpen = true, artwork = artwork()))
        lateinit var player: WorkoutPlayerMotion
        val commands = mutableListOf<Triple<String, String, Double>>()
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                player = rememberWorkoutPlayer(record.id)
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F))) {
                    WorkoutArtwork(music, player)
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Text("Running", color = Color.White, modifier = Modifier.fillMaxWidth().height(74.dp).padding(20.dp))
                        WorkoutLive(record, false, null, {}, {}, music, player, { id, action, value -> commands += Triple(id, action, value) }, {})
                    }
                }
            }
        }
        rule.waitForIdle()
        val initialControl = rule.onNodeWithTag("music-toggle").fetchSemanticsNode().boundsInRoot
        save("music-native-compact.png")
        val canvas = rule.onNodeWithTag("workout-live")
        canvas.performTouchInput { down(Offset(centerX, height * .58f)); moveBy(Offset(0f, -140f), 180) }
        rule.runOnIdle { assertTrue(player.motion.value > .1f); assertTrue(player.motion.value < .9f) }
        val partial = rule.onNodeWithTag("music-toggle").fetchSemanticsNode().boundsInRoot
        assertEquals(initialControl.size, partial.size)
        save("music-native-mid-drag.png")
        canvas.performTouchInput { moveBy(Offset(0f, -230f), 240); advanceEventTime(160); up() }
        rule.waitForIdle(); rule.runOnIdle { assertTrue(player.expanded); assertEquals(1f, player.motion.value, .01f) }
        assertEquals(initialControl.size, rule.onNodeWithTag("music-toggle").fetchSemanticsNode().boundsInRoot.size)
        save("music-native-expanded.png")
        canvas.performTouchInput { down(Offset(centerX, height * .45f)); moveBy(Offset(0f, 180f), 200); cancel() }
        rule.waitForIdle(); rule.runOnIdle { assertTrue(player.expanded); assertEquals(1f, player.motion.value, .01f) }
        rule.onNodeWithTag("music-toggle").performClick()
        assertEquals("pause", commands.single().second)
        commands.clear()
        rule.onNodeWithTag("music-seek").performTouchInput { down(center); moveBy(Offset(35f, 0f)); cancel() }
        assertTrue(commands.isEmpty())
        rule.onNodeWithTag("music-seek").performTouchInput { down(center); moveBy(Offset(35f, 0f)) }
        rule.runOnIdle { music = music.copy(id = "1:2", title = "Changed track") }
        rule.onNodeWithTag("music-seek").performTouchInput { up() }
        assertTrue(commands.isEmpty())
        rule.mainClock.autoAdvance = false
        rule.runOnUiThread { player.target(false) }
        rule.mainClock.advanceTimeBy(96)
        val before = player.motion.value
        canvas.performTouchInput { down(Offset(centerX, height * .38f)) }
        rule.runOnIdle { assertEquals(before, player.motion.value, .01f) }
        canvas.performTouchInput { moveBy(Offset(0f, -180f), 200); up() }
        rule.mainClock.autoAdvance = true
        rule.waitForIdle(); rule.runOnIdle { assertTrue(player.expanded) }
    }

    /** A tap on the music text or the timer owns the same focus change a drag performs. */
    @Test fun tappingMusicTextAndTimerOpensAndClosesTheFocusedPlayer() {
        guard()
        val music = NativeMusicState(status = "ready", id = "2:1", title = "A quiet morning", artist = "Local artwork fixture",
            source = "Test music", duration = 180000, position = 45000, playing = true, canToggle = true, canNext = true,
            canPrevious = true, canSeek = true, canOpen = true, artwork = artwork())
        lateinit var player: WorkoutPlayerMotion
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                player = rememberWorkoutPlayer(record.id)
                Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F))) {
                    WorkoutArtwork(music, player)
                    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                        Text("Running", color = Color.White, modifier = Modifier.fillMaxWidth().height(74.dp).padding(20.dp))
                        WorkoutLive(record, false, null, {}, {}, music, player, { _, _, _ -> }, {})
                    }
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("music-heading").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertTrue("A music text tap must open the player", player.expanded) }
        rule.runOnIdle { assertEquals(1f, player.motion.value, .01f) }
        rule.onNodeWithContentDescription("Show workout details").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertFalse("A timer tap must close the player", player.expanded) }
        rule.runOnIdle { assertEquals(0f, player.motion.value, .01f) }
        rule.onNodeWithContentDescription("Focus on timer and music").performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertTrue("A timer tap must reopen the player", player.expanded) }
        rule.runOnIdle { assertEquals(1f, player.motion.value, .01f) }
    }

    @Test fun largeTextMusicPermissionAndReducedMotionRemainUsable() {
        guard()
        lateinit var player: WorkoutPlayerMotion
        var connects = 0
        var music by mutableStateOf(NativeMusicState(status = "permission"))
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f), LocalOrbitReducedMotion provides true) {
                MaterialTheme(colorScheme = darkColorScheme(), typography = OrbitTypography) {
                    player = rememberWorkoutPlayer(record.id)
                    Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F))) {
                        Box(Modifier.width(320.dp).fillMaxHeight().safeDrawingPadding()) {
                            WorkoutLive(record.copy(target = 1800000), false, null, {}, {}, music, player, { _, _, _ -> }, { connects++ })
                        }
                    }
                }
            }
        }
        rule.onNodeWithText("Connect music").performScrollTo().performClick(); assertEquals(1, connects)
        rule.onNodeWithText("Pause workout").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Finish").assertIsDisplayed()
        assertEquals(rule.onNode(hasText("Pause workout") and hasClickAction()).fetchSemanticsNode().boundsInRoot.height,
            rule.onNode(hasText("Finish") and hasClickAction()).fetchSemanticsNode().boundsInRoot.height, .5f)
        rule.runOnIdle {
            music = NativeMusicState(status = "ready", id = "large-text", title = "A quiet morning with a long track title", artist = "A longer artist name", duration = 180000, position = 45000, canToggle = true, canSeek = true)
            player.target(true); assertEquals(1f, player.motion.value, 0f)
        }
        rule.onNodeWithTag("music-seek").performScrollTo()
        val mode = rule.onNodeWithTag("workout-clock-mode").fetchSemanticsNode().boundsInRoot
        val heading = rule.onNodeWithTag("music-heading").fetchSemanticsNode().boundsInRoot
        val seek = rule.onNodeWithTag("music-seek").fetchSemanticsNode().boundsInRoot
        assertTrue("Timer mode must be above music text", mode.bottom < heading.top)
        assertTrue("Music text must be above the timeline", heading.bottom <= seek.top + 1)
        save("music-native-large-text.png")
        rule.runOnIdle { assertTrue(player.back()); assertEquals(0f, player.motion.value, 0f) }
    }
}
