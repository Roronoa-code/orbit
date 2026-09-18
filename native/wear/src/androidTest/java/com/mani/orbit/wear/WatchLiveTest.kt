package com.mani.orbit.wear

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mani.orbit.sync.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WatchLiveTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sent = mutableListOf<Pair<String, LiveUpdate>>()
    private lateinit var original: (Context, String, ByteArray) -> Unit
    private lateinit var originalStart: (Context) -> Unit
    private var starts = 0
    private var wasEnabled = false

    @Before fun capture() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Local emulator tests only" }
        original = WatchLive.send
        originalStart = WatchLive.startLive
        WatchLive.startLive = { starts++ }
        wasEnabled = WatchStore(context).enabled()
        WatchLive.reset(context)
        WatchLive.send = { _, node, bytes -> synchronized(sent) { sent += node to LiveWire.decodeUpdate(bytes) } }
    }

    @After fun restore() {
        WatchLive.send = original
        WatchLive.startLive = originalStart
        WatchLive.reset(context)
        WatchStore(context).enable(wasEnabled)
    }

    private val boot = UUID.randomUUID().toString()
    private fun heart(value: Double?, at: Long, quality: String = "valid", uncertain: Boolean = false) =
        WatchReading(UUID.randomUUID().toString(), 1, at, at, 0, "heart", value, "bpm", quality, "instant", boot, 1000,
            timeUncertain = uncertain)

    /** A phone holds the watch for one lease at most, and letting go ends it at once. */
    @Test fun aPhoneLeaseIsBoundedAndEndsWhenReleased() {
        val now = System.currentTimeMillis()
        WatchLive.lease(context, "phone", LiveRequest(now + 3_600_000), now)
        assertEquals("phone", WatchLive.leased(context, now + LiveWire.LEASE_MS - 1))
        assertNull(WatchLive.leased(context, now + LiveWire.LEASE_MS + 1))
        WatchLive.lease(context, "phone", LiveRequest(now + 10_000), now)
        WatchLive.lease(context, "phone", LiveRequest(0), now)
        assertNull(WatchLive.leased(context, now))
    }

    /**
     * While a phone is watching, the newest valid heart reading of each capture goes straight to it, once.
     * Nothing goes while no phone is watching, and nothing the watch could not stand behind goes at all.
     */
    @Test fun whileAPhoneWatchesEachNewValidHeartReadingIsPushedOnce() {
        val now = System.currentTimeMillis()
        WatchLive.offer(context, listOf(heart(70.0, now - 2_000)))
        assertTrue("Nothing is pushed without a lease", sent.isEmpty())

        WatchLive.lease(context, "phone", LiveRequest(now + 30_000))
        WatchLive.offer(context, listOf(heart(71.0, now - 3_000), heart(74.0, now - 1_000), heart(null, now - 500, "no_contact"),
            heart(120.0, now - 400, uncertain = true)))
        assertEquals(1, sent.size)
        assertEquals("phone", sent[0].first)
        assertEquals(74.0, sent[0].second.bpm!!, 0.0)
        assertEquals(now - 1_000, sent[0].second.at)

        WatchLive.offer(context, listOf(heart(74.0, now - 1_000)))
        assertEquals("The same reading is not news", 1, sent.size)
        WatchLive.offer(context, listOf(heart(40.0, now - 200, "unreliable")))
        assertEquals(1, sent.size)
        WatchLive.offer(context, listOf(heart(76.0, now)))
        assertEquals(2, sent.size)
        assertEquals(76.0, sent[1].second.bpm!!, 0.0)
    }

    /** A new step count goes to the watching phone at once, even between a live recording's heart pushes. */
    @Test fun aNewStepCountGoesToTheWatchingPhoneAtOnce() {
        val now = System.currentTimeMillis()
        val midnight = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        fun steps(count: Double, at: Long) = WatchReading(UUID.randomUUID().toString(), 1, midnight, at, 0, "steps", count, "count",
            "valid", "daily", boot, 1000)
        WatchLive.lease(context, "phone", LiveRequest(now + 30_000))
        WatchLive.offer(context, listOf(steps(800.0, maxOf(midnight, now - 5_000))))
        assertEquals(800.0, sent.last().second.steps!!, 0.0)
        // A heart push carries the watch's step count with it.
        WatchLive.offer(context, listOf(heart(80.0, now - 1_000)))
        assertEquals(80.0, sent.last().second.bpm!!, 0.0)
        assertEquals(800.0, sent.last().second.steps!!, 0.0)
        val before = sent.size
        WatchLive.offer(context, listOf(steps(800.0, maxOf(midnight, now - 5_000))))
        assertEquals("The same count is not news", before, sent.size)
        WatchLive.offer(context, listOf(steps(812.0, now)))
        assertEquals(812.0, sent.last().second.steps!!, 0.0)
    }

    /**
     * Opening Orbit on the phone starts the watch streaming by itself; nobody has to press record. The
     * answer comes at once, addressed to the phone that asked. If the wearer stops the phone's stream on
     * the watch it stays stopped until the phone lets go, and a start Android refuses leaves the passive
     * readings answering.
     */
    @Test fun aRequestStartsTheStreamForThePhoneUnlessTheWearerStoppedIt() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.grantRuntimePermission(context.packageName, WatchPermissions.heart)
        WatchPermissions.background?.let { automation.grantRuntimePermission(context.packageName, it) }
        val store = WatchStore(context)
        fun ask(): LiveUpdate {
            val index = synchronized(sent) { sent.size }
            WatchLive.request(context, "phone", LiveRequest(System.currentTimeMillis() + 30_000))
            return synchronized(sent) { assertEquals("phone", sent[index].first); sent[index].second }
        }
        store.enable(false)
        assertEquals("streaming", ask().state)
        assertEquals(1, starts)

        WatchLive.decline(context)
        assertEquals("off", ask().state)
        store.enable(true)
        assertEquals("passive", ask().state)
        assertEquals("A stream the wearer stopped is not restarted", 1, starts)

        WatchLive.request(context, "phone", LiveRequest(0))
        assertNull(WatchLive.leased(context))
        assertFalse(WatchLive.declined(context))
        assertEquals("streaming", ask().state)
        assertEquals(2, starts)

        WatchLive.startLive = { throw IllegalStateException("Background start refused") }
        val refused = ask()
        assertEquals("passive", refused.state)
        // Its reading, if it has one, is the newest valid one the watch held when it answered.
        refused.at?.let { at -> assertTrue(at <= (WatchLive.newest(context)?.second ?: at)) }
    }
}
