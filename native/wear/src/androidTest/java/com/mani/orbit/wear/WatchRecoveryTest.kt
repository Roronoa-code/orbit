package com.mani.orbit.wear

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.wear.compose.foundation.AmbientMode
import com.mani.orbit.sync.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class WatchRecoveryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val now = Instant.parse("2026-09-15T12:00:00Z").toEpochMilli()
    private val today = LocalDate.of(2026, 9, 15)
    private val day = RecoveryDay(today, now - 13 * 3600000, now - 5 * 3600000, 1,
        mapOf("light" to 4 * 3600000L, "deep" to 2 * 3600000L, "rem" to 3600000L, "awake" to 3600000L), 84.0, 77.0)
    private val previous = RecoveryDay(today.minusDays(1), day.start!! - 86400000, day.end!! - 86400000, 1, mapOf("unknown" to 8 * 3600000L), null, null)
    private val saved = HealthContext("UTC", now, now, listOf(day, previous))
    @Before fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun save(name: String) { compose.activity.cacheDir.resolve("recovery-$name.png").outputStream().use {
        compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
    } }
    @Test fun dailyPagingDetailsAndRestorationKeepTheSameRecordedDay() {
        val restore = StateRestorationTester(compose)
        var refreshes = 0
        restore.setContent { WatchEnvironment { WatchRecoveryScreen(HealthContextWire.decode(HealthContextWire.encode(saved)), false, null, WatchDisplay(AmbientMode.Interactive, now, 0)) { refreshes++ } } }
        compose.onNodeWithTag("recovery-value-0").assertTextEquals("7h 0m").assertIsDisplayed()
        save("summary")
        compose.onNodeWithTag("watch-recovery").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("recovery-date-1").assertTextEquals("14 Sept · Sleep").assertIsDisplayed()
        compose.onNodeWithTag("recovery-value-1").assertTextEquals("—")
        compose.onNodeWithTag("recovery-details-1").performScrollTo().performClick()
        compose.onNodeWithText("Unknown").performScrollTo().assertIsDisplayed()
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("recovery-detail-sleep").performScrollTo().assertTextEquals("—")
        compose.onNodeWithText("Refresh from phone").performScrollTo().performClick()
        assertEquals(1, refreshes)
        compose.onNodeWithText("Back").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("watch-back-surface").performTouchInput {
            down(Offset(2f, height * .5f)); moveTo(Offset(width * .25f, height * .5f), 160); cancel()
        }
        compose.onNodeWithText("Back").assertIsDisplayed()
        compose.onNodeWithTag("watch-back-surface").performTouchInput {
            swipe(Offset(2f, height * .5f), Offset(width - 2f, height * .5f), 350)
        }
        save("restored-page")
        compose.onNodeWithTag("recovery-date-1").performScrollTo().assertIsDisplayed()
    }
    @Test fun incomingDayDoesNotReplaceTheHeldPageOrLoseItsReleaseDestination() {
        var context by mutableStateOf(saved)
        compose.setContent { WatchEnvironment { WatchRecoveryScreen(context, false, null, WatchDisplay(AmbientMode.Interactive, now, 0)) {} } }
        compose.onNodeWithTag("recovery-date-0").assertTextEquals("15 Sept · Sleep")
        compose.onNodeWithTag("watch-recovery").performTouchInput {
            down(Offset(width * .8f, height * .5f)); moveTo(Offset(width * .65f, height * .5f), 80)
        }
        compose.runOnIdle { context = saved.copy(days = listOf(day.copy(date = today.plusDays(1))) + saved.days) }
        compose.onNodeWithTag("recovery-date-0").assertTextEquals("15 Sept · Sleep")
        compose.onNodeWithTag("watch-recovery").performTouchInput {
            moveTo(Offset(width * .2f, height * .5f), 200); up()
        }
        compose.onNodeWithTag("recovery-date-2").assertTextEquals("14 Sept · Sleep").assertIsDisplayed()
        compose.onNodeWithTag("recovery-details-2").performScrollTo().performClick()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithTag("recovery-date-2").performScrollTo().assertTextEquals("14 Sept · Sleep")
        compose.onNodeWithTag("recovery-details-2").performScrollTo().performTouchInput { down(center); advanceEventTime(400) }
        compose.runOnIdle { context = saved }
        compose.onNodeWithTag("recovery-date-2").assertTextEquals("14 Sept · Sleep")
        compose.onNodeWithTag("recovery-details-2").performTouchInput { up() }
        compose.onNodeWithTag("recovery-detail-sleep").performScrollTo().assertTextEquals("—")
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.onNodeWithTag("recovery-date-1").performScrollTo().assertTextEquals("14 Sept · Sleep")
        save("incoming-day-held")
    }
    @Test fun savedAndUncertainContextKeepTheirMeaningWhenRefreshFails() {
        var context by mutableStateOf<HealthContext?>(saved)
        var error by mutableStateOf<String?>("Phone unavailable · saved view")
        compose.setContent { WatchRecoveryScreen(context, false, error, WatchDisplay(AmbientMode.Interactive, now, 0)) {} }
        compose.onNodeWithTag("recovery-value-0").assertTextEquals("7h 0m")
        compose.onNodeWithText("Saved view", substring = true).performScrollTo().assertIsDisplayed()
        save("saved-offline")
        compose.onNodeWithTag("recovery-details-0").performScrollTo().performClick()
        compose.onNodeWithText(error!!).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Back").performScrollTo().performClick()
        compose.runOnIdle { context = saved.copy(generatedAt = now + 120000); error = null }
        compose.onNodeWithText("Reading time uncertain").performScrollTo().assertIsDisplayed()
        save("uncertain")
        compose.runOnIdle { context = null; error = "Saved phone readings could not be checked" }
        compose.onNodeWithText(error!!).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Connect Samsung Health", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Check again").performScrollTo().assertIsDisplayed()
        save("failed")
        compose.runOnIdle { context = saved; error = null }
        compose.onNodeWithTag("recovery-value-0").performScrollTo().assertTextEquals("7h 0m")
        compose.onAllNodesWithText("Saved view", substring = true).assertCountEquals(0)
    }

    @Test fun largeTextMissingDataAndRemovalStayReadable() {
        var context by mutableStateOf<HealthContext?>(saved)
        var exits = 0
        compose.setContent {
            BackHandler { exits++ }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                WatchRecoveryScreen(context, false, null, WatchDisplay(AmbientMode.Interactive, now, 0)) {}
            }
        }
        compose.onNodeWithTag("recovery-details-0").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithText("Stage totals").performScrollTo().assertIsDisplayed()
        save("large-stages")
        compose.onNodeWithText("Phone updated", substring = true).performScrollTo().assertIsDisplayed()
        compose.runOnIdle { context = saved.copy(days = emptyList()) }
        compose.onNodeWithText("No recent readings shared").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Check again").performScrollTo().assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertEquals("An empty page must not leave an invisible detail Back step", 1, exits)
        save("empty")
    }
}
