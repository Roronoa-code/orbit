package com.mani.orbit

import kotlin.math.*

/*
 * The Home ring, after the reference the owner chose: broad bands of particles folding round a clear
 * centre, lit like a surface. Each band is a closed strip in 3D whose centreline undulates in radius
 * and in depth, whose width breathes, and which twists along its length.
 *
 * What makes the reference read as volume rather than clutter is its light. Most of the surface faces
 * the viewer and is dark — a faint dotted mesh in deep indigo. Only where a band turns edge-on does it
 * catch the light, and those folds are the bright lavender ridges that draw the form. So every grain is
 * lit by how edge-on its surface is (a rim light) plus a little key light from the upper left, and the
 * grains are drawn additively so a fold's projected density brightens it further. Lighting everything
 * evenly is what made the first version busy; thinning it out is what made the second one flat.
 *
 * Every wave number and twist is whole, so each band closes on itself without a seam.
 */

/** The ring's design space: 340 wide by 300 tall, centred. */
internal const val RingWidth = 340.0
internal const val RingHeight = 300.0
internal const val RingCentreX = RingWidth / 2
internal const val RingCentreY = RingHeight / 2

/** Centreline radius and the half width of a band. Broad bands give the ring its volume. */
private const val RingMean = 114.0
private const val BandHalfWidth = 26.0

/** The ring leans back from the viewer, so its near side reads nearer and its bands fold in depth. */
private val RingLean = 26.0 * PI / 180
private val LeanCos = cos(RingLean)
private val LeanSin = sin(RingLean)

/** Camera distance for the perspective, in design units. */
private const val Camera = 460.0

/** The key light: from the upper left and in front, normalised. */
private const val KeyX = -.48
private const val KeyY = -.52
private const val KeyZ = .71

/** The centre is the number's. No grain comes closer in the ring's own plane, at any moment of the flow. */
internal const val RingClearRadius = 70.0

internal const val SheetSteps = 360
internal const val SheetAcross = 24

private class Band(
    val n1: Int, val a1: Double, val w1: Double, val p1: Double,
    val n2: Int, val a2: Double, val w2: Double, val p2: Double,
    val m: Int, val w3: Double, val p3: Double,
    val twist: Int, val w4: Double, val p4: Double,
    val n3: Int, val a3: Double, val w5: Double, val drift: Double,
)

// A few big lobes rather than many small wiggles, and different speeds per band so they stay out of
// step. The twist speed carries each fold slowly along its band; it drifts, it never churns.
private val Bands = arrayOf(
    Band(2, 9.0, .16, 0.0, 3, 4.0, .11, 1.3, 2, .15, .4, 2, .22, 0.0, 2, 18.0, .13, .026),
    Band(3, 7.0, .13, 2.1, 2, 5.0, .15, .6, 3, .12, 1.9, 1, .19, 1.7, 3, 14.0, .17, -.021),
    Band(2, 8.0, .15, 4.0, 4, 3.0, .12, 2.8, 2, .17, 3.1, 2, .26, 3.3, 1, 20.0, .11, .016),
    Band(3, 6.0, .12, 5.3, 2, 4.0, .14, 3.7, 3, .14, 2.4, 3, .20, .8, 2, 16.0, .15, -.018),
)
internal val SheetCount get() = Bands.size

/**
 * One cross-section of one band at [u] round the ring, as twelve values: its centre in 3D, the
 * direction its width runs, its half width, the cosine and sine of its twist and of its direction round
 * the ring, and the light the section's surface catches. A band is flat across its width, so every
 * grain in one section shares its surface light; working it out once per section rather than once per
 * grain is most of what keeps the ring cheap to animate. [spin] turns the whole ring; [swell] moves it
 * outward.
 */
internal fun sheetSection(sheet: Int, u: Double, time: Double, spin: Double, swell: Double, out: DoubleArray) {
    val b = Bands[sheet]
    val theta = u + spin + b.drift * time
    val c = cos(theta); val n = sin(theta)
    val radius = RingMean + swell + b.a1 * sin(b.n1 * u + b.w1 * time + b.p1) + b.a2 * sin(b.n2 * u - b.w2 * time + b.p2)
    val twist = b.twist * u + b.w4 * time + b.p4
    val ct = cos(twist); val st = sin(twist)
    out[0] = radius * c; out[1] = radius * n; out[2] = b.a3 * sin(b.n3 * u + b.w5 * time)
    out[3] = ct * c; out[4] = ct * n; out[5] = st
    out[6] = BandHalfWidth * (.6 + .4 * sin(b.m * u - b.w3 * time + b.p3))
    out[7] = ct; out[8] = st; out[9] = c; out[10] = n
    // The band's surface normal is its width direction turned a quarter about its length.
    val nx = st * c; val ny = st * n; val nz = -ct
    val viewY = ny * LeanCos - nz * LeanSin
    val viewZ = ny * LeanSin + nz * LeanCos
    val rim = (1 - abs(viewZ)).pow(2.4)
    val key = abs(nx * KeyX + viewY * KeyY + viewZ * KeyZ)
    out[11] = .12 + .58 * rim + .16 * key * key
}

/**
 * A grain [v] of the way across a section, -1..1: where it lands in the design space, how much light
 * it catches (0..1), and — for checks — its distance from the centre in the ring's plane.
 */
internal fun sheetGrain(section: DoubleArray, v: Double, out: DoubleArray) {
    val across = v * section[6]
    val x = section[0] + across * section[3]
    val y = section[1] + across * section[4]
    val z = section[2] + across * section[5]
    // Lean the ring back about its horizontal axis, then project.
    val leanY = y * LeanCos - z * LeanSin
    val leanZ = y * LeanSin + z * LeanCos
    val scale = Camera / (Camera - leanZ)
    out[0] = RingCentreX + x * scale
    out[1] = RingCentreY + leanY * scale
    val near = (leanZ / (RingMean + BandHalfWidth)).coerceIn(-1.0, 1.0)
    out[2] = (section[11] + .12 * (near + 1) / 2).coerceIn(0.0, 1.0)
    out[3] = sqrt(x * x + y * y)
}

/** Deep indigo in shadow, the app's lavender on the lit folds, near white on the brightest ridges. */
internal fun ringColour(light: Float): Int {
    val t = light.coerceIn(0f, 1f)
    fun mix(a: Int, b: Int, f: Float) = (a + (b - a) * f.coerceIn(0f, 1f)).roundToInt()
    fun channel(colour: Int, shift: Int) = colour shr shift and 255
    val (from, to, f) = if (t < .5f) Triple(0x4A3C90, 0x9E8AE6, t / .5f) else Triple(0x9E8AE6, 0xEDE8FF, (t - .5f) / .5f)
    val alpha = ((.10f + .66f * t.pow(1.3f)) * 255).roundToInt()
    return (alpha shl 24) or (mix(channel(from, 16), channel(to, 16), f) shl 16) or
        (mix(channel(from, 8), channel(to, 8), f) shl 8) or mix(channel(from, 0), channel(to, 0), f)
}

/** A stable few grains catch a glint — only on lit folds, where the reference's sparkle sits. */
internal fun glints(sheet: Int, step: Int, across: Int, light: Double): Boolean =
    light > .45 && ((sheet * 73856093) xor (step * 19349663) xor (across * 83492791)).mod(1000) < 14
