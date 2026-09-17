package com.mani.orbit

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

internal val HealthAccent = Color(0xFFBEA4E7)
internal val HealthSecondary = Color(0xFFB9B2C5)
internal fun Double?.healthNumber(precision: Int = 0): String = this?.let { String.format(Locale.UK, "%,.${precision}f", it) } ?: "—"
internal fun healthTime(at: Long): String = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm"))
private val HealthDate = DateTimeFormatter.ofPattern("d MMM", Locale.UK)

@Composable
internal fun HealthCardContent(id: HealthCard, wide: Boolean, sizing: Boolean, moving: Boolean,
    oxygenOpen: Boolean, state: HealthScreenState, goal: Int?, reduced: Boolean,
    modifier: Modifier, resize: () -> Unit, select: () -> Unit, open: () -> Unit, earlier: () -> Boolean, later: () -> Boolean,
    headerBounds: (Rect) -> Unit) {
    val contact = remember { MutableInteractionSource() }
    val pressed by contact.collectIsPressedAsState()
    val held = pressed && !moving
    val response = animateFloatAsState(if (held) 1f else 0f,
        tween(if (reduced) 0 else if (held) 100 else 320, easing = CubicBezierEasing(.2f, .8f, .2f, 1f)), label = "card contact")
    val day = state.day
    val value = when (id) {
        HealthCard.Sleep -> day.asleepMinutes?.roundToInt()?.let { "${it / 60} h ${it % 60} m" } ?: "—"
        HealthCard.Steps -> day.steps.healthNumber()
        HealthCard.Heart -> day.heart.healthNumber()
        HealthCard.Body -> day.weight.healthNumber(1)
        HealthCard.Intake -> day.nutrition.healthNumber()
        HealthCard.Oxygen -> day.oxygen.healthNumber()
    }
    val unit = when (id) { HealthCard.Heart -> "bpm"; HealthCard.Body -> "kg"; HealthCard.Intake -> "kcal"; HealthCard.Oxygen -> "%"; else -> "" }
    val note = when (id) {
        HealthCard.Sleep -> if (day.nights.isEmpty()) "No sleep shared" else "${healthTime(day.nights.minOf { it.start })} – ${healthTime(day.nights.maxOf { it.end })}"
        HealthCard.Steps -> if (day.steps == null) "No steps shared" else goal?.let { "${(day.steps / it * 100).roundToInt()}% of your goal" } ?: "Across the day"
        HealthCard.Heart -> day.heartAt?.let { "Latest · ${healthTime(it)}" } ?: if (day.heart == null) "No heart rate shared" else "Latest recorded"
        HealthCard.Body -> day.weightDate?.let { "${it.format(HealthDate)} · latest" } ?: "No measurement shared"
        HealthCard.Intake -> if (day.meals.isEmpty()) "No meals shared" else "${day.meals.size} ${if (day.meals.size == 1) "meal" else "meals"} recorded"
        HealthCard.Oxygen -> if (day.oxygen == null) "No oxygen shared" else "Latest recorded"
    }
    val background = when (id) { HealthCard.Sleep -> Color(0xFF242031); HealthCard.Heart -> Color(0xFF211B28); else -> Color(0xFF1B1920) }
    val density = LocalDensity.current
    val action = Modifier.onPreviewKeyEvent { event -> when {
                    event.type != KeyEventType.KeyDown -> false
                    event.key == Key.F10 && event.isShiftPressed -> { select(); true }
                    event.isAltPressed && event.key in listOf(Key.DirectionLeft, Key.DirectionUp) -> earlier()
                    event.isAltPressed && event.key in listOf(Key.DirectionRight, Key.DirectionDown) -> later()
                    else -> false
                } }.clickable(interactionSource = contact, indication = null, onClickLabel =
                if (id == HealthCard.Oxygen) "${if (oxygenOpen) "Hide" else "Show"} oxygen week" else "Open ${id.label}", onClick = open)
                .semantics {
                    customActions = listOf(CustomAccessibilityAction("${if (wide) "Compact" else "Expand"} ${id.label}") { resize(); true },
                        CustomAccessibilityAction("Move earlier", earlier), CustomAccessibilityAction("Move later", later),
                        CustomAccessibilityAction("Show size control") { select(); true })
                }
    Box(modifier.then(if (id != HealthCard.Oxygen) action else Modifier).graphicsLayer { scaleX = 1f - response.value * .035f; scaleY = scaleX }
        .clip(RoundedCornerShape(26.dp)).background(background).testTag("health-card-${id.key}")) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().onGloballyPositioned { headerBounds(Rect(it.positionInRoot(), it.size.toSize())) }
                .then(if (id == HealthCard.Oxygen) action else Modifier).padding(if (wide) 20.dp else 18.dp).heightIn(min = if (wide) 116.dp else 172.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(id.label, fontSize = 14.sp, lineHeight = 19.sp, fontWeight = FontWeight.Medium,
                            color = Color(0xFFE9E2F3), modifier = Modifier.padding(end = if (sizing && !wide) 25.dp else 0.dp))
                        if (!wide) Box(Modifier.fillMaxWidth().height(80.dp), contentAlignment = Alignment.CenterEnd) {
                            CardArt(id, day, goal, response, Modifier.size(68.dp))
                        } else Spacer(Modifier.height(20.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.Bottom) {
                            val styledValue = buildAnnotatedString {
                                if (id == HealthCard.Sleep && day.asleepMinutes != null) {
                                    val minutes = day.asleepMinutes.roundToInt()
                                    append("${minutes / 60}")
                                    withStyle(SpanStyle(fontSize = (if (wide) 17 else 12).sp, color = HealthSecondary)) { append(" h ") }
                                    append("${minutes % 60}")
                                    withStyle(SpanStyle(fontSize = (if (wide) 17 else 12).sp, color = HealthSecondary)) { append(" m") }
                                } else append(value)
                            }
                            Text(styledValue, color = Color(0xFFF7F2FC), fontSize = (if (wide) 40 else if (id == HealthCard.Sleep) 31 else 33).sp,
                                lineHeight = (if (wide) 40 else 33).sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Medium)
                            if (unit.isNotEmpty()) Text(unit, fontSize = 12.sp, color = HealthSecondary, modifier = Modifier.padding(bottom = 5.dp))
                        }
                        Text(note, fontSize = 11.sp, lineHeight = 16.sp, color = HealthSecondary, modifier = Modifier.padding(top = 8.dp,
                            end = if (id == HealthCard.Oxygen) 18.dp else 0.dp))
                    }
                    if (wide) CardArt(id, day, goal, response, Modifier.padding(start = 8.dp).size(if (density.fontScale > 1.25f) 60.dp else 76.dp))
                }
                if (wide) {
                    Spacer(Modifier.height(20.dp))
                    if (id == HealthCard.Sleep) HealthSleepRibbons(day.nights)
                    else HealthCardFacts(id, state, goal)
                }
            }
            if (oxygenOpen && id == HealthCard.Oxygen) HealthOxygenChart(state)
        }
        if (id == HealthCard.Oxygen && !oxygenOpen) Text("+", color = HealthAccent, fontSize = 18.sp,
            modifier = Modifier.align(Alignment.BottomEnd).padding(18.dp).clearAndSetSemantics {})
        if (sizing && !moving) Box(Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 5.dp).size(44.dp)
            .testTag("health-resize-${id.key}").clickable(indication = null, interactionSource = remember { MutableInteractionSource() }, onClick = resize)
            .semantics { contentDescription = "${if (wide) "Compact" else "Expand"} ${id.label}" }, contentAlignment = Alignment.Center) {
            HealthResizeIcon(wide, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CardArt(id: HealthCard, day: HealthDay, goal: Int?, response: State<Float>, modifier: Modifier) {
    HealthCardArt(id, day.steps?.let { steps -> goal?.let { (steps / it).toFloat().coerceIn(0f, 1f) } }, modifier.graphicsLayer {
        rotationZ = response.value * when (id) { HealthCard.Sleep, HealthCard.Steps -> -14f; HealthCard.Heart -> -7f; HealthCard.Body -> 10f; HealthCard.Intake -> -12f; HealthCard.Oxygen -> 9f }
        scaleX = 1f - if (id == HealthCard.Heart) response.value * .14f else 0f; scaleY = scaleX
        translationY = response.value * when (id) { HealthCard.Body -> 2.dp.toPx(); HealthCard.Oxygen -> -5.dp.toPx(); else -> 0f }
    })
}

@Composable
private fun HealthCardFacts(id: HealthCard, state: HealthScreenState, goal: Int?) {
    val day = state.day
    fun mealSum(value: (HealthMeal) -> Double?) = day.meals.mapNotNull(value).takeIf { it.isNotEmpty() }?.sum()
    val week = (6 downTo 0).mapNotNull { n -> val date = day.date.minusDays(n.toLong()); if (n == 0) day.oxygen else state.days[date]?.oxygen }
    val facts = when (id) {
        HealthCard.Steps -> listOf("To your goal" to (day.steps?.let { n -> goal?.let { maxOf(0.0, it - n) } }.healthNumber()), "Distance" to "${day.distance?.div(1000).healthNumber(2)} km")
        HealthCard.Heart -> listOf("Lowest" to "${day.heartLow.healthNumber()} bpm", "Highest" to "${day.heartHigh.healthNumber()} bpm")
        HealthCard.Body -> listOf("Body fat" to "${day.measurements.fatPercent.lastOrNull { it.day() == day.weightDate }?.value.healthNumber(1)}%",
            "Lean mass" to "${day.measurements.lean.lastOrNull { it.day() == day.weightDate }?.value.healthNumber(1)} kg")
        HealthCard.Intake -> listOf("Protein" to "${mealSum { it.protein }.healthNumber()} g", "Carbs" to "${mealSum { it.carbs }.healthNumber()} g", "Water" to "${day.water.healthNumber()} ml")
        HealthCard.Oxygen -> listOf("Past week" to if (week.isEmpty()) "—" else "${week.min().healthNumber()}–${week.max().healthNumber()}%", "Days recorded" to "${week.size} of 7")
        else -> emptyList()
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        facts.forEach { (label, value) -> Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.sp, lineHeight = 16.sp, color = HealthSecondary)
            Text(value, fontSize = 15.sp, lineHeight = 21.sp, color = Color(0xFFE7DDF3), modifier = Modifier.padding(top = 4.dp))
        } }
    }
}
