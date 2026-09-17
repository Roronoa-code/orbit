package com.mani.orbit

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.mani.health.core.model.measurement.*
import com.mani.orbit.sync.EcgRecord
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class EcgHistoryUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Test fun rawWaveformHasBoundedPagingDetailsAndHonestPartialStates() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        fun id() = UUID.randomUUID().toString()
        val callbacks = List(200) { index -> EcgCallback(index.toLong(), Instant.EPOCH, index * 10000000L,
            List(5) { point -> EcgPoint(point * 2L, kotlin.math.sin((index * 5 + point) * .04).toFloat(),
                if (point == 0) 0 else null, null, if (point == 0) 1f else null, if (point == 0) -1f else null) }) }
        val playback = mutableStateOf(EcgPlayback("complete", listOf(EcgPlaybackChunk(0, callbacks.take(100)), EcgPlaybackChunk(1, callbacks.drop(100))),
            1000, 1000, true, emptySet(), summarizeEcgSignal(callbacks)))
        val record = EcgRecord(id(), id(), id(), 1789426800000, "complete", 2, 1000)
        val expanded = mutableStateOf(false); val font = mutableStateOf(1f); val uncertain = mutableStateOf(false)
        compose.setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFFBFA6EF), surface = Color(0xFF201D27)), typography = OrbitTypography) {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, font.value)) {
                Surface(color = Color(0xFF0B0A0F), contentColor = MaterialTheme.colorScheme.onSurface) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
                        EcgHistoryCard(record, null, null, if (expanded.value) playback.value else null, expanded.value, orderingUncertain = uncertain.value, expand = { expanded.value = !expanded.value }, select = {})
                    }
                }
            }
        } }
        compose.onNodeWithText("View waveform").performClick()
        compose.onNodeWithText("Samples 1–500 of 1000").assertIsDisplayed()
        capture("ecg-waveform-detail")
        compose.onNodeWithContentDescription("Later samples").performScrollTo().performClick()
        compose.onNodeWithText("Samples 501–1000 of 1000").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Overview").performClick()
        compose.onNodeWithText("Samples 1–1000 of 1000").assertIsDisplayed()
        compose.runOnIdle { font.value = 1.5f; playback.value = playback.value.copy(complete = false, issues = setOf("CHUNK_GAP")) }
        compose.onNodeWithText("Recording needs review").performScrollTo().assertIsDisplayed()
        capture("ecg-waveform-large-partial")
        compose.onNodeWithText("Some samples are missing. Gaps are not filled in.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close waveform").performScrollTo().performClick()
        compose.onNodeWithText("View waveform").assertIsDisplayed()
        compose.runOnIdle { uncertain.value = true }
        compose.onNodeWithText("Recording order uncertain").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Previous ECG recording").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next ECG recording").assertIsNotEnabled()
        capture("ecg-history-order-uncertain")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        File(compose.activity.cacheDir, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
