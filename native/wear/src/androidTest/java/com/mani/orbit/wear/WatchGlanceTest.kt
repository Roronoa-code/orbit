package com.mani.orbit.wear

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.wear.protolayout.DeviceParametersBuilders.*
import androidx.wear.protolayout.LayoutElementBuilders.Layout
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.material3.materialScope
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.connection.DefaultTileClient
import androidx.wear.tiles.renderer.TileRenderer
import androidx.wear.watchface.complications.data.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WatchGlanceTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val now = Instant.parse("2026-09-15T09:00:00Z").toEpochMilli()
    private val zone = ZoneId.of("Europe/London")
    private val device = DeviceParameters.Builder().setScreenWidthDp(192).setScreenHeightDp(192)
        .setScreenDensity(2f).setFontScale(1f).setScreenShape(SCREEN_SHAPE_ROUND).build()
    @Before fun emulatorOnly() { check(Build.HARDWARE in setOf("ranchu", "goldfish")) }
    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
        .bufferedReader().use { it.readText().trim() }
    private fun row(metric: String, at: Long = now, value: Double = 80.0) = JSONObject()
        .put("metric", metric).put("end", at).put("value", value).put("quality", "valid")
        .put("timeUncertain", false).put("semantics", if (metric == "steps") "daily" else "instant")
    private fun tile(readings: Map<String, GlanceReading>?, at: Long = now): Tile {
        lateinit var result: Tile
        materialScope(context, device, allowDynamicTheme = false, defaultColorScheme = WatchTileColors) {
            result = watchTodayTile(readings, at)
            result.tileTimeline!!.timelineEntries.first().layout!!.root!!
        }
        return result
    }

    @Test fun hostRefreshBudgetsAreIndependentDurableAndDoNotRetryEverySample() {
        val name = "glance-budget-test-${java.util.UUID.randomUUID()}"
        val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        try {
            val tileTimes = mutableListOf<Long>(); val complicationTimes = mutableListOf<Long>()
            for (elapsed in 0L..600_000L step 1000) {
                if (claimGlanceRefresh(prefs, "tile", 30_000, 7, elapsed)) tileTimes += elapsed
                if (claimGlanceRefresh(prefs, "complications", 300_000, 7, elapsed)) complicationTimes += elapsed
            }
            assertEquals((0L..600_000L step 30_000).toList(), tileTimes)
            assertEquals(listOf(0L, 300_000L, 600_000L), complicationTimes)
            val reopened = context.createPackageContext(context.packageName, 0).getSharedPreferences(name, Context.MODE_PRIVATE)
            // The same durable reservation also covers process loss or an exception after dispatch.
            assertFalse(claimGlanceRefresh(reopened, "complications", 300_000, 7, 600_001))
            assertTrue(claimGlanceRefresh(reopened, "tile", 30_000, 7, 630_000))
            assertFalse(claimGlanceRefresh(reopened, "complications", 300_000, 7, 630_000))
            assertFalse(claimGlanceRefresh(reopened, "complications", 300_000, -1, 900_000))
            assertTrue(claimGlanceRefresh(reopened, "complications", 300_000, 8, 10))
            assertFalse(claimGlanceRefresh(reopened, "complications", 300_000, 8, 11))
            val failed = object : android.content.SharedPreferences by reopened {
                override fun edit(): android.content.SharedPreferences.Editor {
                    val editor = reopened.edit()
                    return object : android.content.SharedPreferences.Editor by editor {
                        override fun putInt(key: String, value: Int): android.content.SharedPreferences.Editor { editor.putInt(key, value); return this }
                        override fun putLong(key: String, value: Long): android.content.SharedPreferences.Editor { editor.putLong(key, value); return this }
                        override fun commit() = false
                    }
                }
            }
            assertThrows(IllegalStateException::class.java) { claimGlanceRefresh(failed, "complications", 300_000, 9, 20) }
            assertEquals(8, reopened.getInt("complications-boot", -1))
            assertEquals(10L, reopened.getLong("complications-at", -1))
        } finally { assertTrue(context.deleteSharedPreferences(name)) }
    }

    @Test fun staleInvalidMidnightAndDstReadingsCannotRemainCurrent() {
        assertEquals(80.0, glanceReading(row("heart"), "heart", now, zone).value)
        for (invalid in listOf(row("heart", now + 1), row("heart", now - 300_000), row("heart", value = 0.0),
            row("heart").put("quality", "no_contact"), row("heart").put("timeUncertain", true), row("steps"))) {
            assertNull(glanceReading(invalid, "heart", now, zone).value)
        }
        assertNull(glanceReading(null, "steps", now, zone).value)
        assertNull(glanceReading(row("steps", now - 86_400_000), "steps", now, zone).value)
        assertNull(glanceReading(row("steps").put("semantics", "interval"), "steps", now, zone).value)
        for ((midnight, hours) in listOf("2026-03-29T00:00:00Z" to 23, "2026-10-24T23:00:00Z" to 25)) {
            val start = Instant.parse(midnight).toEpochMilli()
            assertEquals(hours * 3_600_000L, glanceReading(row("steps", start), "steps", start, zone).expires - start)
        }
        val readings = mapOf("steps" to glanceReading(row("steps", value = 2400.0), "steps", now, zone),
            "heart" to glanceReading(row("heart"), "heart", now, zone))
        val entries = tile(readings).tileTimeline!!.timelineEntries
        assertEquals(3, entries.size)
        assertEquals(now + 300_000, entries[0].validity!!.endMillis)
        assertEquals(entries[0].validity!!.endMillis, entries[1].validity!!.startMillis)
        assertTrue(entries[0].layout.toString().contains("Latest 80 bpm"))
        assertFalse(entries[1].layout.toString().contains("Latest 80 bpm"))
        assertFalse(entries[2].layout.toString().contains("2,400"))
        assertTrue(tile(null).tileTimeline!!.timelineEntries.first().layout.toString().contains("Open Orbit to retry"))
    }

    @Test fun realTileBindsAndRendersAndProvidersHaveProtectedRegistrations() {
        for ((type, permission) in listOf(WatchTodayTile::class.java to "BIND_TILE_PROVIDER",
            WatchStepsComplication::class.java to "BIND_COMPLICATION_PROVIDER", WatchHeartComplication::class.java to "BIND_COMPLICATION_PROVIDER")) {
            val info = context.packageManager.getServiceInfo(ComponentName(context, type), PackageManager.GET_META_DATA)
            assertTrue(info.exported)
            assertEquals("com.google.android.wearable.permission.$permission", info.permission)
            if (type == WatchTodayTile::class.java) assertNotNull(context.getDrawable(info.metaData.getInt("androidx.wear.tiles.PREVIEW")))
        }
        val client = DefaultTileClient(context, ComponentName(context, WatchTodayTile::class.java), context.mainExecutor)
        val response = client.requestTile(TileRequest.Builder().setDeviceConfiguration(device).build()).get(15, TimeUnit.SECONDS)
        assertTrue(response.tileTimeline!!.timelineEntries.isNotEmpty())
        val resources = Resources.Builder().build()
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            lateinit var parent: FrameLayout
            lateinit var renderer: TileRenderer
            scenario.onActivity { activity ->
                parent = FrameLayout(activity)
                activity.setContentView(parent)
                renderer = TileRenderer(activity, activity.mainExecutor) { }
            }
            fun render(layout: Layout, name: String) {
                lateinit var ready: com.google.common.util.concurrent.ListenableFuture<android.view.View>
                scenario.onActivity { ready = renderer.inflateAsync(layout, resources, parent) }
                ready.get(10, TimeUnit.SECONDS)
                instrumentation.waitForIdleSync()
                assertTrue(parent.childCount > 0)
                scenario.onActivity {
                    fun texts(view: android.view.View): List<android.widget.TextView> = when (view) {
                        is android.widget.TextView -> listOf(view)
                        is android.view.ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
                        else -> emptyList()
                    }
                    val labels = texts(parent)
                    if (name == "expired") {
                        assertTrue(labels.any { it.text.toString() == "No steps recorded" })
                        assertFalse(labels.any { it.text.toString().contains("80 bpm") })
                    }
                    val workouts = labels.first { it.text.toString() == "Workouts" }
                    val visible = android.graphics.Rect()
                    assertTrue("Workouts shortcut clipped", workouts.getGlobalVisibleRect(visible) && visible.height() >= workouts.height)
                    for (label in labels) for (line in 0 until (label.layout?.lineCount ?: 0))
                        assertEquals("Truncated label: ${label.text}", 0, label.layout.getEllipsisCount(line))
                }
                // Capture the hardware renderer after its frame is submitted, preserving rounded clips.
                val drawn = java.util.concurrent.CountDownLatch(1)
                scenario.onActivity { parent.postOnAnimation { parent.postOnAnimation { parent.post { drawn.countDown() } } } }
                assertTrue(drawn.await(2, TimeUnit.SECONDS))
                instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                    File(context.cacheDir, "tile-$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            render(response.tileTimeline!!.timelineEntries.first().layout!!, "actual-cache")
            val populated = tile(mapOf("steps" to glanceReading(row("steps", value = 2400.0), "steps", now, zone),
                "heart" to glanceReading(row("heart"), "heart", now, zone)))
            render(populated.tileTimeline!!.timelineEntries.first().layout!!, "fixture")
            render(populated.tileTimeline!!.timelineEntries.last().layout!!, "expired")
        }
    }

    @Test fun complicationsExpireAndOpenPulseWithoutStartingMeasurement() {
        val reading = glanceReading(row("heart"), "heart", now, zone)
        val data = glanceComplication(context, "heart", ComplicationType.SHORT_TEXT, reading) as ShortTextComplicationData
        assertEquals("80bpm", data.text.getTextAt(context.resources, Instant.ofEpochMilli(now)).toString())
        assertTrue(data.validTimeRange.contains(Instant.ofEpochMilli(now)))
        assertFalse(data.validTimeRange.contains(Instant.ofEpochMilli(now + 300_000)))
        assertNull(glanceComplication(context, "heart", ComplicationType.RANGED_VALUE, reading))
        val missing = glanceComplication(context, "heart", ComplicationType.LONG_TEXT, glanceReading(null, "heart", now, zone)) as LongTextComplicationData
        assertEquals("— bpm", missing.text.getTextAt(context.resources, Instant.ofEpochMilli(now)).toString())
        // A host showing only required text in one colour must retain metric identity.
        for (metric in listOf("steps", "heart")) {
            val unit = if (metric == "heart") "bpm" else "stp"
            val values = if (metric == "heart") listOf(null, 1.0, 80.0, 99.0, 100.0, 199.0, 350.0)
                else listOf(null, 0.0, 9.0, 99.0, 999.0, 1000.0, 2400.0, 9999.0, 99999.0, 999999.0, Double.MAX_VALUE)
            for (value in values) {
                val fixture = GlanceReading(value, now, now + 300_000, "Recorded at 10:00")
                val short = glanceComplication(context, metric, ComplicationType.SHORT_TEXT, fixture) as ShortTextComplicationData
                val text = short.text.getTextAt(context.resources, Instant.ofEpochMilli(now)).toString()
                assertTrue("Unit lost: $text", text.endsWith(unit))
                assertTrue("Host may truncate: $text", text.length <= 7)
                if (metric == "heart" && value != null) assertEquals("${value.toInt()}bpm", text)
                val spoken = short.contentDescription!!.getTextAt(context.resources, Instant.ofEpochMilli(now)).toString()
                assertTrue(spoken.contains(if (metric == "heart") "heart rate" else "steps"))
                assertTrue(spoken.contains("Recorded at 10:00"))
                val long = glanceComplication(context, metric, ComplicationType.LONG_TEXT, fixture) as LongTextComplicationData
                assertTrue(long.text.getTextAt(context.resources, Instant.ofEpochMilli(now)).toString().endsWith(if (metric == "heart") "bpm" else "steps"))
                assertEquals(short.validTimeRange, long.validTimeRange)
            }
        }
        ActivityScenario.launch(WatchActivity::class.java).use { scenario ->
            data.tapAction!!.send()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Measure").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("Measure").assertExists()
            compose.onRoot().performTouchInput { swipeLeft() }
            compose.onNodeWithText("Sync now").assertExists()
            val ambient = shell("settings get global ambient_enabled")
            try {
                shell("settings put global ambient_enabled 1")
                shell("input keyevent KEYCODE_SLEEP")
                compose.waitUntil(10000) { compose.onAllNodesWithTag("watch-ambient").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
                shell("input keyevent KEYCODE_WAKEUP")
                compose.waitUntil(10000) { compose.onAllNodesWithText("Sync now").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            } finally {
                shell("input keyevent KEYCODE_WAKEUP")
                if (ambient == "null") shell("settings delete global ambient_enabled") else shell("settings put global ambient_enabled $ambient")
            }
            scenario.recreate()
            compose.onNodeWithText("Sync now").assertExists()
            data.tapAction!!.send()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Measure").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            scenario.recreate()
            compose.onNodeWithText("Measure").assertExists()
            glanceComplication(context, "steps", ComplicationType.SHORT_TEXT,
                glanceReading(null, "steps", now, zone))!!.tapAction!!.send()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Watch steps").fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty() }
            compose.onNodeWithText("Watch steps").assertIsDisplayed()
            assertNull(WatchWorkoutService.state.value.workout?.takeIf { it.phase in setOf("active", "paused") })
        }
    }
}
