package com.goveon.app.lighting

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CommandExecutionTest {
    @Test fun `recipe validation rejects unsupported values before any member can send`() {
        val bulb = LightDevice("bulb", "H6006", "Bulb", bleProfile = "bulb-v3")
        assertThrows(IllegalArgumentException::class.java) { validateRecipe(listOf(bulb), listOf(LightCommand.Power(true), LightCommand.Temperature(2000))) }
        assertThrows(IllegalArgumentException::class.java) { validateRecipe(listOf(bulb), listOf(LightCommand.Brightness(101))) }
        val basic = bulb.copy(bleProfile = "common-light")
        assertThrows(IllegalArgumentException::class.java) { validateRecipe(listOf(bulb, basic), listOf(LightCommand.Temperature(4000))) }
        validateRecipe(listOf(bulb), listOf(LightCommand.Power(true), LightCommand.Temperature(4000)))
    }
    @Test fun `absent relevant LAN fields do not turn accepted delivery into rejection`() {
        listOf(LightCommand.Power(true), LightCommand.Brightness(100), LightCommand.Color(0xFFFFFF), LightCommand.Temperature(4000))
            .forEach { command -> assertEquals(CommandStatus.ACCEPTED, lanCommandResult("light", command, LightState(online = true)).status) }
        assertEquals(CommandStatus.ACCEPTED, lanCommandResult("light", LightCommand.Brightness(100), null).status)
    }
    @Test fun `actual requested readings confirm and contradictory readings fail`() {
        assertEquals(CommandStatus.CONFIRMED, lanCommandResult("light", LightCommand.Brightness(100), LightState(brightness = 100)).status)
        assertEquals(CommandStatus.FAILED, lanCommandResult("light", LightCommand.Brightness(100), LightState(brightness = 50)).status)
        assertEquals(CommandStatus.FAILED, lanCommandResult("light", LightCommand.Color(0xFFFFFF), LightState(rgb = 0xFFFFFF, temperatureKelvin = 4000)).status)
    }
    @Test fun `overlapping recipes and controls for one light preserve whole recipe order`() = runTest {
        val locks = CommandLocks()
        val trace = mutableListOf<Int>()
        val first = launch { locks.withLock("light") {
            executeRecipe("light", listOf(LightCommand.Brightness(10), LightCommand.Brightness(20))) { id, command ->
                trace.add((command as LightCommand.Brightness).percent); delay(100)
                CommandResult(id, CommandStatus.ACCEPTED, "Sent")
            }
        } }
        val second = launch { locks.withLock("light") { trace.add(30) } }
        first.join(); second.join()
        assertEquals(listOf(10, 20, 30), trace)
    }
    @Test fun `different targets remain concurrent`() = runTest {
        val locks = CommandLocks()
        val trace = mutableListOf<String>()
        val first = launch { locks.withLock("a") { trace.add("a-start"); delay(100); trace.add("a-end") } }
        val second = launch { locks.withLock("b") { trace.add("b") } }
        first.join(); second.join()
        assertEquals(listOf("a-start", "b", "a-end"), trace)
    }
    @Test fun `failed recipe stops before subsequent commands`() = runTest {
        val sent = mutableListOf<LightCommand>()
        val result = executeRecipe("light", listOf(LightCommand.Power(true), LightCommand.Brightness(50))) { id, command -> sent.add(command); failure(id, "Unavailable") }
        assertEquals(1, sent.size); assertFalse(result.successful)
    }
    @Test fun `cancellation is propagated and releases the target lock`() = runTest {
        val locks = CommandLocks()
        try { locks.withLock("light") { executeRecipe("light", listOf(LightCommand.Power(true))) { _, _ -> throw CancellationException("Background") } }; fail() }
        catch (_: CancellationException) { }
        assertEquals(42, locks.withLock("light") { 42 })
    }
}
