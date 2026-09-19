package com.mani.orbit

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.LocalDate

data class Reading(val at: Long, val value: Double)
data class HealthDay(
    val date: LocalDate = LocalDate.now(),
    val steps: Double? = null,
    val distance: Double? = null,
    val floors: Double? = null,
    val energy: Double? = null,
    val heart: Double? = null,
    val weight: Double? = null,
    val weightDate: LocalDate? = null,
    val oxygen: Double? = null,
    val oxygenLow: Double? = null,
    val oxygenHigh: Double? = null,
    val nutrition: Double? = null,
    val asleepMinutes: Double? = null,
    val sleepScore: Double? = null,
    val energyScore: Double? = null,
    val energyScoreAt: Long? = null,
    val hourlySteps: List<Reading> = emptyList(),
    val heartReadings: List<Reading> = emptyList(),
    val measurements: MeasurementHistory = MeasurementHistory(),
    val heartCount: Long = 0,
    val heartSum: Double = 0.0,
    val heartLow: Double? = null,
    val heartHigh: Double? = null,
    val heartAt: Long? = null,
    /** Today's heart rate is the newest reading from Orbit's watch app rather than Samsung Health's. */
    val heartFromWatch: Boolean = false,
    /** Today's steps include steps Orbit's watch app has counted that Samsung Health has not reported yet. */
    val stepsFromWatch: Boolean = false,
    val hourlyHeart: List<Reading> = emptyList(),
    val nights: List<SleepNight> = emptyList(),
    val sleepStages: Map<String, Double?> = emptyMap(),
    val sleepIncomplete: Boolean = false,
    val meals: List<HealthMeal> = emptyList(),
    val water: Double? = null,
)
data class HealthScreenState(
    val day: HealthDay = HealthDay(),
    val loading: Boolean = true,
    val syncing: Boolean = false,
    val status: String = "Loading saved readings…",
    val error: String? = null,
    val lastSync: Long? = null,
    val liveStepsAt: Long? = null,
    val days: Map<LocalDate, HealthDay> = emptyMap(),
    val available: Boolean = false,
    val permitted: Boolean = false,
    val scanned: Long = 0,
    val liveStatus: String = "Connect for live phone and watch steps",
    val liveConnected: Boolean = false,
    val recordCount: Long? = null,
    val firstRecord: LocalDate? = null,
    val historyAllowed: Boolean = false,
    val workouts: List<WorkoutRecord> = emptyList(),
    /** What Orbit's watch app last said it can offer for live heart rate; see [LiveUpdate]. */
    val watchHeartState: String? = null,
)

class OrbitModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    val route = saved.getStateFlow("route", "Steps")
    val requestedWorkout = saved.getStateFlow<String?>("workoutRequest", null)
    val selectedDate = saved.getStateFlow("date", LocalDate.now().toString())
    private val mutableHealth = MutableStateFlow(HealthScreenState(day = HealthDay(LocalDate.parse(selectedDate.value))))
    val health = mutableHealth.asStateFlow()
    private val mutableProfile = MutableStateFlow<JSONObject?>(null)
    val profile = mutableProfile.asStateFlow()
    private val mutableProfileError = MutableStateFlow<String?>(null)
    val profileError = mutableProfileError.asStateFlow()
    private val mutableStepsGoal = MutableStateFlow<Int?>(null)
    val stepsGoal = mutableStepsGoal.asStateFlow()
    private val mutableReducedMotion = MutableStateFlow(false)
    val reducedMotion = mutableReducedMotion.asStateFlow()
    val globeRotation = saved.getStateFlow("globeRotation", true)
    private val mutableGoalError = MutableStateFlow<String?>(null)
    val goalError = mutableGoalError.asStateFlow()
    private val mutableMotionError = MutableStateFlow<String?>(null)
    val motionError = mutableMotionError.asStateFlow()
    private val mutableReadability = MutableStateFlow<MaterialReadability?>(null)
    internal val readability = mutableReadability.asStateFlow()
    private val mutableReadabilityError = MutableStateFlow<String?>(null)
    internal val readabilityError = mutableReadabilityError.asStateFlow()
    private val settingsWrite = Mutex()
    private val mutableCardLayout = MutableStateFlow<HealthCardLayout?>(null)
    internal val cardLayout = mutableCardLayout.asStateFlow()
    private val layoutWrite = Mutex()
    private val snapshots = Channel<Pair<Long, HealthUpdate>>(Channel.CONFLATED)
    @Volatile private var healthRevision = ""
    @Volatile private var sourceGeneration = 0L
    @Volatile private var acceptedGeneration = -1L
    private val preferences = AppPreferences(application)
    private var cached: NativeHealthSnapshot? = null
    private var lastSync: Long? = null
    private var recordCount: Long? = null
    private var firstRecord: LocalDate? = null
    private var historyAllowed = false

    init {
        reloadProfile()
        viewModelScope.launch(Dispatchers.IO) {
            val order = try { HealthCardLayout.order(preferences.read(HealthCardLayout.ORDER_KEY)) }
                catch (_: Exception) { report("Saved card order could not be read. The original setting is preserved."); HealthCard.entries.toList() }
            val wide = try { HealthCardLayout.sizes(preferences.read(HealthCardLayout.SIZE_KEY)) }
                catch (_: Exception) { report("Saved card sizes could not be read. The original setting is preserved."); HealthCardLayout().wide }
            mutableCardLayout.value = HealthCardLayout(order, wide)
        }
        viewModelScope.launch(Dispatchers.IO) {
            for ((generation, update) in snapshots) {
                if (generation != sourceGeneration) continue
                try {
                    val snapshot = update.info
                    require(!snapshot.has("error"))
                    update.readings?.let { cached = it }
                    snapshot.optJSONObject("meta")?.let {
                        lastSync = it.optLong("lastSync").takeIf { time -> time > 0 }
                        recordCount = it.optLong("recordCount", -1).takeIf { count -> count >= 0 }
                        firstRecord = it.optLong("firstRecord", -1).takeIf { at -> at > 0 }
                            ?.let { at -> java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate() }
                        historyAllowed = it.optBoolean("historyAllowed")
                    }
                    val date = LocalDate.parse(selectedDate.value)
                    val next = cached?.day?.takeIf { it.date == date }
                    var day = next ?: HealthDay(date)
                    // Today's hours travel on their own, so fetching them never re-sends the year.
                    if (snapshot.optString("stepHoursDate") == date.toString())
                        day = day.copy(hourlySteps = NativeHealthProjection.hours(snapshot.getJSONArray("stepHours")))
                    val live = snapshot.optJSONObject("live")?.optJSONObject("reading")
                    val liveAt = live?.optLong("at")?.takeIf { it > 0 }
                    val current = live != null && date == LocalDate.now() && live.optString("date") == date.toString()
                        && live.optString("source") == "com.sec.android.app.shealth"
                    if (current) {
                        val steps = live!!.getDouble("steps")
                        val hours = NativeHealthProjection.hours(live.optJSONArray("hours"))
                        require(steps.isFinite() && steps >= 0 && steps == kotlin.math.floor(steps)
                            && hours.all { it.value == kotlin.math.floor(it.value) } && hours.sumOf { it.value } == steps)
                        day = day.copy(steps = steps, hourlySteps = hours)
                    }
                    samsungDay = day
                    day = withWatch(day)
                    withContext(Dispatchers.Main.immediate) {
                        if (generation == sourceGeneration && selectedDate.value == date.toString()) {
                            mutableHealth.value = HealthScreenState(day, next == null, snapshot.optBoolean("syncing"),
                                snapshot.optString("status"), lastSync = lastSync,
                                error = if (snapshot.optBoolean("loadFailed")) "Readings could not be loaded. Your saved data is unchanged." else null,
                                liveStepsAt = if (current) liveAt else null,
                                days = cached?.days.orEmpty(), available = snapshot.optBoolean("available"), permitted = snapshot.optBoolean("permitted"),
                                scanned = snapshot.optLong("scanned").coerceAtLeast(0),
                                liveStatus = snapshot.optJSONObject("live")?.optString("status")?.takeIf { it.isNotBlank() } ?: "Connect for live phone and watch steps",
                                liveConnected = snapshot.optJSONObject("live")?.optBoolean("connected") == true,
                                recordCount = recordCount, firstRecord = firstRecord, historyAllowed = historyAllowed, workouts = cached?.workouts.orEmpty(),
                                watchHeartState = mutableHealth.value.watchHeartState)
                            healthRevision = update.revision
                            acceptedGeneration = generation
                        }
                    }
                } catch (error: Exception) { if (generation == sourceGeneration) report("Readings could not be loaded. Your saved data is unchanged.") }
            }
        }
    }

    fun attachHealthSource(): Long = ++sourceGeneration
    fun reloadProfile() {
        viewModelScope.launch(Dispatchers.IO) { settingsWrite.withLock {
            mutableProfileError.value = null
            try { mutableProfile.value = NativeProfile.read(preferences.read("orbit-profile-v1")) }
            catch (error: Exception) { mutableProfile.value = null; mutableProfileError.value = "Your saved profile could not be read. It has been preserved." }
            try {
                val raw = preferences.read("orbit-steps-goal-v1")
                mutableStepsGoal.value = if (raw == null) 10000 else requireNotNull(raw.toIntOrNull()).also {
                    require(it in 100..100000 && it % 100 == 0)
                }
                mutableGoalError.value = null
            } catch (_: Exception) { mutableStepsGoal.value = null; mutableGoalError.value = "Your saved step goal could not be read. It has been preserved." }
            try {
                val raw = preferences.read("orbit-reduce-motion-v1")
                require(raw == null || raw == "true" || raw == "false")
                mutableReducedMotion.value = raw == "true"
                mutableMotionError.value = null
                withContext(Dispatchers.Main.immediate) { if (raw == "true") saved["globeRotation"] = false }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableMotionError.value = "Your saved motion setting could not be read. It has been preserved." }
            try {
                mutableReadability.value = MaterialReadability.read(preferences.read(MaterialReadability.KEY))
                mutableReadabilityError.value = null
            } catch (_: Exception) {
                mutableReadability.value = null
                mutableReadabilityError.value = "Your saved readability settings could not be read. They have been preserved."
            }
        } }
    }
    fun revisionFor(generation: Long): String = if (generation == acceptedGeneration) healthRevision else ""
    internal fun acceptHealth(generation: Long, update: HealthUpdate) { if (generation == sourceGeneration) snapshots.trySend(generation to update) }

    /**
     * The newest valid heart reading Orbit's own watch app has delivered: bpm and when it was taken.
     *
     * Samsung Health reaches the phone on Samsung's own schedule, often most of an hour behind the
     * watch. Orbit's watch app delivers its readings as they are collected, so whichever of the two is
     * newer is the heart rate today shows.
     */
    @Volatile private var watchHeart: Pair<Double, Long>? = null
    private val stepLead = WatchStepLead()
    /** The selected day as Samsung Health last reported it, before anything from the watch is laid over it. */
    @Volatile private var samsungDay: HealthDay? = null

    /** Pick up the newest heart reading and step count Orbit's watch app has delivered through the journal. */
    fun refreshWatch(journal: java.io.File) {
        val (heart, steps) = try {
            com.mani.orbit.sync.ReadingJournal(journal).use { it.latest("heart") to it.latest("steps") }
        } catch (_: Exception) { return }
        val now = System.currentTimeMillis()
        heart?.let { latest ->
            val bpm = latest.optDouble("value", Double.NaN)
            val at = latest.optLong("end", 0)
            if (latest.optString("quality") == "valid" && !latest.optBoolean("timeUncertain") && bpm.isFinite() && bpm > 0 &&
                at > 0 && at <= now) offerWatchHeart(bpm, at)
        }
        steps?.let { latest ->
            val count = latest.optDouble("value", Double.NaN)
            val at = latest.optLong("end", 0)
            // A daily count starts at midnight and is flagged time-uncertain after any clock correction
            // since; its count and the moment it was taken are still sound.
            if (latest.optString("semantics") == "daily" && latest.optString("quality") == "valid" &&
                count.isFinite() && count >= 0 && at > 0 && at <= now) offerWatchSteps(count, at)
        }
    }

    /**
     * What Orbit's watch app answered while this screen holds its attention: what it can offer, and its
     * newest valid heart reading, pushed the moment the watch captures it.
     */
    fun acceptLive(update: com.mani.orbit.sync.LiveUpdate) {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            if (mutableHealth.value.watchHeartState != update.state) mutableHealth.value = mutableHealth.value.copy(watchHeartState = update.state)
        }
        // The watch keeps its own clock; a reading a little ahead of the phone's is still the latest.
        val latest = System.currentTimeMillis() + 60_000
        val steps = update.steps
        val stepsAt = update.stepsAt
        if (steps != null && stepsAt != null && stepsAt <= latest) offerWatchSteps(steps, stepsAt)
        val bpm = update.bpm ?: return
        val at = update.at ?: return
        if (at <= latest) offerWatchHeart(bpm, at)
    }

    private fun offerWatchSteps(steps: Double, at: Long) {
        val date = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        if (date != LocalDate.now()) return
        stepLead.watch(steps, at, date)
        publishWatch()
    }

    /** Lay the watch over the day Samsung Health last reported, never over a day it is already laid over. */
    private fun publishWatch() {
        viewModelScope.launch(Dispatchers.Main.immediate) {
            val current = mutableHealth.value
            val base = samsungDay?.takeIf { it.date == current.day.date } ?: current.day
            mutableHealth.value = current.copy(day = withWatch(base))
        }
    }

    private fun withWatch(day: HealthDay) = withWatchSteps(withWatchHeart(day))

    /**
     * Today's steps with what the watch has counted that Samsung Health has not reported yet (see
     * [WatchStepLead]), and never fewer than the watch's own count. The extra lands in the hour the watch
     * counted it, so the day's hours still add up to the day.
     */
    private fun withWatchSteps(day: HealthDay): HealthDay {
        if (day.date != LocalDate.now()) return day
        val samsung = day.steps ?: 0.0
        val (lead, watch, at) = stepLead.lead(samsung, day.date) ?: return day
        val total = maxOf(samsung + lead, watch)
        if (total <= samsung) return day
        val zone = java.time.ZoneId.systemDefault()
        val hour = java.time.Instant.ofEpochMilli(at).atZone(zone).truncatedTo(java.time.temporal.ChronoUnit.HOURS).toInstant().toEpochMilli()
        val hours = day.hourlySteps.associateByTo(LinkedHashMap()) { it.at }
        hours[hour] = Reading(hour, (hours[hour]?.value ?: 0.0) + total - samsung)
        return day.copy(steps = total, hourlySteps = hours.values.sortedBy { it.at }, stepsFromWatch = true)
    }

    /** Readings arrive by two paths, live and journal, in either order; the heart rate only moves forward. */
    private fun offerWatchHeart(bpm: Double, at: Long) {
        synchronized(this) {
            if (watchHeart?.let { at <= it.second } == true) return
            watchHeart = bpm to at
        }
        publishWatch()
    }

    private fun withWatchHeart(day: HealthDay): HealthDay {
        val (bpm, at) = watchHeart ?: return day
        if (day.date != LocalDate.now() || at <= (day.heartAt ?: 0L)) return day
        return day.copy(heart = bpm, heartAt = at, heartFromWatch = true)
    }
    fun openWorkout(id: String = "active") { saved["workoutRequest"] = id; navigate("Workouts") }
    fun workoutOpened(id: String?) { if (requestedWorkout.value == id) saved["workoutRequest"] = null }
    fun navigate(destination: String) {
        require(destination in setOf("Steps", "Health", "Measurements", "Heart rate", "Sleep", "Nutrition", "Blood oxygen", "Workouts", "Settings", "Galaxy Watch"))
        if (route.value == destination) return
        saved["backStack"] = ArrayList((saved.get<ArrayList<String>>("backStack") ?: arrayListOf()) + route.value)
        saved["route"] = destination
    }
    fun back(): Boolean {
        val stack = saved.get<ArrayList<String>>("backStack") ?: return false
        if (stack.isEmpty()) return false
        saved["route"] = stack.last()
        saved["backStack"] = ArrayList(stack.dropLast(1))
        return true
    }
    fun canGoBack(): Boolean = saved.get<ArrayList<String>>("backStack")?.isNotEmpty() == true
    internal suspend fun resizeCard(id: HealthCard): Boolean = withContext(Dispatchers.IO) { layoutWrite.withLock {
        val current = mutableCardLayout.value ?: return@withLock false
        val next = current.copy(wide = if (id in current.wide) current.wide - id else current.wide + id)
        if (!preferences.write(HealthCardLayout.SIZE_KEY, next.sizeJson())) {
            report("Card size could not be saved. Please try again."); return@withLock false
        }
        mutableCardLayout.value = next; true
    } }
    internal suspend fun moveCards(order: List<HealthCard>): Boolean = withContext(Dispatchers.IO) { layoutWrite.withLock {
        val current = mutableCardLayout.value ?: return@withLock false
        val next = current.copy(order = order)
        if (!preferences.write(HealthCardLayout.ORDER_KEY, next.orderJson())) {
            report("Card order could not be saved. Please try again."); return@withLock false
        }
        mutableCardLayout.value = next; true
    } }
    fun date(value: LocalDate) {
        require(value >= LocalDate.of(1970, 1, 1) && value <= LocalDate.now())
        saved["date"] = value.toString()
        mutableHealth.value = mutableHealth.value.copy(day = HealthDay(value), loading = true, liveStepsAt = null)
    }
    fun report(message: String) { mutableHealth.value = mutableHealth.value.copy(loading = false, error = message) }
    suspend fun saveProfile(name: String, birth: String, height: String, weight: String, sex: String? = mutableProfile.value?.optString("sex")?.takeIf { it in setOf("female", "male") }): String? = withContext(Dispatchers.IO) { settingsWrite.withLock {
        val existing = mutableProfile.value ?: return@withLock "Wait for your saved profile to load"
        if (sex != null && sex !in setOf("female", "male")) return@withLock "Choose a supported sex setting or leave it empty."
        val birthDate = try {
            if (birth.isBlank()) "" else if (birth.matches(Regex("[0-9]{8}")))
                LocalDate.of(birth.substring(4).toInt(), birth.substring(2, 4).toInt(), birth.substring(0, 2).toInt()).toString()
            else LocalDate.parse(birth).toString().also { require(it == birth) }
        } catch (_: Exception) { return@withLock "Enter a valid date of birth, up to today." }
        if (birthDate.isNotEmpty() && (birthDate < "1900-01-01" || birthDate > LocalDate.now().toString()))
            return@withLock "Enter a valid date of birth, up to today."
        val h = height.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        val w = weight.takeIf { it.isNotBlank() }?.toDoubleOrNull()
        if (name.length > 80 || height.isNotBlank() && (h == null || !h.isFinite() || h !in 40.0..260.0)
            || weight.isNotBlank() && (w == null || !w.isFinite() || w !in 20.0..350.0)) return@withLock "Check your name, height and weight"
        val next = JSONObject(existing.toString()).put("name", name.trim()).put("birthDate", birthDate)
            .put("heightCm", h ?: JSONObject.NULL).put("weightKg", w ?: JSONObject.NULL).put("sex", sex ?: JSONObject.NULL)
        try { NativeProfile.read(next.toString()) } catch (invalid: Exception) { return@withLock "Use up to one decimal place for height and weight" }
        if (!preferences.write("orbit-profile-v1", next.toString())) return@withLock "Could not save. Please try again."
        mutableProfile.value = next
        PhoneHealthContextWorker.schedule(getApplication(), urgent = true)
        null
    } }
    suspend fun saveGoal(raw: String): String? = withContext(Dispatchers.IO) { settingsWrite.withLock {
        val value = raw.toIntOrNull()
        if (value == null || value !in 100..100000 || value % 100 != 0) return@withLock "Choose 100–100,000 steps, in increments of 100."
        if (!preferences.write("orbit-steps-goal-v1", value.toString())) return@withLock "Could not save the goal. Please try again."
        mutableStepsGoal.value = value; mutableGoalError.value = null; null
    } }
    suspend fun saveReducedMotion(value: Boolean): String? = withContext(Dispatchers.IO) { settingsWrite.withLock {
        if (!preferences.write("orbit-reduce-motion-v1", value.toString())) return@withLock "Could not save the motion setting. Please try again."
        mutableReducedMotion.value = value; mutableMotionError.value = null
        withContext(Dispatchers.Main.immediate) { saved["globeRotation"] = !value }; null
    } }
    fun rotateGlobe(value: Boolean) { saved["globeRotation"] = value && !mutableReducedMotion.value }
    internal suspend fun saveReadability(reduceTransparency: Boolean? = null, increaseContrast: Boolean? = null): String? =
        withContext(Dispatchers.IO) { settingsWrite.withLock {
            try {
                // Read under the write lock; changing one preference preserves the other and unknown optional fields.
                val next = MaterialReadability.document(preferences.read(MaterialReadability.KEY))
                reduceTransparency?.let { next.put("reduceTransparency", it) }
                increaseContrast?.let { next.put("increaseContrast", it) }
                val accepted = MaterialReadability.read(next.toString())
                if (!preferences.write(MaterialReadability.KEY, next.toString()))
                    return@withLock "Could not save readability settings. Please try again."
                mutableReadability.value = accepted; mutableReadabilityError.value = null; null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { "Your saved readability settings could not be updated. The original has been preserved." }
        } }
}

internal object NativeProfile {
    fun read(raw: String?): JSONObject {
        if (raw == null) return JSONObject().put("name", "").put("birthDate", "")
            .put("heightCm", JSONObject.NULL).put("weightKg", JSONObject.NULL)
        require(raw.length <= 4096)
        val value = JSONObject(raw)
        require(value.get("name") is String && value.getString("name").length <= 80)
        require(value.get("birthDate") is String)
        if (!value.isNull("sex")) require(value.get("sex") in setOf("female", "male"))
        value.getString("birthDate").takeIf { it.isNotEmpty() }?.let {
            val day = LocalDate.parse(it)
            require(day.toString() == it && day >= LocalDate.of(1900, 1, 1) && day <= LocalDate.now())
        }
        for ((key, limits) in listOf("heightCm" to 40.0..260.0, "weightKg" to 20.0..350.0)) {
            if (value.isNull(key)) continue
            require(value.get(key) is Number)
            val n = value.getDouble(key)
            require(n.isFinite() && n in limits && kotlin.math.abs(n * 10 - kotlin.math.round(n * 10)) < 0.000001)
        }
        return value
    }
}
