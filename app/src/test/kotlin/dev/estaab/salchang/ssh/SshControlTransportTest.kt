package dev.estaab.salchang.ssh

import dev.estaab.salchang.data.HostProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SshControlTransportTest {
    private val base = HostProfile(id = "1", name = "box", hostname = "box.tail", username = "me")

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

    @Test
    fun listSessionsCommandQuotesTheFormat() {
        val format = "#{session_group}\t#{session_name}\t#{session_windows}\t#{session_attached}"
        assertEquals(format, SshControlTransport.LIST_SESSIONS_FORMAT)
        assertEquals("'tmux' 'list-sessions' '-F' '$format'", SshControlTransport.buildListSessionsCommand(base))
        assertEquals(
            "'tmux' '-L' 'dev' 'list-sessions' '-F' '$format'",
            SshControlTransport.buildListSessionsCommand(base.copy(tmuxSocketName = "dev")),
        )
        assertEquals(
            "'/opt/tmux' '-S' '/tmp/s' 'list-sessions' '-F' '$format'",
            SshControlTransport.buildListSessionsCommand(base.copy(tmuxBinary = "/opt/tmux", tmuxSocketName = "dev", tmuxSocketPath = "/tmp/s")),
        )
    }

    @Test
    fun parseListSessionsHandlesGroupedAndUngroupedLines() {
        // Verbatim tmux 3.4 output for `new-session -s S; new-session -t S; new-session -s solo`.
        val stdout = "S\tS\t3\t1\nS\tS-1\t3\t0\n\tsolo\t1\t0\n"
        assertEquals(
            listOf(
                RemoteTmuxSession(group = "S", name = "S", windows = 3, attached = 1),
                RemoteTmuxSession(group = "S", name = "S-1", windows = 3, attached = 0),
                RemoteTmuxSession(group = null, name = "solo", windows = 1, attached = 0),
            ),
            SshControlTransport.parseListSessions(stdout),
        )
        assertEquals(emptyList<RemoteTmuxSession>(), SshControlTransport.parseListSessions(""))
        assertEquals(emptyList<RemoteTmuxSession>(), SshControlTransport.parseListSessions("\n\n"))
    }

    @Test
    fun parseListSessionsRejectsMalformedLines() {
        assertThrows(IllegalArgumentException::class.java) { SshControlTransport.parseListSessions("S\tS\t3\n") }
        assertThrows(IllegalArgumentException::class.java) { SshControlTransport.parseListSessions("S\tS\tmany\t0\n") }
        assertThrows(IllegalArgumentException::class.java) { SshControlTransport.parseListSessions("no server running on /tmp/x\n") }
    }

    @Test
    fun attachTargetsCollapsesGroupsAndKeepsOrder() {
        val sessions = listOf(
            RemoteTmuxSession(group = null, name = "solo", windows = 1, attached = 0),
            RemoteTmuxSession(group = "S", name = "S", windows = 3, attached = 1),
            RemoteTmuxSession(group = "main", name = "main-11", windows = 1, attached = 0),
            RemoteTmuxSession(group = "S", name = "S-1", windows = 3, attached = 0),
            RemoteTmuxSession(group = null, name = "other", windows = 2, attached = 1),
        )
        assertEquals(
            listOf(
                TmuxAttachTarget(name = "S", grouped = true, sessions = 2, windows = 3),
                TmuxAttachTarget(name = "main", grouped = true, sessions = 1, windows = 1),
                TmuxAttachTarget(name = "solo", grouped = false, sessions = 1, windows = 1),
                TmuxAttachTarget(name = "other", grouped = false, sessions = 1, windows = 2),
            ),
            SshControlTransport.attachTargets(sessions),
        )
    }

    @Test
    fun isNoServerErrorMatchesTmuxMessages() {
        val cmd = "'tmux' 'list-sessions'"
        assertTrue(SshControlTransport.isNoServerError(RemoteCommandFailedException(cmd, 1, "no server running on /tmp/tmux-1000/default\n")))
        assertTrue(SshControlTransport.isNoServerError(RemoteCommandFailedException(cmd, 1, "error connecting to /tmp/tmux-1000/dev (No such file or directory)\n")))
        assertFalse(SshControlTransport.isNoServerError(RemoteCommandFailedException(cmd, 127, "bash: tmux: command not found\n")))
        assertFalse(SshControlTransport.isNoServerError(RemoteCommandFailedException(cmd, 1, "")))
    }
}
