package dev.estaab.salchang.session

import dev.estaab.salchang.data.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentProberTest {
    private val base = HostProfile(id = "1", name = "box", hostname = "box.tail", username = "me", keyId = "k")

    @Test
    fun probeArgsCarryTheSocketFlagsThenTheInterval() {
        assertEquals(listOf("--interval", "3"), AgentProber.probeArgs(base))
        assertEquals(listOf("-L", "dev", "--interval", "3"), AgentProber.probeArgs(base.copy(tmuxSocketName = "dev")))
        assertEquals(listOf("-S", "/tmp/s", "--interval", "3"), AgentProber.probeArgs(base.copy(tmuxSocketName = "dev", tmuxSocketPath = "/tmp/s")))
    }

    @Test
    fun backoffDoublesAndCaps() {
        assertEquals(4_000L, AgentProber.nextBackoff(2_000L))
        assertEquals(32_000L, AgentProber.nextBackoff(16_000L))
        assertEquals(60_000L, AgentProber.nextBackoff(32_000L))
        assertEquals(60_000L, AgentProber.nextBackoff(60_000L))
    }

    @Test
    fun missingDependencyExitStatusMatchesTheScript() {
        assertEquals(2, PROBE_EXIT_MISSING_DEPENDENCY)
    }
}
