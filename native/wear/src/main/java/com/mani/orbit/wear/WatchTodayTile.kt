package com.mani.orbit.wear

import androidx.wear.protolayout.ActionBuilders.*
import androidx.wear.protolayout.DimensionBuilders.*
import androidx.wear.protolayout.LayoutElementBuilders.*
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Background
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ColorBuilders
import androidx.wear.protolayout.TimelineBuilders.*
import androidx.wear.protolayout.material3.*
import androidx.wear.protolayout.material3.ButtonDefaults.filledTonalButtonColors
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders.TileRequest
import androidx.wear.tiles.TileBuilders.Tile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

internal val WatchTileColors = ColorScheme(primary = 0xFFBBA1ED.argb, onPrimary = 0xFF20142F.argb,
    surfaceContainer = 0xFF201D27.argb, background = 0xFF0B0A0F.argb)

class WatchTodayTile : Material3TileService(allowDynamicTheme = false, defaultColorScheme = WatchTileColors) {
    override suspend fun MaterialScope.tileResponse(requestParams: TileRequest): Tile {
        val now = System.currentTimeMillis()
        val readings = try { withContext(Dispatchers.IO) {
            val store = WatchStore(this@WatchTodayTile)
            store.journal().use { journal -> listOf("steps", "heart").associateWith { glanceReading(journal.latest(it, store.installation, store.clock().boot), it, now) } }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        return watchTodayTile(readings, now)
    }
}

internal fun MaterialScope.watchTodayTile(readings: Map<String, GlanceReading>?, now: Long): Tile {
    fun open(page: String) = Clickable.Builder().setId(page).setOnClick(LaunchAction.Builder().setAndroidActivity(
        AndroidActivity.Builder().setPackageName(context.packageName).setClassName(WatchActivity::class.java.name)
            .addKeyToExtraMapping("orbit-page", stringExtra(page)).build()).build()).build()
    fun layoutAt(at: Long): LayoutElement {
        val steps = readings?.get("steps")?.takeIf { it.expires > at }
        val heart = readings?.get("heart")?.takeIf { it.expires > at }
        val stamp = steps?.at?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm", Locale.UK)) }
        val content = primaryLayout(
            titleSlot = { text("Watch today".layoutString, typography = Typography.BODY_SMALL) },
            mainSlot = {
                Column.Builder().setWidth(expand()).setHorizontalAlignment(HORIZONTAL_ALIGN_CENTER)
                    .addContent(text((steps?.value?.let { "%,.0f".format(Locale.UK, it) } ?: "—").layoutString, typography = Typography.DISPLAY_SMALL, color = colorScheme.primary))
                    .addContent(text((stamp?.let { "steps · $it" } ?: if (readings == null) "Open Orbit to retry" else "No steps recorded").layoutString, typography = Typography.LABEL_SMALL, color = colorScheme.onSurface))
                    .addContent(Spacer.Builder().setHeight(dp(2f)).build())
                    .addContent(textButton(open("heart"), { text((heart?.value?.let { "Latest ${it.toInt()} bpm" } ?: "Check pulse").layoutString, typography = Typography.BODY_SMALL) }, width = expand(), height = dp(48f), colors = filledTonalButtonColors()))
                    .build()
            },
            bottomSlot = { textEdgeButton(open("workouts")) { text("Workouts".layoutString) } },
            onClick = open("today"))
        return Box.Builder().setWidth(expand()).setHeight(expand()).setModifiers(Modifiers.Builder()
            .setBackground(Background.Builder().setColor(ColorBuilders.argb(0xFF0B0A0F.toInt())).build()).build())
            .addContent(content).build()
    }
    // The system advances these entries even if it defers our next background update.
    val boundaries = (listOf(now) + readings.orEmpty().values.filter { it.value != null && it.expires > now }.map { it.expires }).distinct().sorted()
    val timeline = Timeline.Builder()
    boundaries.forEachIndexed { index, at ->
        timeline.addTimelineEntry(TimelineEntry.Builder().setValidity(TimeInterval.Builder().setStartMillis(at)
            .setEndMillis(boundaries.getOrNull(index + 1) ?: Long.MAX_VALUE).build())
            .setLayout(Layout.Builder().setRoot(layoutAt(at)).build()).build())
    }
    return Tile.Builder().setFreshnessIntervalMillis(5 * 60_000).setTileTimeline(timeline.build()).build()
}
