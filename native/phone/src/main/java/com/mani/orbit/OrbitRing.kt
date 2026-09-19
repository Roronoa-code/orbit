package com.mani.orbit

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.*

/*
 * The Home ring is the owner's reference brought to life: "a thunderstorm approaching in dark clouds",
 * moving as one living thing rather than as a picture being turned.
 *
 * The reference supplies the storm's form. Its flow (assets/storm/field.bin) was read off the sheets of
 * the reference itself: at every point, the direction its dotted sheets run and how bright the storm
 * is there. Its dots (assets/storm/dots.bin) are where particles are born, each with that dot's own
 * colour and strength. Its soft light, with the faint mesh between the dots (assets/storm/glow.png),
 * is the cloud they move through.
 *
 * Particles are born at the reference's dots and carried along its sheets, each at its own pace with a
 * little drift of its own; they brighten through the bright cloud, dim in the dark, fade out after a
 * few seconds and are born again elsewhere. So the storm keeps the reference's shape while nothing in
 * it ever stands still, and the whole form turns slowly. Now and then lightning flickers through a
 * stretch of it. Imitations built from waves missed the reference; a picture of it bent and turned
 * looked like a GIF, not a storm.
 */

/** The ring's design space is the reference's own: 480 by 453, centred on its ring. */
internal const val RingWidth = 480.0
internal const val RingHeight = 453.0
internal const val RingCentreX = 240.0
internal const val RingCentreY = 226.5

/** The centre is the number's. A particle carried closer than this is gone before it is drawn. */
internal const val RingClearRadius = 95.0
private const val RingOuterRadius = 238.0

internal const val StormParticles = 9000
/** The form turns once in four minutes; particles stream through it far faster. */
private const val FormTurnSeconds = 240.0
private const val Speed = 22.0
private const val SpeedSpread = .18
private const val LifeShortest = 4.0
private const val LifeLongest = 9.0
private const val Fade = .4f
private const val Drift = 1.5

/** A particle's soft point: the reference's own dots are Gaussians of this width, in its pixels. */
private const val DotSigma = .62f
private const val DotReach = DotSigma * 3

/** How many angles round the ring the storm's light is worked out at, each step. */
internal const val LightSteps = 48
/** Strikes come about every couple of seconds, each at a moment and a place of its own. */
private const val StrikeSeconds = 2.6

/**
 * The storm's purple light round the ring at [time]: [out] gets, for each of [LightSteps] angles
 * (radians from the ring's right, clockwise on screen), how lit the cloud there is, 0 dark to about 1.2
 * at a strike's peak. Returns the strongest strike's brightness now, and puts where it is in [strongest].
 *
 * The cloud is dark but for a faint light stirring slowly round it. Strikes come every couple of
 * seconds somewhere round the ring: a flash, a flicker and a glow that dies away over about two
 * seconds, drifting a little along the cloud as it goes. Stateless, so every step agrees.
 */
