package com.mani.orbit

import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min

/**
 * Steps Orbit's watch app has counted that Samsung Health has not reported yet.
 *
 * Samsung Health merges the phone's and the watch's steps, but it hears from the watch on Samsung's own
 * schedule: walk without the phone and its total stands still while the watch counts. The watch's own
 * count since midnight reaches Orbit within seconds, so the steps it has counted since Samsung last
 * caught up are added to Samsung's total. Whenever Samsung's total rises, the rise is taken to cover the
 * oldest of those steps first. Carrying the phone as well, the lead stays at nothing and no step is
 * counted twice; when Samsung catches up, the lead folds into its total without the number going back.
 *
 * The first time both counts are seen they are taken to agree, so a lead is only ever what the watch
 * counted while Orbit was watching.
 */
internal class WatchStepLead {
    private var day: LocalDate? = null
    private var samsung = Double.NaN
    private var anchor = Double.NaN
    private var watch = Double.NaN
    private var watchAt = 0L

    /** The watch's own step count since [date]'s midnight, counted at [at]. It only moves forward in time. */
    @Synchronized fun watch(steps: Double, at: Long, date: LocalDate) {
        if (!steps.isFinite() || steps < 0) return
        if (date != day) { day = date; samsung = Double.NaN; anchor = Double.NaN; watch = Double.NaN; watchAt = 0L }
        if (at < watchAt) return
        watch = steps; watchAt = at
    }

    /**
     * Samsung Health's [total] for [date] as it stands now. Returns the steps the watch has counted beyond
     * it, the watch's own count and when that was counted; null while the watch has said nothing today.
     */
    @Synchronized fun lead(total: Double, date: LocalDate): Triple<Double, Double, Long>? {
        if (date != day || watch.isNaN() || !total.isFinite()) return null
        if (samsung.isNaN()) { samsung = total; anchor = watch }
        val reported = total - samsung
        if (reported > 0) anchor += min(reported, max(0.0, watch - anchor))
        samsung = total
        return Triple(max(0.0, watch - anchor), watch, watchAt)
    }
}
