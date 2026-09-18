package dev.estaab.salchang.ssh

import dev.estaab.salchang.data.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class SshControlTransportTest {
    private val base = HostProfile(id = "1", name = "box", hostname = "box.tail", username = "me", keyId = "k")

    @Test
    fun defaultSocketUsesNoSocketFlag() {
        assertEquals("'tmux' '-C' 'new-session' '-t' 'main'", SshControlTransport.buildTmuxCommand(base))
    }

    @Test
    fun sessionNameWithSpaceAndQuoteIsSafelyQuoted() {
        val p = base.copy(tmuxSession = "my 'work' session")
        assertEquals("'tmux' '-C' 'new-session' '-t' 'my '\\''work'\\'' session'", SshControlTransport.buildTmuxCommand(p))
    }

    @Test
    fun socketNameAndPathFlags() {
        assertEquals(
            "'/usr/local/bin/tmux' '-L' 'dev' '-C' 'new-session' '-t' 'main'",
            SshControlTransport.buildTmuxCommand(base.copy(tmuxBinary = "/usr/local/bin/tmux", tmuxSocketName = "dev")),
        )
        assertEquals(
            "'tmux' '-S' '/tmp/tmux sock' '-C' 'new-session' '-t' 'main'",
            SshControlTransport.buildTmuxCommand(base.copy(tmuxSocketPath = "/tmp/tmux sock")),
        )
    }

    @Test
    fun socketPathWinsOverSocketName() {
        val p = base.copy(tmuxSocketName = "dev", tmuxSocketPath = "/tmp/s")
        assertEquals("'tmux' '-S' '/tmp/s' '-C' 'new-session' '-t' 'main'", SshControlTransport.buildTmuxCommand(p))
    }

    @Test
    fun shellQuoteEscapesSingleQuotes() {
        assertEquals("'it'\\''s'", SshControlTransport.shellQuote("it's"))
        assertEquals("''", SshControlTransport.shellQuote(""))
    }
}
