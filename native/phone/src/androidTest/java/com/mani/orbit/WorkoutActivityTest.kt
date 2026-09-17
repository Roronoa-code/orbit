package com.mani.orbit

import android.Manifest
import android.content.Context
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutActivityTest {
    @get:Rule val rule = createEmptyComposeRule()

    @Test fun nativeStartUsesProfileCountdownSurvivesBackgroundAndFinishIsSaved() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") && android.os.Build.MODEL.contains("sdk", true))
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppPreferences(context)
        val oldProfile = settings.read("orbit-profile-v1")
        val prefs = context.getSharedPreferences("orbit-workouts", Context.MODE_PRIVATE)
        val oldSessions = prefs.getString("sessions", null)
        val oldMode = if (prefs.contains("real-data-v1")) prefs.getBoolean("real-data-v1", false) else null
        assertTrue(settings.write("orbit-profile-v1", JSONObject().put("name", "Workout test").put("birthDate", "1998-01-02").put("heightCm", 178).put("weightKg", 84).toString()))
        assertTrue(prefs.edit().putBoolean("real-data-v1", true).putString("sessions", "{\"active\":null,\"history\":[]}").commit())
        if (android.os.Build.VERSION.SDK_INT >= 33) InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        val session = WorkoutSession(context)
        val listener = android.content.ComponentName(context, MusicSession.Access::class.java)
        val notifications = context.getSystemService(android.app.NotificationManager::class.java)
        val musicAllowed = notifications.isNotificationListenerAccessGranted(listener)
        fun access(allow: Boolean) {
            val command = "cmd notification ${if (allow) "allow_listener" else "disallow_listener"} ${listener.flattenToString()}"
            android.os.ParcelFileDescriptor.AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
                .bufferedReader().use { it.readText() }
        }
        access(true)
        val fixture = android.media.session.MediaSession(context, "Integrated native music verification")
        val cover = android.graphics.Bitmap.createBitmap(720, 720, android.graphics.Bitmap.Config.ARGB_8888)
        android.graphics.Canvas(cover).apply {
            drawColor(android.graphics.Color.rgb(54, 78, 98))
            drawCircle(430f, 250f, 205f, android.graphics.Paint(3).apply { color = android.graphics.Color.rgb(221, 173, 125) })
        }
        fun playback(playing: Boolean) = android.media.session.PlaybackState.Builder()
            .setActions(android.media.session.PlaybackState.ACTION_PLAY or android.media.session.PlaybackState.ACTION_PAUSE or android.media.session.PlaybackState.ACTION_SEEK_TO)
            .setState(if (playing) android.media.session.PlaybackState.STATE_PLAYING else android.media.session.PlaybackState.STATE_PAUSED, 45000, if (playing) 1f else 0f).build()
        fixture.setCallback(object : android.media.session.MediaSession.Callback() {
            override fun onPause() { fixture.setPlaybackState(playback(false)) }
            override fun onPlay() { fixture.setPlaybackState(playback(true)) }
        }, android.os.Handler(android.os.Looper.getMainLooper()))
        fixture.setMetadata(android.media.MediaMetadata.Builder().putString(android.media.MediaMetadata.METADATA_KEY_MEDIA_ID, "native-integration")
            .putString(android.media.MediaMetadata.METADATA_KEY_TITLE, "A quiet morning")
            .putString(android.media.MediaMetadata.METADATA_KEY_ARTIST, "Local media fixture")
            .putLong(android.media.MediaMetadata.METADATA_KEY_DURATION, 180000)
            .putBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART, cover).build())
        fixture.setPlaybackState(playback(true)); fixture.isActive = true
        try {
            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("Explore").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithContentDescription("Explore").performClick()
                rule.onNode(hasText("Workouts") and hasClickAction()).performClick()
                rule.onNodeWithTag("choose-Running").performClick()
                rule.onNodeWithText("Track outdoors").performClick()
                rule.onNodeWithText("Weight").assertDoesNotExist()
                rule.waitUntil(10000) { rule.onNodeWithTag("workout-start").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick) }
                rule.onNodeWithTag("workout-start").assertIsEnabled().performClick()
                rule.waitUntil(5000) { JSONObject(session.read()).optJSONObject("active") != null }
                assertEquals(84.0, JSONObject(session.read()).getJSONObject("active").getDouble("weightKg"), 0.0)
                assertFalse(JSONObject(session.read()).getJSONObject("active").getBoolean("trackLocation"))
                activity.moveToState(Lifecycle.State.CREATED)
                SystemClock.sleep(3600)
                val clock = JSONObject(session.snapshot(""))
                assertEquals(0L, clock.getLong("startsInMs")); assertTrue(clock.getLong("elapsedMs") > 0)
                activity.moveToState(Lifecycle.State.RESUMED)
                rule.waitUntil(10000) { rule.onAllNodesWithTag("workout-timer").fetchSemanticsNodes().isNotEmpty() }
                activity.recreate()
                rule.waitUntil(10000) { rule.onAllNodesWithTag("workout-timer").fetchSemanticsNodes().isNotEmpty() }
                rule.waitUntil(10000) { rule.onAllNodesWithTag("music-toggle").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithContentDescription("Focus on timer and music").performClick()
                rule.waitForIdle()
                activity.recreate()
                rule.waitUntil(10000) { rule.onAllNodesWithContentDescription("Show workout details").fetchSemanticsNodes().isNotEmpty() }
                rule.waitUntil(10000) { rule.onAllNodesWithTag("music-toggle").fetchSemanticsNodes().isNotEmpty() }
                rule.onNodeWithTag("music-toggle").performClick()
                rule.waitUntil(5000) { fixture.controller.playbackState?.state == android.media.session.PlaybackState.STATE_PAUSED }
                rule.waitUntil(5000) { rule.onAllNodesWithContentDescription("Play music").fetchSemanticsNodes().isNotEmpty() }
                java.io.File(context.cacheDir, "music-native-integrated.png").outputStream().use {
                    rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                rule.onNodeWithContentDescription("Back").performClick()
                rule.onNodeWithContentDescription("Focus on timer and music").assertExists()
                rule.onNodeWithText("All details").performClick()
                rule.waitForIdle()
                rule.onNodeWithTag("workout-timer").assertDoesNotExist()
                var scenarioIntent: android.content.Intent? = null
                activity.onActivity {
                    scenarioIntent = android.content.Intent(it.intent)
                    it.startActivity(android.content.Intent(it, MainActivity::class.java)
                        .setAction("com.mani.orbit.OPEN_WORKOUT").addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP))
                }
                rule.waitUntil(10000) { rule.onAllNodesWithTag("workout-timer").fetchSemanticsNodes().isNotEmpty() }
                // ActivityScenario matches lifecycle events against its original launch intent.
                activity.onActivity { it.intent = scenarioIntent }
                rule.onNodeWithText("Pause workout").performScrollTo().performClick()
                rule.waitUntil(5000) { JSONObject(session.read()).getJSONObject("active").isNull("resumedAt") }
                val paused = JSONObject(session.snapshot(""))
                SystemClock.sleep(1100)
                assertEquals(paused.getLong("elapsedMs"), JSONObject(session.snapshot("")).getLong("elapsedMs"))
                rule.onNodeWithContentDescription("Back").performClick()
                rule.onNodeWithContentDescription("Active workout, Running").performClick()
                rule.onNodeWithText("Open workout").performClick()
                rule.onNodeWithText("Resume workout").performScrollTo().performClick()
                rule.waitUntil(5000) { !JSONObject(session.read()).getJSONObject("active").isNull("resumedAt") }
                rule.onNodeWithText("Finish").performClick()
                rule.waitUntil(5000) { JSONObject(session.read()).isNull("active") }
                rule.onNodeWithText("Workout saved", substring = true).assertExists()
                java.io.File(context.cacheDir, "workout-native-finished.png").outputStream().use {
                    rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                val saved = JSONObject(session.read()).getJSONArray("history")
                assertEquals(1, saved.length())
                assertEquals(84.0, saved.getJSONObject(0).getDouble("weightKg"), 0.0)
                assertTrue(saved.getJSONObject(0).getLong("elapsed") > 0)
                rule.onNodeWithContentDescription("Back").performClick()
                rule.onNodeWithTag("choose-Running").assertExists()
            }
        } finally {
            fixture.release(); cover.recycle(); access(musicAllowed)
            // Only the emulator fixture session is affected, and its prior stores are restored.
            if (JSONObject(session.read()).optJSONObject("active") != null) session.action("finish", "", 0.0)
            assertTrue(prefs.edit().apply {
                if (oldSessions == null) remove("sessions") else putString("sessions", oldSessions)
                if (oldMode == null) remove("real-data-v1") else putBoolean("real-data-v1", oldMode)
            }.commit())
            if (oldProfile != null) assertTrue(settings.write("orbit-profile-v1", oldProfile))
            else assertTrue(context.getSharedPreferences("orbit-settings", Context.MODE_PRIVATE).edit().remove("orbit-profile-v1").commit())
            session.updateNotification()
        }
    }
}
