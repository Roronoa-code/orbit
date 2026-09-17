package com.mani.orbit.sync

data class EcgPage(val record: EcgRecord, val olderId: String?, val newerId: String?, val orderingUncertain: Boolean)

private data class EcgHead(val id: String, val boot: String, val wall: Long, val elapsed: Long?, val count: Int?, val hasLegacy: Boolean = false)
private val ecgWallOrder = compareBy<EcgHead> { it.wall }.thenBy { it.id }

/** One indexed known/legacy candidate per boot. Raw encrypted chunks are never opened for history ordering. */
private fun EcgJournal.heads(installation: String): List<EcgHead> {
    uuid(installation)
    val candidates = db.rawQuery("""SELECT r.id,r.boot,r.start,r.sensor_elapsed,b.boot_count FROM reading_boots b
        JOIN ecg_records r ON r.installation=b.installation AND r.id IN (
            (SELECT id FROM ecg_records WHERE installation=b.installation AND boot=b.boot AND sensor_elapsed IS NOT NULL ORDER BY sensor_elapsed DESC,id DESC LIMIT 1),
            (SELECT id FROM ecg_records WHERE installation=b.installation AND boot=b.boot AND sensor_elapsed IS NULL ORDER BY start DESC,id DESC LIMIT 1))
        WHERE b.installation=?""", arrayOf(installation)).use { c -> buildList {
            while (c.moveToNext()) add(EcgHead(c.getString(0), c.getString(1), c.getLong(2),
                if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getInt(4)))
        } }
    val boots = candidates.groupBy { it.boot }.values.map { entries ->
        orderSensorEntries(entries.map { it to it.elapsed }, ecgWallOrder).first().copy(hasLegacy = entries.any { it.elapsed == null })
    }
    return orderSensorEntries(boots.map { it to it.count }, ecgWallOrder)
}

fun EcgJournal.latest(installation: String): EcgRecord? = heads(installation).firstOrNull()?.let { record(installation, it.id) }

private fun EcgJournal.legacyBootOrder(installation: String, boot: String): List<EcgHead> {
    // ponytail: only legacy/mixed boots merge scalar metadata in memory; persist an order index if a measured large legacy boot makes browsing slow.
    val entries = db.rawQuery("SELECT id,start,sensor_elapsed FROM ecg_records WHERE installation=? AND boot=?", arrayOf(installation, boot)).use { c ->
        buildList { while (c.moveToNext()) add(EcgHead(c.getString(0), boot, c.getLong(1), if (c.isNull(2)) null else c.getLong(2), null)) }
    }
    return orderSensorEntries(entries.map { it to it.elapsed }, ecgWallOrder)
}

/** Modern records use indexed range seeks. Legacy gaps stay explicit, without invented sensor timestamps. */
fun EcgJournal.page(installation: String, selectedId: String? = null): EcgPage? {
    uuid(installation); selectedId?.let(::uuid)
    db.beginTransactionNonExclusive()
    try {
        val boots = heads(installation)
        val head = boots.firstOrNull() ?: return null
        val selected = selectedId?.let { record(installation, it) } ?: record(installation, head.id) ?: return null
        val bootIndex = boots.indexOfFirst { it.boot == selected.boot }.also { check(it >= 0) }
        val current = boots[bootIndex]
        val legacy = if (current.hasLegacy) legacyBootOrder(installation, selected.boot) else null
        val legacyIndex = legacy?.indexOfFirst { it.id == selected.id }
        if (legacyIndex != null) check(legacyIndex >= 0)
        fun neighbor(older: Boolean): String? {
            val step = if (older) 1 else -1
            val op = if (older) "<" else ">"; val order = if (older) "DESC" else "ASC"
            val sameBoot = if (legacy != null) legacy.getOrNull(legacyIndex!! + step)?.id
            else db.rawQuery("""SELECT id FROM ecg_records WHERE installation=? AND boot=? AND
                (sensor_elapsed,id) $op (?,?) ORDER BY sensor_elapsed $order,id $order LIMIT 1""",
                arrayOf(installation, selected.boot, requireNotNull(selected.startedElapsedMs).toString(), selected.id)).use {
                if (it.moveToFirst()) it.getString(0) else null
            }
            if (sameBoot != null) return sameBoot
            val next = boots.getOrNull(bootIndex + step) ?: return null
            if (older) return next.id
            if (next.hasLegacy) return legacyBootOrder(installation, next.boot).lastOrNull()?.id
            return db.rawQuery("SELECT id FROM ecg_records WHERE installation=? AND boot=? ORDER BY sensor_elapsed ASC,id ASC LIMIT 1",
                arrayOf(installation, next.boot)).use { if (it.moveToFirst()) it.getString(0) else null }
        }
        val ambiguousLegacy = boots.any { candidate -> candidate.hasLegacy &&
            if (candidate.boot == selected.boot) (legacy?.size ?: 0) > 1 else db.rawQuery(
                "SELECT 1 FROM ecg_records WHERE installation=? AND boot=? AND id!=? LIMIT 1",
                arrayOf(installation, candidate.boot, candidate.id)).use { it.moveToFirst() }
        }
        val uncertain = boots.size > 1 && boots.any { it.count == null } || ambiguousLegacy
        val page = EcgPage(selected, neighbor(true), neighbor(false), uncertain)
        db.setTransactionSuccessful()
        return page
    } finally { db.endTransaction() }
}

/** Legacy rows have no recorded start clock. Register their boot identity without decrypting or rewriting originals. */
internal fun EcgJournal.migrateOrder() {
    val ready = db.rawQuery("PRAGMA table_info(ecg_records)", null).use { c ->
        var found = false; while (c.moveToNext()) if (c.getString(1) == "order_ready") found = true; found
    }
    if (!ready) {
        db.execSQL("ALTER TABLE ecg_records ADD COLUMN sensor_elapsed INTEGER")
        db.execSQL("ALTER TABLE ecg_records ADD COLUMN order_ready INTEGER NOT NULL DEFAULT 0")
    }
    db.execSQL("CREATE INDEX IF NOT EXISTS ecg_order_pending ON ecg_records(installation,boot) WHERE order_ready=0")
    db.rawQuery("SELECT installation,boot FROM ecg_records WHERE order_ready=0 GROUP BY installation,boot", null).use { c ->
        while (c.moveToNext()) journal.registerBoot(c.getString(0).also(::uuid), c.getString(1).also(::uuid), null)
    }
    db.execSQL("UPDATE ecg_records SET order_ready=1 WHERE order_ready=0")
    db.execSQL("CREATE INDEX IF NOT EXISTS ecg_sensor_order ON ecg_records(installation,boot,sensor_elapsed DESC,id DESC)")
    db.execSQL("CREATE INDEX IF NOT EXISTS ecg_legacy_order ON ecg_records(installation,boot,start DESC,id DESC) WHERE sensor_elapsed IS NULL")
    db.execSQL("DROP INDEX IF EXISTS ecg_history")
}
