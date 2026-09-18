package com.mani.orbit

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mani.orbit.sync.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class LiveTest {
    private fun edit(bytes: ByteArray, change: JSONObject.() -> Unit) =
        JSONObject(bytes.toString(Charsets.UTF_8)).apply(change).toString().toByteArray(Charsets.UTF_8)

    /** A live message is small, strict and versioned like every other family; an unknown state is a newer watch. */
    @Test fun aLiveMessageKeepsItsMeaningAndRefusesWhatItCannotTrust() {
        val now = System.currentTimeMillis()
        assertEquals(LiveRequest(now + 45_000), LiveWire.decodeRequest(LiveWire.encode(LiveRequest(now + 45_000))))
        assertTrue(LiveWire.decodeRequest(LiveWire.encode(LiveRequest(0))).release)
        val update = LiveUpdate("passive", 72.0, now - 5_000, now)
        assertEquals(update, LiveWire.decodeUpdate(LiveWire.encode(update)))
        val waiting = LiveUpdate("needs_access", null, null, now)
        assertEquals(waiting, LiveWire.decodeUpdate(LiveWire.encode(waiting)))

        val bytes = LiveWire.encode(update)
        assertThrows(UnsupportedWire::class.java) { LiveWire.decodeUpdate(edit(bytes) { put("version", 2) }) }
        assertThrows(UnsupportedWire::class.java) { LiveWire.decodeUpdate(edit(bytes) { put("state", "orbiting") }) }
        // A request is never mistaken for an update, or the other way round.
        assertThrows(IllegalArgumentException::class.java) { LiveWire.decodeRequest(bytes) }
        assertThrows(IllegalArgumentException::class.java) { LiveWire.decodeUpdate(LiveWire.encode(LiveRequest(now))) }
        // A reading needs its time, and a time needs its reading; neither may be impossible.
        assertThrows(IllegalArgumentException::class.java) { LiveWire.decodeUpdate(edit(bytes) { put("at", JSONObject.NULL) }) }
        assertThrows(IllegalArgumentException::class.java) { LiveWire.decodeUpdate(edit(bytes) { put("bpm", 0) }) }
        assertThrows(IllegalArgumentException::class.java) { LiveWire.decodeUpdate(edit(bytes) { put("bpm", 400) }) }
        assertThrows(IllegalArgumentException::class.java) { LiveWire.decodeUpdate(ByteArray(LiveWire.MAX_BYTES + 1) { ' '.code.toByte() }) }
        assertThrows(IllegalArgumentException::class.java) { LiveWire.decodeRequest(edit(LiveWire.encode(LiveRequest(now))) { put("until", -1) }) }
    }

    /** The phone keeps the most recent answer, and ignores a reading the watch's clock put in the future. */
    @Test fun thePhoneHoldsTheLatestAnswerAndIgnoresAReadingFromTheFuture() {
        PhoneLive.reset()
        val now = System.currentTimeMillis()
        PhoneLive.receive(LiveUpdate("passive", 70.0, now - 10_000, now - 1_000), now)
        PhoneLive.receive(LiveUpdate("passive", 60.0, now - 20_000, now - 2_000), now)
        assertEquals(70.0, PhoneLive.updates.value!!.bpm!!, 0.0)
        PhoneLive.receive(LiveUpdate("streaming", 99.0, now + 120_000, now), now)
        assertEquals(70.0, PhoneLive.updates.value!!.bpm!!, 0.0)
        PhoneLive.receive(LiveUpdate("needs_access", null, null, now), now)
        assertEquals("needs_access", PhoneLive.updates.value!!.state)
        PhoneLive.reset()
    }

    /** Today's heart card says where its reading came from, or what keeps the watch from sending one live. */
    @Test fun theHeartCardNamesItsSourceOrWhatTheWatchNeeds() {
        val today = LocalDate.now()
        fun footer(day: HealthDay, state: String?) = HomeSummary.from(HealthScreenState(day = day, watchHeartState = state), HomeMetric.Heart, 1, null)
        val samsung = HealthDay(today, heart = 64.0, heartAt = System.currentTimeMillis() - 3_600_000)
        assertEquals(HomeFact("Recorded readings", "Samsung Health"), footer(samsung, "passive").footer.first())
        assertEquals("bpm · latest reading", footer(samsung, "passive").caption)
        assertEquals(HomeFact("Live from watch", "Allow heart access"), footer(samsung, "needs_access").footer.first())
        assertEquals(HomeFact("Live from watch", "Turned off on watch"), footer(samsung, "off").footer.first())
        val watch = samsung.copy(heart = 81.0, heartAt = System.currentTimeMillis() - 5_000, heartFromWatch = true)
        assertEquals(HomeFact("Recorded readings", "Galaxy Watch"), footer(watch, "passive").footer.first())
        assertEquals("bpm · from your watch", footer(watch, "passive").caption)
        assertEquals("bpm · live from your watch", footer(watch, "streaming").caption)
        // Another day, or a week's average, is Samsung Health's record, whatever the watch is doing now.
        assertEquals(HomeFact("Recorded readings", "Samsung Health"), footer(samsung.copy(date = today.minusDays(1)), "needs_access").footer.first())
        assertEquals(HomeFact("Recorded readings", "Samsung Health"),
            HomeSummary.from(HealthScreenState(day = samsung, watchHeartState = "needs_access"), HomeMetric.Heart, 7, null).footer.first())
    }

    /**
     * Steps the watch counts while the phone stays behind are added to Samsung Health's total at once;
     * when Samsung catches up they fold in, carrying both counts nothing twice, and the day never goes back.
     */
    @Test fun theWatchsStepsLeadSamsungWithoutCountingAnyTwice() {
        val today = LocalDate.now()
        val lead = WatchStepLead()
        assertNull("Nothing from the watch yet", lead.lead(917.0, today))
        lead.watch(730.0, 1_000, today)
        assertEquals(0.0, lead.lead(917.0, today)!!.first, 0.0)
        // A walk without the phone: the watch counts, Samsung stands still.
        lead.watch(821.0, 2_000, today)
        assertEquals(91.0, lead.lead(917.0, today)!!.first, 0.0)
        // Samsung hears about part of the walk, then the rest: the total never falls back.
        assertEquals(31.0, lead.lead(977.0, today)!!.first, 0.0)
        assertEquals(0.0, lead.lead(1008.0, today)!!.first, 0.0)
        // Carrying the phone too, both count the same steps: Samsung's rise absorbs the watch's.
        lead.watch(900.0, 3_000, today)
        assertEquals(0.0, lead.lead(1087.0, today)!!.first, 0.0)
        // Steps only the phone saw leave nothing to add.
        assertEquals(0.0, lead.lead(1200.0, today)!!.first, 0.0)
        // An older report from the watch changes nothing; a new day starts over.
        lead.watch(100.0, 2_500, today)
        assertEquals(900.0, lead.lead(1200.0, today)!!.second, 0.0)
        lead.watch(12.0, 4_000, today.plusDays(1))
        assertNull(lead.lead(1200.0, today))
        assertEquals(0.0, lead.lead(40.0, today.plusDays(1))!!.first, 0.0)
    }

    /** A live update carries the watch's step count too, and an older watch that sends none still decodes. */
    @Test fun aLiveUpdateCarriesTheWatchsStepsAndAnOlderWatchStillReads() {
        val now = System.currentTimeMillis()
        val update = LiveUpdate("streaming", 90.0, now - 1_000, now, 1234.0, now - 2_000)
        assertEquals(update, LiveWire.decodeUpdate(LiveWire.encode(update)))
        val older = JSONObject(LiveWire.encode(LiveUpdate("passive", 70.0, now - 1_000, now)).toString(Charsets.UTF_8))
            .apply { remove("steps"); remove("stepsAt") }.toString().toByteArray(Charsets.UTF_8)
        assertNull(LiveWire.decodeUpdate(older).steps)
        assertThrows(IllegalArgumentException::class.java) { LiveUpdate("passive", null, null, now, 10.0, null) }
        assertThrows(IllegalArgumentException::class.java) { LiveUpdate("passive", null, null, now, -1.0, now) }
    }
}
