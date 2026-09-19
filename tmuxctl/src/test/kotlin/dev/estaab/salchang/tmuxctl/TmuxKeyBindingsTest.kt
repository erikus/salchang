package dev.estaab.salchang.tmuxctl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxKeyBindingsTest {
    @Test
    fun parsesPlainRepeatAndRootLines() {
        val bindings = parseListKeys(
            listOf(
                "bind-key    -T prefix C-n     last-window",
                "bind-key -r -T prefix M-Up    resize-pane -U 5",
                "bind-key -T root M-Left                 previous-window",
            ),
        )
        assertEquals(
            listOf(
                TmuxKeyBinding("prefix", "C-n", "last-window", repeat = false),
                TmuxKeyBinding("prefix", "M-Up", "resize-pane -U 5", repeat = true),
                TmuxKeyBinding("root", "M-Left", "previous-window", repeat = false),
            ),
            bindings,
        )
    }

    @Test
    fun unescapesKeyTokens() {
        val bindings = parseListKeys(
            listOf(
                """bind-key    -T prefix \"       split-window""",
                """bind-key    -T prefix \#       list-buffers""",
                """bind-key    -T prefix \;       last-pane""",
                """bind-key    -T prefix \\       display-message k=\\""",
                """bind-key    -T prefix C-\\     display-message k=C-\\""",
                """bind-key    -T prefix \{       display-message "k={"""",
                """bind-key    -T prefix \~       display-message k=~""",
                """bind-key    -T prefix 'M-"'    display-message 'k=M-"'""",
                """bind-key    -T prefix "M-{"    display-message "k=M-{"""",
                """bind-key    -T prefix Space    next-layout""",
                """bind-key    -T prefix é        display-message k=é""",
            ),
        )
        assertEquals(
            listOf("\"", "#", ";", "\\", "C-\\", "{", "~", "M-\"", "M-{", "Space", "é"),
            bindings.map { it.key },
        )
        // The command is kept as printed (re-parsable tmux syntax).
        assertEquals("""display-message k=C-\\""", bindings[4].command)
    }

    @Test
    fun keepsCommandsWithBracesAndQuotesVerbatim() {
        val menu = """display-menu -T "#[align=centre]#{window_index}:#{window_name}" -x W -y W "Swap Left" l { swap-window -t :-1 } '' Kill X { kill-window }"""
        val confirm = """confirm-before -p "kill-window #W? (y/n)" kill-window"""
        val ifShell = """if-shell -F "#{window_zoomed_flag}" "resize-pane -Z" "resize-pane -Z ; display \"zoom\""""
        val bindings = parseListKeys(
            listOf(
                "bind-key    -T prefix <        $menu",
                "bind-key    -T prefix &        $confirm",
                "bind-key    -T prefix z        $ifShell",
                "bind-key    -T prefix y        display-message \"it's\"",
            ),
        )
        assertEquals(listOf(menu, confirm, ifShell, "display-message \"it's\""), bindings.map { it.command })
    }

    @Test
    fun turnsArgumentSeparatorIntoCommandSeparator() {
        val bindings = parseListKeys(
            listOf(
                """bind-key -T root MouseDown1Pane         select-pane -t = \; send-keys -M""",
                """bind-key -T prefix q send-keys -l '\;' \; display-message done""",
            ),
        )
        assertEquals("select-pane -t = ; send-keys -M", bindings[0].command)
        assertEquals("send-keys -l '\\;' ; display-message done", bindings[1].command)
    }

    @Test
    fun mouseKeysParseLikeAnyOther() {
        val bindings = parseListKeys(
            listOf("""bind-key -T root WheelUpPane            if-shell -F "#{||:#{pane_in_mode},#{mouse_any_flag}}" { send-keys -M } { copy-mode -e }"""),
        )
        assertEquals(1, bindings.size)
        assertEquals("WheelUpPane", bindings[0].key)
        assertEquals("root", bindings[0].table)
    }

    @Test
    fun skipsLinesThatAreNotBindings() {
        val bindings = parseListKeys(
            listOf(
                "",
                "C-b     Send the prefix key",
                "bind-key -T prefix",
                "bind-key    -T prefix c        new-window",
                "unbind-key -T prefix c",
            ),
        )
        assertEquals(listOf(TmuxKeyBinding("prefix", "c", "new-window", repeat = false)), bindings)
    }

    @Test
    fun repeatFlagIsOnlySetWhenPrinted() {
        val bindings = parseListKeys(
            listOf(
                "bind-key -r -T prefix Up       select-pane -U",
                "bind-key    -T prefix p        previous-window",
            ),
        )
        assertTrue(bindings[0].repeat)
        assertFalse(bindings[1].repeat)
    }
}
