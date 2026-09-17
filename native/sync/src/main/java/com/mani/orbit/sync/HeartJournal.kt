package com.mani.orbit.sync

import android.database.sqlite.SQLiteDatabase

data class HeartPage(val frame: HeartFrame, val older: String?, val newer: String?)

/** Retains the established API and database tables while sharing callback assembly with raw sensors. */
class HeartJournal(journal: ReadingJournal) {
    private val frames = SensorFrameJournal(journal, WireFamily.HEART)
    fun capture(frame: HeartFrame) = frames.capture(frame)
    fun receive(bytes: ByteArray) = frames.receive(bytes)
    fun latestId(installation: String) = frames.latestId(installation)
    fun page(installation: String, selected: String? = null): HeartPage? = frames.page(installation, selected)?.let {
        HeartPage(it.frame as HeartFrame, it.older, it.newer)
    }
    companion object {
        internal fun createTables(db: SQLiteDatabase) = SensorFrameJournal.createTables(db, "heart")
    }
}
