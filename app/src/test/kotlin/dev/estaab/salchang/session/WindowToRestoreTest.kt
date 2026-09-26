package dev.estaab.salchang.session

import dev.estaab.salchang.tmuxctl.TmuxState
import dev.estaab.salchang.tmuxctl.TmuxWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WindowToRestoreTest {
    private fun window(id: String, index: Int, active: Boolean): TmuxWindow = TmuxWindow(
        id = id,
        index = index,
        name = "w$index",
        active = active,
        layout = "",
        activePaneId = null,
        panes = emptyList(),
        meta = emptyMap(),
        statusLabel = "",
    )

    private val state: TmuxState = TmuxState(
        sessionId = "\$1",
        sessionName = "main",
        activeWindowId = "@0",
        windows = listOf(window("@0", 0, active = true), window("@3", 1, active = false)),
    )

    @Test
    fun nothingRememberedMeansNothingToRestore() {
        assertNull(SessionController.windowToRestore(null, state))
    }

    @Test
    fun rememberedWindowThatStillExistsIsRestored() {
        assertEquals("@3", SessionController.windowToRestore("@3", state))
    }

    @Test
    fun rememberedWindowThatIsAlreadyCurrentIsNotReselected() {
        assertNull(SessionController.windowToRestore("@0", state))
    }

    @Test
    fun rememberedWindowThatWasClosedIsIgnored() {
        assertNull(SessionController.windowToRestore("@7", state))
    }

    @Test
    fun emptyStateRestoresNothing() {
        assertNull(SessionController.windowToRestore("@3", TmuxState.EMPTY))
    }
}
