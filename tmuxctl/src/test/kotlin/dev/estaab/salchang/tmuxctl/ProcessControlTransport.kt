package dev.estaab.salchang.tmuxctl

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

private const val PROCESS_EXIT_WAIT_SECONDS: Long = 3L

/**
 * [ControlTransport] over a local child process (for tests: `tmux -C ...`).
 * stderr goes to [stderrFile]; [extraEnv] is added to and [removedEnv] dropped from the environment.
 */
class ProcessControlTransport(
    command: List<String>,
    extraEnv: Map<String, String>,
    removedEnv: List<String>,
    stderrFile: File,
) : ControlTransport {
    private val process: Process = ProcessBuilder(command).apply {
        environment().putAll(extraEnv)
        removedEnv.forEach { environment().remove(it) }
        redirectError(ProcessBuilder.Redirect.to(stderrFile))
    }.start()

    override val input: InputStream = process.inputStream
    override val output: OutputStream = process.outputStream

    val isAlive: Boolean get() = process.isAlive

    override fun close() {
        try {
            output.close()
        } catch (_: IOException) {
        }
        try {
            input.close()
        } catch (_: IOException) {
        }
        process.destroy()
        if (!process.waitFor(PROCESS_EXIT_WAIT_SECONDS, TimeUnit.SECONDS)) process.destroyForcibly()
    }
}
