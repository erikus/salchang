package dev.estaab.salchang.tmuxctl

import java.io.InputStream
import java.io.OutputStream

/**
 * A bidirectional byte stream carrying a tmux control-mode conversation
 * (`tmux -C ...`). Implementations: an SSH exec channel in the app, a local
 * process in tests.
 */
interface ControlTransport {
    /** Bytes written by tmux (notifications and command output blocks). */
    val input: InputStream

    /** Bytes sent to tmux (newline-terminated command lines). */
    val output: OutputStream

    /** Tears down the underlying channel; idempotent. */
    fun close()
}
