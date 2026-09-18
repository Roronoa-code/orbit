package com.mani.orbit

import android.os.Build
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mani.orbit.sync.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WatchReadingsUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    /**
     * Samsung Health reaches the phone on Samsung's schedule, often most of an hour behind the watch;
     * Orbit's own watch readings arrive as they are collected. Today's heart rate is the newer of the two,
     * and only a valid reading with a trustworthy time may stand in for it.
     */
    @Test fun theNewestValidWatchHeartReadingBecomesTodaysHeartRate() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val app = rule.activity.application
        val model = OrbitModel(app, androidx.lifecycle.SavedStateHandle())
        val journal = java.io.File(app.cacheDir, "watch-heart-check.db").also { it.delete() }
        val installation = UUID.nameUUIDFromBytes("Orbit heart check watch".toByteArray()).toString()
        val boot = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        fun deliver(value: Double, at: Long, quality: String = "valid") = ReadingJournal(journal).use {
            it.receive(ReadingWire.encode(ReadingBatch(UUID.randomUUID().toString(), installation, listOf(
                // The watch's own sensor clock advances with each reading, as it does on a real boot.
                WatchReading(UUID.randomUUID().toString(), 1, at, at, 0, "heart", value, "bpm", quality, "instant", boot,
                    at - (now - 3_600_000))))), now)
        }
        fun today() = model.health.value.day
        deliver(91.0, now - 60_000)
        model.refreshWatchHeart(journal)
        rule.waitUntil(5000) { today().heart == 91.0 }
        org.junit.Assert.assertEquals(now - 60_000, today().heartAt)
        // A newer reading replaces it.
        deliver(95.0, now - 20_000)
        model.refreshWatchHeart(journal)
        rule.waitUntil(5000) { today().heart == 95.0 }
        // A reading the watch could not stand behind does not.
        deliver(40.0, now - 5_000, quality = "no_contact")
        model.refreshWatchHeart(journal)
        Thread.sleep(300)
        org.junit.Assert.assertEquals(95.0, today().heart!!, 0.0)
        journal.delete()
    }

    @Test fun committedWatchReadingsAppearAndUpdateInTheNativePhone() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val now = System.currentTimeMillis()
        val installation = UUID.nameUUIDFromBytes("Orbit emulator watch".toByteArray()).toString()
        val sample = WatchReading(UUID.randomUUID().toString(), 1, now, now, 0, "heart", 83.0, "bpm", "valid", "instant",
            UUID.randomUUID().toString(), 1000)
        val path = rule.activity.getDatabasePath("watch-readings.db")
        ReadingJournal(path).use { it.receive(ReadingWire.encode(ReadingBatch(UUID.randomUUID().toString(), installation, listOf(sample))), now) }
        rule.onNodeWithContentDescription("Settings").performClick()
        val watchButton = rule.onNodeWithText("Watch readings").performScrollTo()
        check(watchButton.fetchSemanticsNode().boundsInRoot.bottom <= rule.onNodeWithTag("explore-island").fetchSemanticsNode().boundsInRoot.top)
        java.io.File(rule.activity.cacheDir, "settings-native-route.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        watchButton.performClick()
        rule.waitUntil(6000) { rule.onAllNodesWithText("83").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("83").assertIsDisplayed()
        java.io.File(rule.activity.cacheDir, "settings-watch-sources.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        ReadingJournal(path).use { it.receive(ReadingWire.encode(ReadingBatch(UUID.randomUUID().toString(), installation,
            listOf(sample.copy(revision = 2, value = 86.0)))), now + 1) }
        rule.waitUntil(6000) { rule.onAllNodesWithText("86").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("86").assertIsDisplayed()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(6000) { rule.onAllNodesWithText("86").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("86").assertIsDisplayed()
    }
}