internal fun stormLight(time: Double, out: FloatArray, strongest: DoubleArray, struck: DoubleArray? = null): Double {
    fun hash(n: Long, salt: Long): Double {
        var h = n * -7046029254386353131L + salt * -4658895280553007687L
        h = (h xor (h ushr 31)) * -7723592293110705685L
        return ((h ushr 11) and 0xFFFFF).toDouble() / 0xFFFFF
    }
    for (k in 0 until LightSteps) {
        val a = k * 2 * PI / LightSteps
        out[k] = (.10 + .08 * (.5 + .5 * sin(2 * a - .3 * time)) + .05 * (.5 + .5 * sin(3 * a + .21 * time + 1.7))).toFloat()
    }
    var peak = 0.0
    val stretch = floor(time / StrikeSeconds).toLong()
    for (m in stretch - 2..stretch) {
        val since = time - (m * StrikeSeconds + 2.0 * hash(m, 1))
        if (since < 0 || since > 2.4) continue
        val pulse = exp(-(since / .08).pow(2)) + .7 * exp(-((since - .25) / .12).pow(2)) + .5 * exp(-((since - .6) / .5).pow(2))
        val strength = (.55 + .6 * hash(m, 4)) * pulse
        val width = .35 + .45 * hash(m, 3)
        val centre = hash(m, 2) * 2 * PI + (hash(m, 5) - .5) * .4 * since
        for (k in 0 until LightSteps) {
            val d = k * 2 * PI / LightSteps - centre
            val apart = atan2(sin(d), cos(d))
            out[k] += (strength * exp(-(apart / width).pow(2))).toFloat()
        }
        if (strength > peak) { peak = strength; strongest[0] = centre }
    }
    // A strike the owner set off: the same flash, flicker and dying glow, where they asked for it.
    if (struck != null) {
        val since = time - struck[0]
        if (since in 0.0..2.4) {
            val pulse = exp(-(since / .08).pow(2)) + .7 * exp(-((since - .25) / .12).pow(2)) + .5 * exp(-((since - .6) / .5).pow(2))
            val strength = .9 * pulse
            for (k in 0 until LightSteps) {
                val d = k * 2 * PI / LightSteps - struck[1]
                val apart = atan2(sin(d), cos(d))
                out[k] += (strength * exp(-(apart / .6).pow(2))).toFloat()
            }
            if (strength > peak) { peak = strength; strongest[0] = struck[1] }
        }
    }
    for (k in 0 until LightSteps) out[k] = min(out[k], 1.25f)
    return peak
}

internal class Storm(val glow: Bitmap, field: ByteBuffer, dots: ByteBuffer, seed: Long = 5) {
    private val fieldWidth: Int
    private val fieldHeight: Int
    private val flowX: FloatArray
    private val flowY: FloatArray
    private val shine: FloatArray
    private val sourceX: FloatArray
    private val sourceY: FloatArray
    private val sourceColour: IntArray
    private val sourceWeight: DoubleArray

    val count = StormParticles
    private val x = FloatArray(count)
    private val y = FloatArray(count)
    private val age = FloatArray(count)
    private val life = FloatArray(count)
    private val pace = FloatArray(count)
    private val home = FloatArray(count)
    private val colour = IntArray(count)
    private val twinkleSpeed = FloatArray(count)
    private val twinklePhase = FloatArray(count)
    private val driftPhase = FloatArray(count)
    private val random = java.util.Random(seed)
    private var clock = Double.NaN

    /** The form's slow turn at the last step, radians. */
    private var formTurn = 0.0

    /**
     * One finished picture of the storm: its particles' corners and colours, the light round the ring,
     * the form's turn and the strongest strike. Two exist: one is shown while the next is built, off the
     * main thread, so the screen always has a whole storm and touch never waits on one.
     */
    inner class Frame {
        val dotMesh = FloatArray(count * 8)
        val dotTint = IntArray(count * 4)
        val light = FloatArray(LightSteps)
        var formTurn = 0.0
        /** The strongest strike's brightness, and where it is in design space. */
        val flash = DoubleArray(3)
        var time = Double.NaN
    }
    private val frames = arrayOf(Frame(), Frame())
    @Volatile private var front = 0
    /** The frame on screen. Only the main thread swaps it, and only between draws. */
    val shown: Frame get() = frames[front]

    /** Build the storm at [time] seconds into the frame not on screen. Any thread; one at a time. */
    fun prepare(time: Double) { advance(time); build(time, frames[1 - front]) }
    /** Put the frame [prepare] built on screen. Main thread. */
    fun show() { front = 1 - front }

