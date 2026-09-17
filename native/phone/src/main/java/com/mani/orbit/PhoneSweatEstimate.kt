package com.mani.orbit

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.mani.orbit.sync.*
import kotlinx.coroutines.*

@Composable internal fun PhoneSweatEstimate(installation: String, workout: WatchWorkout) {
    if (workout.kind != "Running") return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(workout.id) { mutableStateOf(false) }
    val estimate by produceState<SweatEstimate?>(null, installation, workout.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (isActive) {
                try {
                    value = withContext(Dispatchers.IO) { ReadingJournal(context.getDatabasePath("watch-readings.db")).use {
                        SweatJournal(it).forWorkout(installation, workout)
                    } }; error = false
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                delay(3000)
            }
        }
    }
    if (error) Text("The Watch sweat estimate could not be loaded. Retrying…", color = WorkoutMuted, fontSize = 12.sp)
    else estimate?.let { SweatEstimateSummary(it) }
}

@Composable internal fun SweatEstimateSummary(value: SweatEstimate) {
    Column(Modifier.fillMaxWidth().testTag("sweat-estimate"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Sweat loss", color = WorkoutWhite, fontSize = 18.sp)
        value.millilitres?.let { Text("${workoutNumber(it)} ml", color = WorkoutPurple, fontSize = 30.sp) }
        Text(value.description(), color = WorkoutMuted, fontSize = 13.sp, lineHeight = 19.sp)
        if (value.millilitres != null) Text("Uses the profile saved when this run started.", color = WorkoutMuted, fontSize = 12.sp)
    }
}
