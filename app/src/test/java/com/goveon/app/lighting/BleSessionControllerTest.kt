package com.goveon.app.lighting

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BleSessionControllerTest {
    private class Fixture(scope: TestScope) {
        val targets = mutableMapOf<String, BleSessionTarget>()
        val attempts = mutableListOf<String>()
        val disconnected = mutableListOf<String>()
        var foreground = true
        var answer: suspend (String) -> BleSessionAttempt = { id ->
            set(id, BleConnection.CONNECTED)
            BleSessionAttempt.Connected
        }
        val controller = BleSessionController(scope.backgroundScope, { id -> attempts.add(id); answer(id) }, { disconnected.add(it) })
        fun set(id: String, state: BleConnection, reason: BleFailureReason? = null) {
            targets[id] = BleSessionTarget(id, state, failureReason = reason)
            update()
        }
        fun update() = controller.update(targets.values.toList(), foreground)
        fun phase(id: String) = controller.status.value[id]?.phase
    }

    @Test fun `page owns one connection despite repeated identical updates`() = runTest {
        val f = Fixture(this)
        f.set("light", BleConnection.DISCONNECTED)
        runCurrent()
        repeat(10) { f.update() }
        runCurrent()
        assertEquals(listOf("light"), f.attempts)
        assertEquals(BleSessionPhase.CONNECTED, f.phase("light"))
        f.targets.clear(); f.update(); runCurrent()
        assertEquals(listOf("light"), f.disconnected)
        assertTrue(f.controller.status.value.isEmpty())
    }

    @Test fun `transient failures use bounded exponential backoff then stop`() = runTest {
        val f = Fixture(this)
        f.answer = { BleSessionAttempt.Retryable("Unavailable") }
        f.set("light", BleConnection.DISCONNECTED)
        runCurrent()
        assertEquals(1, f.attempts.size)
        advanceTimeBy(999); runCurrent(); assertEquals(1, f.attempts.size)
        advanceTimeBy(1); runCurrent(); assertEquals(2, f.attempts.size)
        advanceTimeBy(14_000); runCurrent()
        assertEquals(5, f.attempts.size)
        assertEquals(BleSessionPhase.STOPPED, f.phase("light"))
        repeat(5) { f.update() }
        advanceTimeBy(100_000); runCurrent()
        assertEquals(5, f.attempts.size)
        f.controller.close()
    }

    @Test fun `permission auth and unsupported failures never automatically retry`() = runTest {
        for (reason in listOf(BleFailureReason.PERMISSION, BleFailureReason.AUTHENTICATION, BleFailureReason.UNSUPPORTED)) {
            val f = Fixture(this)
            f.answer = { throw BleFailure("Cannot connect", reason) }
            f.set("light", BleConnection.DISCONNECTED)
            runCurrent(); f.update()
            advanceTimeBy(60_000); runCurrent()
            assertEquals(1, f.attempts.size)
            assertEquals(reason, f.controller.status.value["light"]?.reason)
            assertEquals(BleSessionPhase.STOPPED, f.phase("light"))
            f.controller.close()
        }
    }

    @Test fun `background cancels backoff and foreground restarts desired sessions`() = runTest {
        val f = Fixture(this)
        f.answer = { BleSessionAttempt.Retryable("Unavailable") }
        f.set("light", BleConnection.DISCONNECTED); runCurrent()
        f.foreground = false; f.update(); runCurrent()
        advanceTimeBy(60_000); runCurrent()
        assertEquals(1, f.attempts.size)
        assertTrue(f.controller.status.value.isEmpty())
        f.foreground = true; f.update(); runCurrent()
        assertEquals(2, f.attempts.size)
        f.controller.close()
    }

    @Test fun `terminal disconnection stops immediately while explicit retry creates one worker`() = runTest {
        val f = Fixture(this)
        f.set("light", BleConnection.DISCONNECTED); runCurrent()
        f.set("light", BleConnection.DISCONNECTED, BleFailureReason.AUTHENTICATION); runCurrent()
        assertEquals(BleSessionPhase.STOPPED, f.phase("light"))
        assertEquals(1, f.attempts.size)
        f.controller.retry("light"); runCurrent()
        assertEquals(2, f.attempts.size)
        assertEquals(BleSessionPhase.CONNECTED, f.phase("light"))
        f.controller.close()
    }

    @Test fun `rapid drop cycles retain budget but stable connection resets it`() = runTest {
        val f = Fixture(this)
        f.set("light", BleConnection.DISCONNECTED); runCurrent()
        f.set("light", BleConnection.DISCONNECTED); runCurrent()
        advanceTimeBy(1_000); runCurrent()
        assertEquals(2, f.attempts.size)
        assertEquals(2, f.controller.status.value["light"]?.attempts)
        advanceTimeBy(30_000); runCurrent()
        f.set("light", BleConnection.DISCONNECTED); runCurrent()
        assertEquals(3, f.attempts.size)
        assertEquals(1, f.controller.status.value["light"]?.attempts)
        f.controller.close()
    }

    @Test fun `group setup bounds simultaneous connections and leaving cancels pending work`() = runTest {
        val f = Fixture(this)
        var active = 0
        var peak = 0
        var cancelled = 0
        f.answer = { id ->
            active++; peak = maxOf(peak, active)
            try { delay(10_000); f.set(id, BleConnection.CONNECTED); BleSessionAttempt.Connected }
            catch (failure: CancellationException) { cancelled++; throw failure }
            finally { active-- }
        }
        repeat(8) { f.targets["light$it"] = BleSessionTarget("light$it", BleConnection.DISCONNECTED) }
        f.update(); runCurrent()
        assertEquals(3, peak)
        assertEquals(3, f.attempts.size)
        f.targets.clear(); f.update(); runCurrent()
        assertEquals(3, cancelled)
        assertEquals(0, active)
        advanceTimeBy(60_000); runCurrent()
        assertEquals(3, f.attempts.size)
        assertEquals(8, f.disconnected.size)
    }

    @Test fun `existing manual setup is awaited instead of opening another GATT connection`() = runTest {
        val f = Fixture(this)
        f.set("light", BleConnection.CONNECTING); runCurrent()
        advanceTimeBy(5_000); runCurrent()
        assertTrue(f.attempts.isEmpty())
        f.set("light", BleConnection.CONNECTED); runCurrent()
        assertEquals(BleSessionPhase.CONNECTED, f.phase("light"))
        assertTrue(f.attempts.isEmpty())
        f.controller.close()
    }

    @Test fun `queued group connection rechecks terminal failure before opening GATT`() = runTest {
        val f = Fixture(this)
        f.answer = { id -> delay(1_000); f.set(id, BleConnection.CONNECTED); BleSessionAttempt.Connected }
        repeat(4) { f.targets["light$it"] = BleSessionTarget("light$it", BleConnection.DISCONNECTED) }
        f.update(); runCurrent()
        f.set("light3", BleConnection.DISCONNECTED, BleFailureReason.AUTHENTICATION)
        advanceTimeBy(1_000); runCurrent()
        assertEquals(3, f.attempts.size)
        assertEquals(BleSessionPhase.STOPPED, f.phase("light3"))
        assertEquals(BleFailureReason.AUTHENTICATION, f.controller.status.value["light3"]?.reason)
        f.controller.close()
    }

    @Test fun `successful connect waits for repository collector instead of opening a duplicate`() = runTest {
        val f = Fixture(this)
        f.answer = { id ->
            backgroundScope.launch { delay(500); f.set(id, BleConnection.CONNECTED) }
            BleSessionAttempt.Connected
        }
        f.set("light", BleConnection.DISCONNECTED); runCurrent()
        assertEquals(BleSessionPhase.CONNECTING, f.phase("light"))
        advanceTimeBy(500); runCurrent()
        assertEquals(1, f.attempts.size)
        assertEquals(BleSessionPhase.CONNECTED, f.phase("light"))
        f.controller.close()
    }

    @Test fun `permission revocation after stable connection stops without another attempt`() = runTest {
        val f = Fixture(this)
        f.set("light", BleConnection.DISCONNECTED); runCurrent()
        advanceTimeBy(30_000); runCurrent()
        f.set("light", BleConnection.DISCONNECTED, BleFailureReason.PERMISSION); runCurrent()
        assertEquals(1, f.attempts.size)
        assertEquals(BleSessionPhase.STOPPED, f.phase("light"))
        assertEquals(BleFailureReason.PERMISSION, f.controller.status.value["light"]?.reason)
        f.controller.close()
    }
}
