package com.mani.orbit.sync

/** Highest native ordinal first, for one clock domain. Only unknown legacy entries use recorded wall time.
 * Merge two ordered streams: a mixed pairwise comparator would be non-transitive after clock rollback.
 */
internal fun <T, O : Comparable<O>> orderSensorEntries(candidates: List<Pair<T, O?>>, wallOrder: Comparator<T>): List<T> {
    val known = candidates.filter { it.second != null }.sortedByDescending { it.second }.map { it.first }
    val legacy = candidates.filter { it.second == null }.map { it.first }.sortedWith(wallOrder.reversed())
    return buildList {
        var k = 0; var l = 0
        while (k < known.size || l < legacy.size) {
            if (l == legacy.size || (k < known.size && wallOrder.compare(known[k], legacy[l]) >= 0)) add(known[k++])
            else add(legacy[l++])
        }
    }
}
