package com.mani.orbit

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import com.mani.orbit.sync.TraceTier

/** Visible-window signals only. Nothing here owns recording, transport, persistence or sensors. */
internal class GlassQualityMonitor(private val context: Context) {
    private val policy = GlassQualityPolicy(Build.VERSION.SDK_INT >= 33, Build.VERSION.SDK_INT >= 31)
    val state = policy.state
    private val power = context.getSystemService(PowerManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var active = false
    private var listening = false
    private var receiving = false
    private var status: Int? = null
    private val thermal = PowerManager.OnThermalStatusChangedListener { value ->
        if (active) { status = value.takeIf { it in 0..6 }; update() }
    }
    private val saver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { if (active) update() }
    }
    private val poll = object : Runnable {
        override fun run() {
            if (!active) return
            update()
            handler.postDelayed(this, 10_000)
        }
    }

    fun start() {
        if (active) return
        active = true
        if (power != null) {
            try { status = power.currentThermalStatus.takeIf { it in 0..6 }; power.addThermalStatusListener(context.mainExecutor, thermal); listening = true }
            catch (_: RuntimeException) { status = null }
        }
        try {
            val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(saver, filter, Context.RECEIVER_NOT_EXPORTED)
            else @Suppress("DEPRECATION") context.registerReceiver(saver, filter)
            receiving = true
        } catch (_: RuntimeException) { /* Resume and bounded polling still read the public power setting. */ }
        poll.run()
    }

    fun stop() {
        active = false; handler.removeCallbacks(poll); policy.pause()
        if (listening) { power?.removeThermalStatusListener(thermal); listening = false }
        if (receiving) { context.unregisterReceiver(saver); receiving = false }
    }

    fun touch(action: Int) {
        when (action) {
            MotionEvent.ACTION_DOWN -> policy.touch(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> policy.touch(false)
        }
    }
    fun frame(start: Long, total: Long, deadline: Long, tier: TraceTier) {
        if (active) policy.frame(start, total, deadline, tier)
    }

    private fun update() {
        val now = SystemClock.elapsedRealtime()
        // PowerManager rate limits this query across calls. Preserve the last query across recreation;
        // a repeated activity start must not turn a valid reading into an artificial NaN.
        if (lastQuery == Long.MIN_VALUE || now - lastQuery >= 10_000 || now < lastQuery) {
            lastQuery = now
            headroom = try { power?.getThermalHeadroom(0)?.takeIf { it.isFinite() && it >= 0f } } catch (_: RuntimeException) { null }
            thresholds = if (Build.VERSION.SDK_INT >= 35) try {
                power?.thermalHeadroomThresholds?.filter { (level, value) -> level in 1..6 && value.isFinite() && value >= 0f }.orEmpty()
            } catch (_: RuntimeException) { emptyMap() } else emptyMap()
        }
        val freshHeadroom = headroom?.takeIf { now >= lastQuery && now - lastQuery <= 20_000 }
        val mapped = freshHeadroom?.let { value -> thresholds.filterValues { value >= it }.keys.maxOrNull()
            ?: if (value >= 1f) PowerManager.THERMAL_STATUS_SEVERE else PowerManager.THERMAL_STATUS_NONE }
        // NONE on its own is not evidence of thermal support; some devices always return it.
        val effective = if (status == null) mapped else maxOf(status!!, mapped ?: 0)
        val powerSaver = try { power?.isPowerSaveMode ?: false } catch (_: RuntimeException) { false }
        policy.conditions(effective, freshHeadroom != null, powerSaver)
    }

    private companion object {
        // Main-thread-only cache of a rate-limited platform signal, never saved or sent anywhere.
        var lastQuery = Long.MIN_VALUE
        var headroom: Float? = null
        var thresholds: Map<Int, Float> = emptyMap()
    }
}
