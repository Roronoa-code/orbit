package com.mani.orbit

import android.os.Handler
import android.os.HandlerThread
import android.view.Choreographer
import android.view.FrameMetrics
import android.view.Window
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.sin

/** Real Choreographer/Window frames. No Compose virtual clock or screenshot work during measurement. */
class GlassRenderingTest {
    @Test fun measureTiersAndSeparateClockSourceAndMaterialInvalidation() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") && android.os.Build.VERSION.SDK_INT >= 33)
        val phase = mutableIntStateOf(0)
        val scroll = mutableFloatStateOf(0f)
        val finger = mutableFloatStateOf(.5f)
        val translation = mutableFloatStateOf(0f)
        val seconds = mutableIntStateOf(0)
        val origin = AtomicLong(0)
        val sourceDraws = AtomicIntegerArray(9)
        val materialDraws = AtomicIntegerArray(9)
        val sourceCompositions = AtomicIntegerArray(9)
        val foregroundCompositions = AtomicIntegerArray(9)
        val counts = IntArray(9)
        val dropped = IntArray(9)
        val samples = Array(9) { LongArray(600 * 4) }
        val sizes = mutableSetOf<IntSize>()
        val finished = CountDownLatch(1)
        val metricThread = HandlerThread("Orbit local rendering measurement").apply { start() }
        val metricHandler = Handler(metricThread.looper)
        fun bucket(timestamp: Long): Int {
            val start = origin.get()
            val age = timestamp - start
            return if (start == 0L || age < 0 || age % PHASE_NS < WARM_NS) -1 else (age / PHASE_NS).toInt().takeIf { it in 0..8 } ?: -1
        }
        fun observe(counter: AtomicIntegerArray) { val index = bucket(System.nanoTime()); if (index >= 0) counter.incrementAndGet(index) }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, lost ->
            val index = bucket(metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP))
            if (index >= 0 && metrics.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 0L) {
                dropped[index] += lost
                val n = counts[index]
                if (n < 600) {
                    val row = samples[index]
                    row[n * 4] = metrics.getMetric(FrameMetrics.TOTAL_DURATION)
                    row[n * 4 + 1] = metrics.getMetric(FrameMetrics.DEADLINE)
                    row[n * 4 + 2] = metrics.getMetric(FrameMetrics.GPU_DURATION)
                    row[n * 4 + 3] = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION)
                    counts[index]++
                }
            }
        }
        var choreographer: Choreographer? = null
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(time: Long) {
                if (origin.get() == 0L) origin.set(time)
                val age = time - origin.get()
                val index = (age / PHASE_NS).toInt()
                if (index >= 9) { finished.countDown(); return }
                phase.intValue = index
                val progress = (age % PHASE_NS).toFloat() / PHASE_NS
                seconds.intValue = (age / 1_000_000_000).toInt()
                scroll.floatValue = if (index % 3 == 1) progress * 240f else 0f
                finger.floatValue = if (index % 3 == 2) .5f + .45f * sin(progress * 12f) else .5f
                translation.floatValue = if (index % 3 == 2) 8f * sin(progress * 12f) else 0f
                choreographer?.postFrameCallback(this)
            }
        }
        var listening = false
        val scenario = ActivityScenario.launch(ComponentActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.window.addOnFrameMetricsAvailableListener(listener, metricHandler); listening = true
                activity.setContent {
                    CompositionLocalProvider(LocalGlassQuality provides GlassQualityState(GlassQuality.entries[phase.intValue / 3])) {
                        val backdrop = rememberGlassBackdrop()
                        Box(Modifier.fillMaxSize().background(Color(0xFF0B0A0F))) {
                            Source(backdrop, scroll, { observe(sourceDraws) }, { observe(sourceCompositions) })
                            Box(Modifier.align(Alignment.BottomCenter).padding(16.dp).fillMaxWidth().height(128.dp)) {
                                Box(Modifier.matchParentSize().graphicsLayer { translationX = translation.floatValue.dp.toPx() }
                                    .onSizeChanged { sizes.add(it) }.clip(RoundedCornerShape(31.dp))
                                    .drawWithContent { observe(materialDraws); drawContent() }
                                    .orbitFrost(backdrop, 31.dp, { if (phase.intValue % 3 == 2) 1f else 0f }, { Offset(finger.floatValue, .5f) })) {
                                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Body        Workouts        Health", color = Color.White)
                                        Spacer(Modifier.height(16.dp))
                                        Clock(seconds) { observe(foregroundCompositions) }
                                    }
                                }
                            }
                            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                repeat(2) { Box(Modifier.size(48.dp).clip(RoundedCornerShape(24.dp)).orbitFrost(backdrop, 24.dp)) }
                            }
                        }
                    }
                }
                choreographer = Choreographer.getInstance()
                choreographer?.postFrameCallback(callback)
            }
            assertTrue("Nine bounded native render phases must finish", finished.await(45, TimeUnit.SECONDS))
            scenario.onActivity { if (listening) { it.window.removeOnFrameMetricsAvailableListener(listener); listening = false }; choreographer?.removeFrameCallback(callback) }
            val drained = CountDownLatch(1)
            metricHandler.post { drained.countDown() }
            assertTrue(drained.await(5, TimeUnit.SECONDS))
            assertEquals("Quality changes retain the surface dimensions", 1, sizes.size)
            val report = JSONArray()
            for (i in 0..8) {
                val totals = (0 until counts[i]).map { samples[i][it * 4] }.sorted()
                val gpu = (0 until counts[i]).map { samples[i][it * 4 + 2] }.filter { it >= 0 }.sorted()
                val misses = (0 until counts[i]).count { samples[i][it * 4 + 1] > 0 && samples[i][it * 4] >= samples[i][it * 4 + 1] }
                report.put(JSONObject().put("tier", GlassQuality.entries[i / 3].name).put("scene", listOf("clock", "scroll", "drag")[i % 3])
                    .put("frames", counts[i]).put("droppedMetricReports", dropped[i]).put("deadlineMisses", misses)
                    .put("totalMedianNs", totals.getOrNull(totals.size / 2) ?: JSONObject.NULL).put("totalP95Ns", totals.getOrNull((totals.size * .95).toInt()) ?: JSONObject.NULL)
                    .put("gpuMedianNs", gpu.takeIf { it.isNotEmpty() }?.get(gpu.size / 2) ?: JSONObject.NULL)
                    .put("sourceDraws", sourceDraws[i]).put("materialDraws", materialDraws[i])
                    .put("sourceCompositions", sourceCompositions[i]).put("foregroundCompositions", foregroundCompositions[i]))
            }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            File(context.cacheDir, "glass-rendering.json").writeText(JSONObject().put("device", "local software emulator")
                .put("phaseNanos", PHASE_NS).put("warmupNanos", WARM_NS).put("phases", report).toString())
            for (i in 0..8) assertTrue("Actual window metrics exist for phase $i: ${counts.joinToString()}", counts[i] >= if (i % 3 == 0) 1 else 10)
            for (i in 0..8) {
                assertEquals("Changing readings never recompose the backdrop", 0, sourceCompositions[i])
                if (i % 3 != 1) assertEquals("Timer and material motion never rerecord an unchanged backdrop", 0, sourceDraws[i])
                if (i % 3 == 0) assertEquals("A child timer never redraws its enclosing material", 0, materialDraws[i])
            }
        } finally {
            scenario.onActivity { if (listening) { it.window.removeOnFrameMetricsAvailableListener(listener); listening = false }; choreographer?.removeFrameCallback(callback) }
            scenario.close(); metricThread.quitSafely(); metricThread.join(5000)
        }
    }

    @Composable private fun Source(backdrop: GlassBackdrop, scroll: State<Float>, draw: () -> Unit, composed: () -> Unit) {
        SideEffect(composed)
        Canvas(Modifier.fillMaxSize().recordBackdrop(backdrop).drawWithContent { draw(); drawContent() }) {
            drawRect(Color(0xFF211A30))
            val pitch = 38.dp.toPx()
            for (i in -2..50) {
                val position = i * pitch + scroll.value
                drawLine(Color(0xFFB8A1E4), Offset(position, 0f), Offset(position, size.height), 4.dp.toPx())
                drawLine(Color(0xFFEDC6AC), Offset(0f, position), Offset(size.width, position), 4.dp.toPx())
            }
        }
    }
    @Composable private fun Clock(seconds: State<Int>, composed: () -> Unit) {
        Text("Workout  ${seconds.value}s", color = Color(0xFFDED9E5))
        SideEffect(composed)
    }
    companion object { private const val PHASE_NS = 3_000_000_000L; private const val WARM_NS = 500_000_000L }
}
