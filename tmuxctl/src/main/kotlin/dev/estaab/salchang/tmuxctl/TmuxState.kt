package dev.estaab.salchang.tmuxctl

/**
 * Snapshot of the tmux session the control client is attached to. Rebuilt from
 * `list-windows` / `list-panes -s` on [TmuxControlClient.refresh] and patched
 * incrementally from notifications in between.
 */
data class TmuxState(
    val sessionId: String?,
    val sessionName: String?,
    val activeWindowId: String?,
    /** Sorted by [TmuxWindow.index]. */
    val windows: List<TmuxWindow>,
) {
    fun window(windowId: String): TmuxWindow? = windows.firstOrNull { it.id == windowId }

    fun pane(paneId: String): TmuxPane? {
        for (w in windows) for (p in w.panes) if (p.id == paneId) return p
        return null
    }

    companion object {
        val EMPTY: TmuxState = TmuxState(sessionId = null, sessionName = null, activeWindowId = null, windows = emptyList())
    }
}

/** One tmux window (`@N`) in the session. */
data class TmuxWindow(
    val id: String,
    val index: Int,
    val name: String,
    val active: Boolean,
    /** Raw `#{window_layout}`; see [parseLayout] / [paneSizes]. */
    val layout: String,
    val activePaneId: String?,
    val panes: List<TmuxPane>,
    /** Raw `@salchang_meta` value keyed by pane id (`%N`); panes without a value are absent. */
    val meta: Map<String, String>,
    /**
     * The server's `window-status-format` expanded for this window (`#{T:window-status-format}`),
     * exactly as tmux would draw it in its own status line, style directives included. Empty when
     * the server could not expand it (tmux < 3.2). Use [tabLabel] for display.
     */
    val statusLabel: String,
) {
    /**
     * Text for a tab representing this window: [statusLabel] with `#[...]` style directives
     * removed and surrounding whitespace trimmed, or `index:name` (tmux's default format without
     * flags) when the label is empty.
     */
    fun tabLabel(): String = stripStyleDirectives(statusLabel).trim().ifEmpty { "$index:$name" }
}

/** One tmux pane (`%N`). */
data class TmuxPane(
    val id: String,
    val windowId: String,
    val width: Int,
    val height: Int,
    val active: Boolean,
    val currentCommand: String,
    val title: String,
)
