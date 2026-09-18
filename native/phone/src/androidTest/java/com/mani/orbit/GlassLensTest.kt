package com.mani.orbit

import android.graphics.Bitmap
import android.graphics.Paint
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class GlassLensTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(android.os.Build.VERSION.SDK_INT >= 33)
    }
    private fun save(name: String, bitmap: Bitmap) = File(rule.activity.cacheDir, name).outputStream().use {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
    }
    private fun difference(a: Int, b: Int): Int = maxOf(abs((a shr 16 and 255) - (b shr 16 and 255)),
        abs((a shr 8 and 255) - (b shr 8 and 255)), abs((a and 255) - (b and 255)))
    private fun luminance(pixel: Int): Int = (pixel shr 16 and 255) + (pixel shr 8 and 255) + (pixel and 255)

    /** Clearly brighter than the tint and its sheen together, so a bright line under the glass. */
    private val BRIGHT_UNDER_GLASS = 150

    @Test fun realBackdropBendsOnlyAtTheEdgeAndForegroundRemainsSharp() {
        val pressure = mutableFloatStateOf(0f)
        val finger = mutableStateOf(Offset(.1f, .5f))
        val scroll = mutableFloatStateOf(0f)
        rule.setContent {
            val backdrop = rememberGlassBackdrop()
            val density = LocalDensity.current.density
            val paint = remember(density) { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE; textSize = 18 * density } }
            Box(Modifier.width(350.dp).height(300.dp).background(Color(0xFF0B0A0F)).testTag("optical-fixture")) {
                Canvas(Modifier.fillMaxSize().recordBackdrop(backdrop)) {
                    drawRect(Color(0xFF17102C))
                    val pitch = 37.dp.toPx()
                    for (i in -1..24) {
                        drawLine(Color(0xFFAF92E5), Offset(i * pitch + scroll.floatValue, 0f),
                            Offset(i * pitch + scroll.floatValue, size.height), 4.dp.toPx())
                        drawLine(Color(0xFFF0BA87), Offset(0f, i * pitch + scroll.floatValue),
                            Offset(size.width, i * pitch + scroll.floatValue), 4.dp.toPx())
                    }
                    drawIntoCanvas { canvas ->
                        for (i in 0..5) canvas.nativeCanvas.drawText("BACKGROUND 012345", 8.dp.toPx(),
                            (i * 52 + 20).dp.toPx() + scroll.floatValue, paint)
                    }
                }
                Box(Modifier.align(Alignment.Center).size(276.dp, 108.dp).testTag("lens")
                    .clip(RoundedCornerShape(24.dp)).orbitFrost(backdrop, 24.dp, { pressure.floatValue }, { finger.value }),
                    contentAlignment = Alignment.Center) { Text("Keep sharp", color = Color.White, fontSize = 16.sp) }
            }
        }
        rule.waitForIdle()
        assertTrue("Refraction needs a runtime shader on this device", LensSupported)
        val root = rule.onNodeWithTag("optical-fixture")
        val origin = root.fetchSemanticsNode().boundsInRoot.topLeft
        val bounds = rule.onNodeWithTag("lens").fetchSemanticsNode().boundsInRoot.translate(-origin)
        val textBounds = rule.onNodeWithText("Keep sharp").fetchSemanticsNode().boundsInRoot
        val idle = root.captureToImage().asAndroidBitmap()
        rule.runOnIdle { pressure.floatValue = 1f }
        val held = root.captureToImage().asAndroidBitmap()
        val density = rule.activity.resources.displayMetrics.density
        // The lifted lens refracts 40dp deep, so "interior" has to clear that on both captures;
        // 26dp put the sample inside the lifted refraction, where the depth term does the talking.
        val inset = 46 * density
        // A lifted surface casts a deeper shadow, so the band just outside it may darken. Beyond
        // the shadow's own reach nothing may move: there is still no page-wide warp.
        val shadow = bounds.inflate(40 * density)
        var changedEdges = 0
        var idleInterior = 0L
        var heldInterior = 0L
        var interiorPixels = 0
        for (y in 0 until idle.height) for (x in 0 until idle.width) {
            val delta = difference(idle.getPixel(x, y), held.getPixel(x, y))
            val point = Offset(x + .5f, y + .5f)
            if (!shadow.contains(point)) assertTrue("No screen-wide warp at $x,$y", delta <= 1)
            else if (!bounds.contains(point)) continue
            else if (x > bounds.left + inset && x < bounds.right - inset && y > bounds.top + inset && y < bounds.bottom - inset) {
                // Only where the page is clearly brighter than the tint does a thinner tint have one
                // direction to move. Over dark page the sheen and the tint pull opposite ways, so a
                // sum across the whole interior barely moves and its sign is noise.
                if (luminance(idle.getPixel(x, y)) > BRIGHT_UNDER_GLASS) {
                    idleInterior += luminance(idle.getPixel(x, y)); heldInterior += luminance(held.getPixel(x, y))
                    interiorPixels++
                }
            } else if (delta > 2) changedEdges++
        }
        save("lens-idle.png", idle); save("lens-held.png", held)
        assertTrue("The actual source must refract rather than merely blur: $changedEdges", changedEdges > 24)
        // Lifting is a thicker piece of glass with a thinner tint, so a bright page shows brighter.
        assertTrue("The fixture must put bright page under the glass: $interiorPixels", interiorPixels > 50)
        assertTrue("A lifted surface must thin its tint: $idleInterior vs $heldInterior over $interiorPixels bright pixels",
            heldInterior > idleInterior)
        assertEquals(textBounds, rule.onNodeWithText("Keep sharp").fetchSemanticsNode().boundsInRoot)
        rule.runOnIdle { finger.value = Offset(.9f, .5f) }
        val moved = root.captureToImage().asAndroidBitmap()
        var following = 0
        for (y in bounds.top.roundToInt() until bounds.bottom.roundToInt())
            for (x in bounds.left.roundToInt() until bounds.right.roundToInt())
                if (difference(held.getPixel(x, y), moved.getPixel(x, y)) > 2) following++
        assertTrue("Refraction follows the contact location", following > 24)
        rule.runOnIdle { pressure.floatValue = 0f }
        val released = root.captureToImage().asAndroidBitmap()
        var releasedDifference = 0
        for (y in 0 until idle.height) for (x in 0 until idle.width)
            releasedDifference = maxOf(releasedDifference, difference(idle.getPixel(x, y), released.getPixel(x, y)))
        assertTrue("Release returns to the resting material without residue", releasedDifference <= 1)
        save("lens-moved.png", moved)
        val frames = org.json.JSONArray()
        var firstScroll: Bitmap? = null
        var refreshedPixels = 0
        for (frame in 0..11) {
            rule.runOnIdle { scroll.floatValue = frame * 3f; pressure.floatValue = .75f; finger.value = Offset(frame / 11f, .5f) }
            val bitmap = root.captureToImage().asAndroidBitmap()
            assertEquals(textBounds, rule.onNodeWithText("Keep sharp").fetchSemanticsNode().boundsInRoot)
            if (frame == 0) firstScroll = bitmap
            if (frame == 11) {
                for (y in (bounds.top + inset).roundToInt() until (bounds.bottom - inset).roundToInt())
                    for (x in (bounds.left + inset).roundToInt() until (bounds.right - inset).roundToInt())
                        if (difference(firstScroll!!.getPixel(x, y), bitmap.getPixel(x, y)) > 2) refreshedPixels++
                val text = textBounds.translate(-origin)
                for (y in text.top.roundToInt() until text.bottom.roundToInt()) for (x in text.left.roundToInt() until text.right.roundToInt()) {
                    val original = idle.getPixel(x, y)
                    if ((original shr 16 and 255) >= 250 && (original shr 8 and 255) >= 250 && (original and 255) >= 250)
                        assertTrue("Foreground glyph cores remain sharp while the source moves", difference(original, bitmap.getPixel(x, y)) <= 1)
                }
            }
            frames.put(JSONObject().put("frame", frame).put("scrollPx", frame * 3).put("finger", frame / 11f))
            if (frame in listOf(0, 6, 11)) save("lens-scroll-$frame.png", bitmap)
        }
        assertTrue("The retained material refreshes from the moving real source", refreshedPixels > 100)
        File(rule.activity.cacheDir, "lens-evidence.json").writeText(JSONObject().put("refreshedPixels", refreshedPixels).put("changedEdgePixels", changedEdges)
            .put("followingPixels", following).put("releaseMaxChannelDifference", releasedDifference).put("frames", frames).toString())
    }

    @Test fun densityInsetsRotationAndScaleSampleTheActualRegion() {
        val visible = mutableStateOf(false)
        val offset = mutableFloatStateOf(0f)
        val translation = mutableFloatStateOf(0f)
        val rotation = mutableFloatStateOf(0f)
        val scale = mutableFloatStateOf(1f)
        val sourceRotation = mutableFloatStateOf(0f)
        val densityScale = mutableFloatStateOf(1f)
        rule.setContent {
            val device = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(device.density * densityScale.floatValue, 1f)) {
                val backdrop = rememberGlassBackdrop()
                Box(Modifier.width(320.dp).height(280.dp).testTag("mapping-fixture").background(Color.Black).padding(13.dp, 21.dp)) {
                    Canvas(Modifier.fillMaxSize().graphicsLayer { rotationZ = sourceRotation.floatValue; scaleX = .94f }
                        .recordBackdrop(backdrop)) {
                        drawRect(Color(0xFFDD3030))
                        drawRect(Color(0xFF3030DD), Offset(size.width / 2f, 0f), androidx.compose.ui.geometry.Size(size.width / 2f, size.height))
                    }
                    Box(Modifier.padding(top = 78.dp).offset { androidx.compose.ui.unit.IntOffset(offset.floatValue.dp.roundToPx(), 0) }.size(104.dp, 76.dp)
                        .graphicsLayer { translationX = translation.floatValue.dp.toPx(); rotationZ = rotation.floatValue; scaleX = scale.floatValue; scaleY = scale.floatValue; alpha = if (visible.value) 1f else 0f }
                        .testTag("mapped-lens").clip(RoundedCornerShape(24.dp))
                        .orbitFrost(backdrop, 24.dp))
                }
            }
        }
        val root = rule.onNodeWithTag("mapping-fixture")
        val samples = org.json.JSONArray()
        for (dpi in listOf(1f, 1.15f)) for ((x, angle) in listOf(12f to -21f, 150f to 19f)) {
            rule.runOnIdle {
                visible.value = false; translation.floatValue = 0f; densityScale.floatValue = dpi; offset.floatValue = x
                rotation.floatValue = angle; scale.floatValue = .85f; sourceRotation.floatValue = -angle * .5f
            }
            val center = rule.onNodeWithTag("mapped-lens").fetchSemanticsNode().boundsInRoot.center - root.fetchSemanticsNode().boundsInRoot.topLeft
            val rawScene = root.captureToImage().asAndroidBitmap()
            val raw = rawScene.getPixel(center.x.roundToInt(), center.y.roundToInt())
            rule.runOnIdle { visible.value = true }
            val image = root.captureToImage().asAndroidBitmap()
            val sampled = image.getPixel(center.x.roundToInt(), center.y.roundToInt())
            val expected = (raw shr 16 and 255) - (raw and 255)
            val actual = (sampled shr 16 and 255) - (sampled and 255)
            assertTrue("Fixture must select an unambiguous colored region", abs(expected) > 100)
            save("lens-mapping-$dpi-$x.png", image)
            assertTrue("Lens samples the visible region under independent transforms: dpi=$dpi x=$x raw=$raw sampled=$sampled expected=$expected actual=$actual center=$center", expected * actual > 0 && abs(actual) > 10)
            samples.put(JSONObject().put("densityScale", dpi).put("xDp", x).put("rotation", angle).put("raw", raw).put("sampled", sampled))
            // Move an already-visible layer without recomposition, relayout or changing the shader pose.
            rule.runOnIdle { translation.floatValue = if (x < 50f) 138f else -138f }
            val movedCenter = rule.onNodeWithTag("mapped-lens").fetchSemanticsNode().boundsInRoot.center - root.fetchSemanticsNode().boundsInRoot.topLeft
            val shifted = root.captureToImage().asAndroidBitmap()
            val movedRaw = rawScene.getPixel(movedCenter.x.roundToInt(), movedCenter.y.roundToInt())
            val movedSample = shifted.getPixel(movedCenter.x.roundToInt(), movedCenter.y.roundToInt())
            val movedExpected = (movedRaw shr 16 and 255) - (movedRaw and 255)
            val movedActual = (movedSample shr 16 and 255) - (movedSample and 255)
            save("lens-translated-$dpi-$x.png", shifted)
            assertTrue("A transform-only move refreshes the source region: expected=$movedExpected actual=$movedActual",
                abs(movedExpected) > 100 && movedExpected * movedActual > 0 && abs(movedActual) > 10)
            samples.put(JSONObject().put("densityScale", dpi).put("xDp", x).put("translationDp", translation.floatValue)
                .put("raw", movedRaw).put("sampled", movedSample))
        }
        File(rule.activity.cacheDir, "lens-mapping.json").writeText(samples.toString())
    }
}
