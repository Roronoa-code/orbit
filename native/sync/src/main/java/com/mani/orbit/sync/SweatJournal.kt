package com.mani.orbit.sync

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/** A workout attachment in the existing durable journal. Basic workout transfer stays independent. */
class SweatJournal(private val journal: ReadingJournal) {
    private val db get() = journal.db
    companion object {
        internal fun createTables(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS workout_sweat(installation TEXT NOT NULL,workout TEXT NOT NULL,revision INTEGER NOT NULL,hash TEXT NOT NULL,payload BLOB NOT NULL,PRIMARY KEY(installation,workout,revision))")
        }
    }
    fun capture(value: SweatEstimate) {
        val bytes = SweatWire.encode(value)
        db.beginTransaction()
        try {
            receive(bytes)
            journal.enqueueBytes(value.id, bytes, SweatWire.PATH)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun receive(bytes: ByteArray): ReadingReceipt {
        val r = SweatWire.decode(bytes); val hash = ReadingWire.digest(bytes)
        db.beginTransaction()
        try {
            var duplicate = false
            db.rawQuery("SELECT revision,hash,payload FROM workout_sweat WHERE installation=? AND workout=?", arrayOf(r.installation, r.workout)).use { c ->
                while (c.moveToNext()) {
                    val prior = SweatWire.decode(c.getBlob(2))
                    require(prior.boot == r.boot && prior.startElapsed == r.startElapsed && prior.profile == r.profile) { "Estimate owner changed" }
                    if (prior.revision == r.revision) { require(c.getString(1) == hash) { "Estimate revision changed" }; duplicate = true }
                    else {
                        val (older, newer) = if (prior.revision < r.revision) prior to r else r to prior
                        require(!older.terminal && newer.elapsed >= older.elapsed) { "Estimate cannot restart or go backwards" }
                        require(older.phase != "pending" || newer.phase != "tracking")
                    }
                }
            }
            journal.workout(r.installation, r.workout)?.let { require(matches(r, it)) }
            if (!duplicate) db.insertOrThrow("workout_sweat", null, ContentValues().apply {
                put("installation", r.installation); put("workout", r.workout); put("revision", r.revision); put("hash", hash); put("payload", bytes)
            })
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ReadingReceipt(r.id, hash)
    }
    fun latest(installation: String, workout: String): SweatEstimate? = db.rawQuery(
        "SELECT payload FROM workout_sweat WHERE installation=? AND workout=? ORDER BY revision DESC LIMIT 1", arrayOf(installation, workout)
    ).use { if (it.moveToFirst()) SweatWire.decode(it.getBlob(0)) else null }

    fun forWorkout(installation: String, workout: WatchWorkout): SweatEstimate? = latest(installation, workout.id)?.also { require(matches(it, workout)) }

    /** Caller must hold the process sensor lease: no surviving Samsung collector may own these records. */
    fun recoverInterrupted(installation: String) {
        uuid(installation)
        db.beginTransaction()
        try {
            val pending = db.rawQuery("SELECT s.payload FROM workout_sweat s WHERE s.installation=? AND s.revision=(SELECT MAX(t.revision) FROM workout_sweat t WHERE t.installation=s.installation AND t.workout=s.workout)", arrayOf(installation)).use { c ->
                buildList { while (c.moveToNext()) { val r = SweatWire.decode(c.getBlob(0)); if (!r.terminal) add(r) } }
            }
            for (r in pending) capture(r.copy(revision = Math.addExact(r.revision, 1), phase = "unavailable", reason = "INTERRUPTED"))
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    private fun matches(r: SweatEstimate, w: WatchWorkout) = w.kind == "Running" && w.id == r.workout && w.boot == r.boot && w.startElapsed == r.startElapsed
}