    /** The fixed texture corners and triangles every frame's particles are drawn with. */
    val dotTexture = FloatArray(count * 8).also { t ->
        for (i in 0 until count) { val o = i * 8; val s = DotTextureSize.toFloat()
            t[o] = 0f; t[o + 1] = 0f; t[o + 2] = s; t[o + 3] = 0f; t[o + 4] = s; t[o + 5] = s; t[o + 6] = 0f; t[o + 7] = s }
    }
    // Sixteen-bit indices reach 65,536 corners: 16,384 particles, more than the storm has.
    val dotOrder = ShortArray(count * 6).also { o ->
        for (i in 0 until count) { val v = i * 4; val k = i * 6
            o[k] = v.toShort(); o[k + 1] = (v + 1).toShort(); o[k + 2] = (v + 2).toShort()
            o[k + 3] = v.toShort(); o[k + 4] = (v + 2).toShort(); o[k + 5] = (v + 3).toShort() }
    }
    @Volatile private var struck = doubleArrayOf(-99.0, 0.0)

    /** Set off lightning at [time] seconds, at [angle] radians round the storm. Any thread. */
    fun strike(time: Double, angle: Double) { struck = doubleArrayOf(time, angle) }
    private val strongest = DoubleArray(1)

    init {
        val f = field.order(ByteOrder.LITTLE_ENDIAN)
        require(f.getInt() == 0x4642524F) { "Not a storm field" }     // "ORBF"
        require(f.getInt() == 1) { "Unknown storm field version" }
        fieldWidth = f.getInt(); fieldHeight = f.getInt()
        require(fieldWidth in 16..2048 && fieldHeight in 16..2048) { "Implausible storm field" }
        val cells = fieldWidth * fieldHeight
        flowX = FloatArray(cells) { f.get() / 127f }
        flowY = FloatArray(cells) { f.get() / 127f }
        shine = FloatArray(cells) { (f.get().toInt() and 255) / 255f * 1.5f }
        val d = dots.order(ByteOrder.LITTLE_ENDIAN)
        require(d.getInt() == 0x5342524F) { "Not a storm" }            // "ORBS"
        require(d.getInt() == 1) { "Unknown storm version" }
        val sources = d.getInt()
        require(sources in 1..65_536) { "Implausible storm" }
        sourceX = FloatArray(sources); sourceY = FloatArray(sources); sourceColour = IntArray(sources); sourceWeight = DoubleArray(sources)
        var total = 0.0
        for (i in 0 until sources) {
            sourceX[i] = d.getFloat(); sourceY[i] = d.getFloat()
            val r = d.get().toInt() and 255; val g = d.get().toInt() and 255; val b = d.get().toInt() and 255
            d.get()
            sourceColour[i] = (r shl 16) or (g shl 8) or b
            total += (r + g + b) / 3.0
            sourceWeight[i] = total
        }
        for (i in 0 until count) {
            twinkleSpeed[i] = 1f + 2f * random.nextFloat()
            twinklePhase[i] = random.nextFloat() * 2 * PI.toFloat()
            driftPhase[i] = random.nextFloat() * 2 * PI.toFloat()
            born(i, 0.0)
            // The storm opens as the reference itself: everyone already partway through a life.
            age[i] = random.nextFloat() * life[i]
        }
    }

    /** Bilinear read of a field at design point ([px], [py]) in the form's own frame. */
    private fun read(field: FloatArray, px: Float, py: Float): Float {
        val gx = (px / RingWidth.toFloat() * fieldWidth - .5f).coerceIn(0f, fieldWidth - 1.001f)
        val gy = (py / RingHeight.toFloat() * fieldHeight - .5f).coerceIn(0f, fieldHeight - 1.001f)
        val x0 = gx.toInt(); val y0 = gy.toInt(); val fx = gx - x0; val fy = gy - y0
        val k = y0 * fieldWidth + x0
        return field[k] * (1 - fx) * (1 - fy) + field[k + 1] * fx * (1 - fy) +
            field[k + fieldWidth] * (1 - fx) * fy + field[k + fieldWidth + 1] * fx * fy
    }

