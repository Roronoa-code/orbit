package com.mani.orbit.wear

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.icu.text.CompactDecimalFormat
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.Locale

class WatchStepsComplication : WatchReadingComplication("steps")
class WatchHeartComplication : WatchReadingComplication("heart")

abstract class WatchReadingComplication(private val metric: String) : SuspendingComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        val now = System.currentTimeMillis()
        val reading = try { withContext(Dispatchers.IO) {
            val store = WatchStore(this@WatchReadingComplication)
            store.journal().use { glanceReading(it.latest(metric, store.installation, store.clock().boot), metric, now) }
        } } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { GlanceReading(null, null, now, "Open Orbit to retry") }
        return glanceComplication(this, metric, request.complicationType, reading)
    }
    override fun getPreviewData(type: ComplicationType): ComplicationData? = glanceComplication(this, metric, type, GlanceReading(null, null, 0, "Open Orbit"))
}

internal fun glanceComplication(context: Context, metric: String, type: ComplicationType, reading: GlanceReading): ComplicationData? {
    require(metric in setOf("steps", "heart"))
    val label = if (metric == "heart") "bpm" else "steps"
    val value = reading.value?.let { "%,.0f".format(Locale.UK, it) } ?: "—"
    // Required text carries its unit even when the host omits our optional title/image.
    // stp avoids confusing steps with the UK weight abbreviation st; seven characters total.
    val compact = reading.value?.let {
        if (metric == "heart" && it < 1000) "%.0f".format(Locale.UK, it)
        else if (it >= 100_000_000_000_000.0) ">99T" else
            CompactDecimalFormat.getInstance(Locale.UK, CompactDecimalFormat.CompactStyle.SHORT)
                .apply { maximumSignificantDigits = 2 }.format(it)
    } ?: "—"
    val short = "$compact${if (metric == "heart") "bpm" else "stp"}"
    val description = PlainComplicationText.Builder("Watch ${if (metric == "heart") "heart rate" else "steps"}: $value $label. ${reading.explanation}").build()
    val intent = PendingIntent.getActivity(context, if (metric == "heart") 2 else 1,
        Intent(context, WatchActivity::class.java).putExtra("orbit-page", if (metric == "heart") "heart" else "today")
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val validity = if (reading.value != null) TimeRange.before(Instant.ofEpochMilli(reading.expires - 1)) else TimeRange.ALWAYS
    return when (type) {
        ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(PlainComplicationText.Builder(short).build(), description)
            .setTitle(PlainComplicationText.Builder(if (metric == "heart") "Pulse" else "Steps").build())
            .setTapAction(intent).setValidTimeRange(validity).build()
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(PlainComplicationText.Builder("$value $label").build(), description)
            .setTitle(PlainComplicationText.Builder(reading.explanation).build()).setTapAction(intent).setValidTimeRange(validity).build()
        else -> null
    }
}
