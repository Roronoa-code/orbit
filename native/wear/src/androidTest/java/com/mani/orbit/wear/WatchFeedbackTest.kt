package com.mani.orbit.wear

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WatchFeedbackTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun feedbackIsTransientAndQuietInAmbientOrBackground() {
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
        val events = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
        val actual = mutableListOf<HapticFeedbackType>()
        val haptic = object : HapticFeedback { override fun performHapticFeedback(type: HapticFeedbackType) { actual.add(type) } }
        val owner = object : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
        val ambient = mutableStateOf(false)
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }
        compose.setContent {
            CompositionLocalProvider(LocalHapticFeedback provides haptic, LocalLifecycleOwner provides owner) {
                BasicText("Workout feedback")
                WatchWorkoutFeedback(ambient.value, events)
            }
        }
        fun subscribed(count: Int) = compose.waitUntil(5000) { events.subscriptionCount.value == count }
        fun emit(success: Boolean) { compose.runOnIdle { assertTrue(events.tryEmit(success)) }; compose.waitForIdle() }
        subscribed(1); emit(true); emit(false)
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.Confirm, HapticFeedbackType.Reject), actual) }
        compose.runOnIdle { ambient.value = true }; subscribed(0)
        emit(true); emit(false)
        compose.runOnIdle { ambient.value = false }; subscribed(1)
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.CREATED }; subscribed(0)
        emit(true)
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.RESUMED }; subscribed(1)
        compose.runOnIdle { assertEquals("No old confirmation on wake or return", 2, actual.size) }
        emit(false)
        compose.runOnIdle { assertEquals(listOf(HapticFeedbackType.Confirm, HapticFeedbackType.Reject, HapticFeedbackType.Reject), actual) }
        compose.runOnUiThread { owner.lifecycle.currentState = Lifecycle.State.DESTROYED }; subscribed(0)
    }
}
