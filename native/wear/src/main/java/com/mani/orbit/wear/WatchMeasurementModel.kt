package com.mani.orbit.wear

import android.app.Application
import androidx.compose.runtime.*
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mani.health.core.model.measurement.MeasurementTracker
import com.mani.orbit.sync.*
import kotlinx.coroutines.*
import java.time.LocalDate

/** Native retained state: completed results outlive Activity recreation and retryable storage failures. */
internal class WatchMeasurementModel(application: Application) : AndroidViewModel(application) {
    var tracker by mutableStateOf<MeasurementTracker?>(null)
    var profile by mutableStateOf<MeasurementProfile?>(null); private set
    var latest by mutableStateOf<WatchMeasurement?>(null); private set
    var latestOrderUncertain by mutableStateOf(false); private set
    var result by mutableStateOf<WatchMeasurement?>(null)
    var resultOrderUncertain by mutableStateOf(false); private set
    var pending by mutableStateOf<WatchMeasurement?>(null); private set
    var running by mutableStateOf(false); private set
    var saving by mutableStateOf(false); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null)
    var historyError by mutableStateOf<String?>(null); private set
    var profileError by mutableStateOf<String?>(null); private set
    var guidance by mutableStateOf("Connecting…"); private set
    var confirmed by mutableStateOf(false)
    var details by mutableStateOf(false)
    var permissionRevision by mutableIntStateOf(0)
    var savedFeedback by mutableStateOf(false)
    var syncWarning by mutableStateOf<String?>(null); private set
    private var operation: Job? = null
    private var refreshing: Job? = null

    fun refresh(request: Boolean = false) {
        refreshing?.cancel()
        refreshing = viewModelScope.launch {
            loading = true
            try {
                try {
                    val before = latest
                    val saved = withContext(Dispatchers.IO) { val store = WatchStore(getApplication())
                        store.journal().use { it.measurementPage(store.installation) } }
                    if (latest === before) { latest = saved?.result; latestOrderUncertain = saved?.orderingUncertain == true }
                    historyError = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { historyError = "Saved measurements could not refresh." }
                try {
                    if (request) withContext(Dispatchers.IO) { requestPhoneHealthContext(getApplication(), WireFamily.PROFILE) }
                    val updated = withContext(Dispatchers.IO) {
                        loadPhoneData(getApplication(), MeasurementProfileWire.PATH)?.let(MeasurementProfileWire::decode)
                    }
                    if (profile != updated) confirmed = false
                    profile = updated; profileError = null
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (failure: Exception) { profileError = (failure as? IncompatiblePeer)?.message ?: "Profile could not refresh. Check your phone connection." }
            } finally { if (currentCoroutineContext()[Job] == refreshing) loading = false }
        }
    }

    fun start() {
        val kind = tracker ?: return
        val input = profile
        if (running || saving || pending != null) return
        if (kind == MeasurementTracker.BIA && (!confirmed || input?.complete(LocalDate.now()) != true)) return
        error = null; result = null; syncWarning = null; guidance = "Connecting…"; running = true
        operation = viewModelScope.launch {
            try {
                samsungMeasurement(getApplication(), kind, input, onResult = ::acceptResult) { frame ->
                    guidance = when {
                        frame?.complete == true -> "Saving…"
                        frame?.errorCode == "CONTACT_NEEDED" -> "Adjust finger contact"
                        kind == MeasurementTracker.BIA -> "Keep touching both keys"
                        else -> "Keep still"
                    }
                }
            } catch (cancelled: CancellationException) { if (result == null) error = "Measurement stopped. Try again." }
            catch (failure: Exception) {
                if (result == null) error = if (pending != null) "Reading not saved. Free some space, then retry saving." else samsungMeasurementError(failure)
            } finally { running = false }
        }
    }

    fun stop() { if (running) operation?.cancel() }
    fun openLatest() {
        result = latest; resultOrderUncertain = latestOrderUncertain; details = false; syncWarning = null
    }
    suspend fun acceptResult(value: WatchMeasurement) = withContext(NonCancellable) {
        check(pending == null || pending?.batch?.id == value.batch.id) { "A previous result still needs saving" }
        pending = value
        savePending()
    }
    fun retrySave() {
        if (running || saving || pending == null) return
        // Set before dispatch, so two taps cannot launch concurrent writes of this result.
        saving = true
        operation = viewModelScope.launch {
            try { savePending() }
            catch (_: Exception) { error = "Still unable to save. Your result is kept here for another attempt." }
            finally { saving = false }
        }
    }

    private suspend fun savePending() = withContext(NonCancellable) {
        val value = pending ?: return@withContext
        saving = true
        try {
            withContext(Dispatchers.IO) { saveSamsungMeasurement(getApplication(), value) }
            latest = value; result = value; pending = null; details = false; confirmed = false; error = null; savedFeedback = true
            latestOrderUncertain = false; resultOrderUncertain = false
            syncWarning = withContext(Dispatchers.IO) { requestMeasurementSync(getApplication()) }
        } finally { saving = false }
    }
}
