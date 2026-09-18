package com.mani.orbit

import kotlin.math.*

/*
 * The Home ring, after the reference the owner chose: a few broad sheets of particles folding over
 * one another round a clear centre. Each sheet is a closed band in 3D whose centreline undulates in
 * radius and in depth, whose width breathes, and which twists about its own length. Where a sheet
 * lies face-on its grains spread into a faint dotted mesh; where it turns edge-on they pile up in
 * projection, and because grains are drawn additively those folds light up by density alone — the
 * bright crests are not painted, they are where the sheet is seen side-on. Every wave number and
 * twist is whole, so each sheet closes on itself without a seam.
 */

/** The ring's design space: 340 wide by 300 tall, centred. */
internal const val RingWidth = 340.0
internal const val RingHeight = 300.0
internal const val RingCentreX = RingWidth / 2
internal const val RingCentreY = RingHeight / 2

/**
 * Centreline radius and the half width of a sheet. Thin strands with room between them read as a few
 * flowing ribbons of dots; broad sheets filled the band and read as a cloud.
 */
private const val RingMean = 116.0
private const val SheetHalfWidth = 14.0

/** The ring leans back from the viewer by this much, so its near side reads nearer. */
private val RingLean = 24.0 * PI / 180
private val LeanCos = cos(RingLean)
private val LeanSin = sin(RingLean)

/** Camera distance for the perspective, in design units. */
private const val Camera = 460.0

/** The centre is the number's. No grain comes closer in the ring's own plane, at any moment of the flow. */
internal const val RingClearRadius = 86.0

/** Few enough grains that each strand reads as its own dotted line. */
internal const val SheetSteps = 360
internal const val SheetAcross = 9

private class Sheet(
    val n1: Int, val a1: Double, val w1: Double, val p1: Double,
    val n2: Int, val a2: Double, val w2: Double, val p2: Double,
    val m: Int, val w3: Double, val p3: Double,
    val twist: Int, val w4: Double, val p4: Double,
    val n3: Int, val a3: Double, val w5: Double, val drift: Double,
)

// Different wave numbers and speeds per sheet keep them out of step, which is what makes the ring
// read as flowing rather than as one shape wobbling. The speeds are slow: it should drift, not churn.
private val Sheets = arrayOf(
    Sheet(3, 8.0, .19, 0.0, 5, 4.0, .12, 1.3, 2, .16, .4, 2, .25, 0.0, 2, 14.0, .14, .028),
    Sheet(4, 6.0, .15, 2.1, 2, 5.0, .17, .6, 3, .13, 1.9, 1, .21, 1.7, 3, 10.0, .19, -.022),
    Sheet(2, 7.0, .16, 4.0, 6, 3.0, .14, 2.8, 2, .18, 3.1, 2, .29, 3.3, 1, 16.0, .12, .017),
)
internal val SheetCount get() = Sheets.size

/**
 * One cross-section of one sheet at [u] round the ring: its centre in 3D and the direction its
 * width runs, as seven values. [spin] turns the whole ring; [swell] breathes it outward.
 */
internal fun sheetSection(sheet: Int, u: Double, time: Double, spin: Double, swell: Double, out: DoubleArray) {
    val s = Sheets[sheet]
    val theta = u + spin + s.drift * time
    val c = cos(theta); val n = sin(theta)
    val radius = RingMean + swell + s.a1 * sin(s.n1 * u + s.w1 * time + s.p1) + s.a2 * sin(s.n2 * u - s.w2 * time + s.p2)
    val twist = s.twist * u + s.w4 * time + s.p4
    // Centre, then the direction across the sheet: part radial in the ring's plane, part along its axis.
    out[0] = radius * c; out[1] = radius * n; out[2] = s.a3 * sin(s.n3 * u + s.w5 * time)
    out[3] = cos(twist) * c; out[4] = cos(twist) * n; out[5] = sin(twist)
    out[6] = SheetHalfWidth * (.6 + .4 * sin(s.m * u - s.w3 * time + s.p3))
}

/**
 * A grain [v] of the way across a section, -1..1: where it lands in the design space, how near the
 * viewer it is (-1 far .. 1 near), and — for checks — its distance from the centre in the ring's plane.
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
    out[2] = (leanZ / (RingMean + SheetHalfWidth)).coerceIn(-1.0, 1.0)
    out[3] = sqrt(x * x + y * y)
}

/** Deep violet in the ring's far reaches, the app's lavender near; additive overlap does the whites. */
internal fun ringColour(near: Float, strength: Float): Int {
    val t = near.coerceIn(0f, 1f)
    fun mix(a: Int, b: Int) = (a + (b - a) * t).roundToInt()
    val alpha = (strength.coerceIn(0f, 1f) * 255).roundToInt()
    return (alpha shl 24) or (mix(0x6A, 0xC4) shl 16) or (mix(0x55, 0xAE) shl 8) or mix(0xC8, 0xF6)
}

