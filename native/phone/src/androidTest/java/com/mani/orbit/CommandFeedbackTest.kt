package com.mani.orbit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mani.orbit.sync.WatchWorkout
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CommandFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun confirmedAndRejectedFeedbackIsNotReplayedOnReturn() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val events = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        val actual = mutableListOf<HapticFeedbackType>()
        val haptic = object : HapticFeedback { override fun performHapticFeedback(type: HapticFeedbackType) { actual.add(type) } }
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptic, LocalLifecycleOwner provides owner) {
                BasicText("Watch control feedback")
                WatchCommandFeedback(events)
                WatchCommandFeedback() // Actual missing-peer failure below uses the controller's stream.
            }
        }
        compose.waitUntil(5000) { events.subscriptionCount.value == 1 }
        fun emit(success: Boolean) { compose.runOnIdle { assertTrue(events.tryEmit(success)) }; compose.waitForIdle() }
        emit(true); emit(false)
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }
        compose.waitUntil(5000) { events.subscriptionCount.value == 0 }
        emit(true); emit(false)
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(5000) { events.subscriptionCount.value == 1 }
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.Confirm, HapticFeedbackType.Reject), actual) }
        fun id() = UUID.randomUUID().toString()
        val w = WatchWorkout(id(), 1, "Walking", id(), 10000, 1000, 12000, 3000, 2000, "active", false)
        compose.runOnIdle { WatchWorkoutControl.request(compose.activity, WatchWorkoutProjection.record(id(), w), "pause") }
        compose.waitUntil(5000) { actual.size == 3 }
        compose.runOnIdle { assertEquals(HapticFeedbackType.Reject, actual.last()) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }
    }
}
