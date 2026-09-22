package dev.estaab.salchang.tmuxctl

import org.junit.Assert.assertEquals
import org.junit.Test

class TmuxFormatTest {
    private fun window(statusLabel: String, index: Int = 1, name: String = "bash"): TmuxWindow = TmuxWindow(
        id = "@$index",
        index = index,
        name = name,
        active = false,
        layout = "",
        activePaneId = null,
        panes = emptyList(),
        meta = emptyMap(),
        statusLabel = statusLabel,
    )

    @Test
    fun stripsStyleDirectives() {
        assertEquals("1:🧠salchang* ", stripStyleDirectives("#[fg=black,bold]1:🧠salchang* "))
        assertEquals("a b", stripStyleDirectives("#[default]a#[fg=colour114,bg=black,nobold] #[align=right]b#[none]"))
        assertEquals("plain", stripStyleDirectives("plain"))
        assertEquals("", stripStyleDirectives("#[fg=red]"))
    }

    @Test
    fun keepsUnterminatedDirectiveAndLiteralHashes() {
        assertEquals("#[fg=red", stripStyleDirectives("#[fg=red"))
        assertEquals("issue #12 [x]", stripStyleDirectives("issue #12 [x]"))
    }

    @Test
    fun tabLabelStripsTrimsAndFallsBack() {
        assertEquals("1:🧠salchang*", window("#[fg=black,bold]1:🧠salchang* ").tabLabel())
        assertEquals("2:~-", window("2:~- ", index = 2).tabLabel())
        // tmux < 3.2 expands #{T:...} to nothing: fall back to tmux's default `#I:#W`.
        assertEquals("3:vim", window("", index = 3, name = "vim").tabLabel())
        assertEquals("3:vim", window("#[fg=red]  ", index = 3, name = "vim").tabLabel())
    }
}