    /** A particle is born at one of the reference's dots, chosen by its strength, turned with the form. */
    private fun born(i: Int, turn: Double) {
        val pick = random.nextDouble() * sourceWeight.last()
        var lo = 0; var hi = sourceWeight.size - 1
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (sourceWeight[mid] < pick) lo = mid + 1 else hi = mid }
        val bx = sourceX[lo] + random.nextGaussian().toFloat()
        val by = sourceY[lo] + random.nextGaussian().toFloat()
        home[i] = max(read(shine, bx, by), .05f)
        colour[i] = sourceColour[lo]
        val c = cos(turn).toFloat(); val s = sin(turn).toFloat()
        val ox = bx - RingCentreX.toFloat(); val oy = by - RingCentreY.toFloat()
        x[i] = RingCentreX.toFloat() + ox * c - oy * s
        y[i] = RingCentreY.toFloat() + ox * s + oy * c
        age[i] = 0f
        life[i] = (LifeShortest + (LifeLongest - LifeShortest) * random.nextDouble()).toFloat()
        pace[i] = (Speed * (1 - SpeedSpread + 2 * SpeedSpread * random.nextDouble())).toFloat()
    }

    /** Carry every particle forward to [time] seconds, in steps no longer than a thirtieth of a second. */
    fun advance(time: Double) {
        if (clock.isNaN() || time < clock || time - clock > 2.0) { clock = time; formTurn = 2 * PI * time / FormTurnSeconds; return }
        while (clock < time - 1e-6) {
            val dt = min(1 / 30.0, time - clock)
            clock += dt
            step(clock, dt.toFloat())
        }
    }

    private fun step(t: Double, dt: Float) {
        formTurn = 2 * PI * t / FormTurnSeconds
        val c = cos(formTurn).toFloat(); val s = sin(formTurn).toFloat()
        val cx = RingCentreX.toFloat(); val cy = RingCentreY.toFloat()
        val tf = t.toFloat()
        for (i in 0 until count) {
            // Into the form's own frame, read its flow there, and carry the particle along it.
            val ox = x[i] - cx; val oy = y[i] - cy
            val fx = cx + ox * c + oy * s; val fy = cy - ox * s + oy * c
            val ux = read(flowX, fx, fy); val uy = read(flowY, fx, fy)
            val wx = ux * c - uy * s; val wy = ux * s + uy * c
            // A little sideways drift of its own, so no two particles trace quite the same line.
            val side = (Drift * quickSin(1.3f * tf + driftPhase[i])).toFloat()
            x[i] += (wx * pace[i] - wy * side) * dt
            y[i] += (wy * pace[i] + wx * side) * dt
            age[i] += dt
            val nx = x[i] - cx; val ny = y[i] - cy
            val r2 = nx * nx + ny * ny
            if (age[i] > life[i] || r2 > RingOuterRadius * RingOuterRadius || r2 < RingClearRadius * RingClearRadius) born(i, formTurn)
        }
    }

    /**
     * Fill [frame] with the storm as last advanced, at [time] seconds. [radii], if given, receives each
     * drawn particle's distance from the centre (a particle not drawn gets -1).
     */
    fun build(time: Double, frame: Frame, radii: FloatArray? = null) {
        val light = frame.light
        val dotMesh = frame.dotMesh
        val dotTint = frame.dotTint
        val strike = stormLight(time, light, strongest, struck)
        frame.flash[0] = strike; frame.flash[1] = RingCentreX + 165 * cos(strongest[0]); frame.flash[2] = RingCentreY + 165 * sin(strongest[0])
        frame.formTurn = formTurn
        frame.time = time
        val c = cos(formTurn).toFloat(); val s = sin(formTurn).toFloat()
        val cx = RingCentreX.toFloat(); val cy = RingCentreY.toFloat()
        val tf = time.toFloat()
        for (i in 0 until count) {
            val ox = x[i] - cx; val oy = y[i] - cy
            val r = sqrt(ox * ox + oy * oy)
            val shown = r >= RingClearRadius
            radii?.set(i, if (shown) r else -1f)
            val o = i * 8
            val px = x[i]; val py = y[i]
            dotMesh[o] = px - DotReach; dotMesh[o + 1] = py - DotReach; dotMesh[o + 2] = px + DotReach; dotMesh[o + 3] = py - DotReach
            dotMesh[o + 4] = px + DotReach; dotMesh[o + 5] = py + DotReach; dotMesh[o + 6] = px - DotReach; dotMesh[o + 7] = py + DotReach
            // Its birth dot's strength, brighter in bright cloud and dimmer in dark, fading at both ends of
            // its life, twinkling, and lit up by lightning nearby.
            var glowing = 0f
            if (shown) {
                val fx = cx + ox * c + oy * s; val fy = cy - ox * s + oy * c
                val here = sqrt((read(shine, fx, fy) / home[i]).coerceIn(0f, 1.3f))
                val fade = (age[i] / Fade).coerceIn(0f, 1f) * ((life[i] - age[i]) / Fade).coerceIn(0f, 1f)
                // Dim in the dark cloud, flaring where the storm's light is: a strike lights it up.
                var round = quickAtan2(oy, ox) / (2 * PI.toFloat()) * LightSteps
                if (round < 0) round += LightSteps
                val k0 = round.toInt() % LightSteps; val k1 = (k0 + 1) % LightSteps; val f = round - round.toInt()
                val lit = light[k0] * (1 - f) + light[k1] * f
                glowing = here * fade * (.8f + .2f * quickSin(twinkleSpeed[i] * tf + twinklePhase[i])) * (.38f + 1.05f * lit)
            }
            val k = colour[i]
            fun channel(shift: Int) = min(255, ((k shr shift and 255) * glowing).roundToInt())
            val tint = (0xFF shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
            val v = i * 4
            dotTint[v] = tint; dotTint[v + 1] = tint; dotTint[v + 2] = tint; dotTint[v + 3] = tint
        }
    }

    companion object {
        private const val SineSteps = 1024
        private val sine = FloatArray(SineSteps) { sin(it * 2 * PI / SineSteps).toFloat() }
        /** Sine from a table: twinkle and drift need its shape, not its last digits. */
        private fun quickSin(a: Float): Float = sine[Math.floorMod((a * (SineSteps / (2 * PI.toFloat()))).toInt(), SineSteps)]
        /** Arctangent within a few thousandths of a radian: enough to look up the light round the ring. */
        private fun quickAtan2(y: Float, x: Float): Float {
            val ax = abs(x); val ay = abs(y)
            if (ax == 0f && ay == 0f) return 0f
            val a = min(ax, ay) / max(ax, ay); val s = a * a
            var r = ((-.0464964749f * s + .15931422f) * s - .327622764f) * s * a + a
            if (ay > ax) r = 1.57079637f - r
            if (x < 0) r = 3.14159274f - r
            return if (y < 0) -r else r
        }

        /** The soft point every particle is drawn with: a white Gaussian in alpha, sized to its reach. */
        const val DotTextureSize = 32
        fun dotTexture(): Bitmap = Bitmap.createBitmap(DotTextureSize, DotTextureSize, Bitmap.Config.ARGB_8888).also { bitmap ->
            val s = DotTextureSize / 2f; val sigma = DotTextureSize / 2f / 3f
            for (py in 0 until DotTextureSize) for (px in 0 until DotTextureSize) {
                val d2 = (px + .5f - s).pow(2) + (py + .5f - s).pow(2)
                val a = (exp(-d2 / (2 * sigma * sigma)) * 255).roundToInt()
                bitmap.setPixel(px, py, (a shl 24) or 0xFFFFFF)
            }
        }

        fun load(assets: AssetManager): Storm {
            val glow = assets.open("storm/glow.png").use { BitmapFactory.decodeStream(it) } ?: error("Storm glow unreadable")
            val field = assets.open("storm/field.bin").use { it.readBytes() }
            val dots = assets.open("storm/dots.bin").use { it.readBytes() }
            return Storm(glow, ByteBuffer.wrap(field), ByteBuffer.wrap(dots))
        }
    }
}
