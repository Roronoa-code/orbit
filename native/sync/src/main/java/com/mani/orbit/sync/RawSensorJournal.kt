package com.mani.orbit.sync

import android.database.sqlite.SQLiteDatabase

data class RawSensorPage(val frame: RawSensorFrame, val older: String?, val newer: String?)

class RawSensorJournal(journal: ReadingJournal) {
    private val frames = SensorFrameJournal(journal, WireFamily.RAW)
    fun capture(frame: RawSensorFrame) = frames.capture(frame)
    fun receive(bytes: ByteArray) = frames.receive(bytes)
    fun latestId(installation: String) = frames.latestId(installation)
    fun page(installation: String, selected: String? = null): RawSensorPage? = frames.page(installation, selected)?.let {
        RawSensorPage(it.frame as RawSensorFrame, it.older, it.newer)
    }
    companion object {
        internal fun createTables(db: SQLiteDatabase) = SensorFrameJournal.createTables(db, "raw")
    }
}
