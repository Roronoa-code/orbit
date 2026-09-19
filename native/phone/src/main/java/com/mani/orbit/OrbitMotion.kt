package com.mani.orbit

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

/*
 * One motion vocabulary for the whole app.
 *
 * Three things happen to a surface under a finger, and each has exactly one timing here. A route
 * that invents its own is what makes two screens of the same app feel like two apps, so a new
 * surface reaches for one of these rather than a number.
 *
 * Springs are normalised in Compose: the same stiffness takes the same time whether the surface
 * travels the width of the screen or four dp. That is the point — a small pill and a whole panel
 * arrive together.
 */

/** A surface returning to rest after a finger let go of it. Opening overshoots a little more. */
internal fun orbitSettle(opening: Boolean = false): AnimationSpec<Float> =
    spring(if (opening) .76f else .85f, 289f, .001f)

/** A surface answering contact: a lift, a selected slot, a contact wash. */
internal fun orbitEngage(reduced: Boolean = false): AnimationSpec<Float> =
    if (reduced) tween(0) else spring(.86f, 650f)

/**
 * The owner's US app's press: glass lifts toward the finger instead of sinking into the page, a
 * little larger and a little higher, over 90ms, and springs back almost without a wobble.
 */
internal const val OrbitPressScale = 1.035f
internal const val OrbitPressLiftDp = 1.5f
internal const val OrbitPressMillis = 90
internal val OrbitPressEasing = CubicBezierEasing(.4f, 0f, .2f, 1f)
internal fun orbitRelease(reduced: Boolean = false): AnimationSpec<Float> =
    if (reduced) tween(0) else spring(.945f, 914f)
