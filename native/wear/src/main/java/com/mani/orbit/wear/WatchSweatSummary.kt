package com.mani.orbit.wear

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import com.mani.orbit.sync.*
import kotlinx.coroutines.*

@Composable internal fun WatchSweatSummary(workout: WatchWorkout) {
    if (workout.kind != "Running") return
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var error by remember(workout.id) { mutableStateOf(false) }
    val estimate by produceState<SweatEstimate?>(null, workout.id) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                try {
                    value = withContext(Dispatchers.IO) { val store = WatchStore(context)
                        store.journal().use { SweatJournal(it).forWorkout(store.installation, workout) }
                    }; error = false
                    if (value?.terminal == true) break
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { error = true }
                delay(3000)
            }
        }
    }
    if (error) Text("Sweat estimate could not load", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    else estimate?.let { WatchSweatEstimate(it) }
}

@Composable internal fun WatchSweatEstimate(value: SweatEstimate) {
    val locale = LocalConfiguration.current.locales[0]
    Text("Sweat estimate", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    value.millilitres?.let { Text("${String.format(locale, "%.0f", it)} ml",
        style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center) }
    Text(value.description(), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
}
