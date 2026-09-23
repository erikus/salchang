package dev.estaab.salchang.session

import android.util.Log
import dev.estaab.salchang.data.HostProfile
import dev.estaab.salchang.ssh.RemoteScript
import dev.estaab.salchang.ssh.SshControlTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG: String = "AgentProber"

/** Asset name of the probe script (the `remote/` directory is the app's asset root). */
const val PROBE_ASSET_NAME: String = "salchang-probe"

/** Seconds between probes, passed as `--interval`. */
private const val PROBE_INTERVAL_SECONDS: Int = 3

/** Backoff before restarting the probe after an unexpected exit: starts here, doubles, caps at the max. */
private const val RESTART_BACKOFF_INITIAL_MS: Long = 2_000L
private const val RESTART_BACKOFF_MAX_MS: Long = 60_000L
private const val RESTART_BACKOFF_FACTOR: Long = 2L

/** A run that survived at least this long resets the backoff to its initial value. */
private const val STABLE_RUN_MS: Long = RESTART_BACKOFF_MAX_MS

/** The probe's exit status for a missing `tmux` or `jq`; the message is on stderr and we do not restart. */
const val PROBE_EXIT_MISSING_DEPENDENCY: Int = 2

/** How long to wait for the exit status after the probe's stdout hit EOF. */
private const val EXIT_WAIT_MS: Long = 5_000L

/** Leading bytes of the probe's stderr kept per run, for the dependency-error hint and logs. */
private const val STDERR_KEEP_BYTES: Int = 4_096

private const val PROBE_INTERVAL_FLAG: String = "--interval"

/**
 * Runs `remote/salchang-probe` on a second exec channel of the connection that carries tmux
 * control mode (see scratch/probe-spec.md section 2). The script discovers coding agents in the
 * user's tmux panes and writes `@salchang_meta`, which the control client already subscribes to,
 * so nothing here feeds the UI directly except [dependencyError].
 *
 * Lifecycle: [start] launches one IO coroutine that loads the script bytes once and then loops:
 * start the script, drain its stdout (heartbeats, logged at debug) and stderr until EOF, read
 * the exit status. Exit [PROBE_EXIT_MISSING_DEPENDENCY] publishes the stderr text to
 * [dependencyError] and ends the loop; any other exit (or a failure to open the channel) is
 * retried after an exponential backoff while the transport is still open. [stop] cancels the
 * loop and closes the live channel, which is what makes a blocked read return.
 */
class AgentProber(
    private val transport: SshControlTransport,
    /** Reads the script bytes; called once, on [Dispatchers.IO]. */
    private val loadScript: () -> ByteArray,
    /** Arguments after `sh -s --`; see [probeArgs]. */
    private val args: List<String>,
    private val scope: CoroutineScope,
) {
    private val _dependencyError: MutableStateFlow<String?> = MutableStateFlow(null)
    /** The probe's stderr after it exited with [PROBE_EXIT_MISSING_DEPENDENCY]; null otherwise. */
    val dependencyError: StateFlow<String?> = _dependencyError.asStateFlow()

    private val stopped = AtomicBoolean(false)
    private val current: AtomicReference<RemoteScript?> = AtomicReference(null)
    private var job: Job? = null

    /** Starts the loop; call once. */
    fun start() {
        check(job == null) { "AgentProber already started" }
        job = scope.launch(Dispatchers.IO) { runLoop() }
    }

    /** Stops the loop and closes the running channel. Idempotent; safe from any thread. */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        job?.cancel()
        current.getAndSet(null)?.close()
    }

    private suspend fun runLoop() {
        val script: ByteArray = try {
            loadScript()
        } catch (e: IOException) {
            Log.e(LOG_TAG, "cannot load $PROBE_ASSET_NAME; agent detection disabled", e)
            return
        }
        var backoffMs: Long = RESTART_BACKOFF_INITIAL_MS
        while (currentCoroutineContext().isActive && !stopped.get()) {
            val startedNanos: Long = System.nanoTime()
            val outcome: Outcome = runOnce(script)
            if (stopped.get() || !currentCoroutineContext().isActive) return
            when (outcome) {
                is Outcome.MissingDependency -> {
                    Log.w(LOG_TAG, "probe reported a missing dependency, not restarting: ${outcome.stderr}")
                    _dependencyError.value = outcome.stderr
                    return
                }
                is Outcome.Exited -> Log.w(LOG_TAG, "probe ended (${outcome.reason}); restarting in $backoffMs ms")
            }
            if (!transport.isOpen) {
                Log.d(LOG_TAG, "transport closed; not restarting the probe")
                return
            }
            val ranForMs: Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos)
            if (ranForMs >= STABLE_RUN_MS) backoffMs = RESTART_BACKOFF_INITIAL_MS
            delay(backoffMs)
            backoffMs = nextBackoff(backoffMs)
        }
    }

    /** One run of the script from channel open to exit; never throws except for cancellation. */
    private suspend fun runOnce(script: ByteArray): Outcome {
        val handle: RemoteScript = try {
            transport.startScript(script, args)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            return Outcome.Exited("could not start: ${e.message}")
        }
        current.set(handle)
        if (stopped.get()) {
            // stop() raced with startScript(); it may have missed this handle.
            current.compareAndSet(handle, null)
            handle.close()
            return Outcome.Exited("stopped")
        }
        Log.d(LOG_TAG, "probe started: ${handle.commandLine}")
        try {
            val stderr = StringBuilder()
            coroutineScope {
                launch { drainStderr(handle.errorStream, stderr) }
                drainStdout(handle.input)
            }
            handle.join(EXIT_WAIT_MS)
            val status: Int? = handle.exitStatus
            val stderrText: String = stderr.toString().trim()
            return if (status == PROBE_EXIT_MISSING_DEPENDENCY) {
                Outcome.MissingDependency(stderrText)
            } else {
                Outcome.Exited(describeExit(status, stderrText))
            }
        } finally {
            current.compareAndSet(handle, null)
            handle.close()
        }
    }

    private sealed interface Outcome {
        /** Exit [PROBE_EXIT_MISSING_DEPENDENCY]; [stderr] is the script's explanation. */
        data class MissingDependency(val stderr: String) : Outcome

        /** Any other end of a run, including failing to start it. */
        data class Exited(val reason: String) : Outcome
    }

    companion object {
        /**
         * `[-L <name> | -S <path>] --interval <seconds>`: the profile's tmux socket flags exactly as
         * the control-mode command line passes them, then the probe interval. Pure.
         */
        fun probeArgs(profile: HostProfile): List<String> =
            SshControlTransport.tmuxSocketArgs(profile) + listOf(PROBE_INTERVAL_FLAG, PROBE_INTERVAL_SECONDS.toString())

        /** The backoff to use after [currentMs]: doubled, capped at the max. Pure. */
        fun nextBackoff(currentMs: Long): Long = minOf(currentMs * RESTART_BACKOFF_FACTOR, RESTART_BACKOFF_MAX_MS)

        private fun describeExit(status: Int?, stderr: String): String {
            val exit: String = if (status == null) "channel closed before exit status" else "exit status $status"
            return if (stderr.isEmpty()) exit else "$exit: $stderr"
        }

        /** Reads heartbeat lines until EOF; each is logged at debug level. Blocking. */
        private fun drainStdout(stream: InputStream) {
            try {
                val reader: BufferedReader = stream.bufferedReader(Charsets.UTF_8)
                while (true) {
                    val line: String = reader.readLine() ?: break
                    Log.d(LOG_TAG, line)
                }
            } catch (e: IOException) {
                Log.d(LOG_TAG, "probe stdout closed: ${e.message}")
            }
        }

        /** Reads stderr until EOF, keeping the first [STDERR_KEEP_BYTES] bytes in [into]. Blocking. */
        private fun drainStderr(stream: InputStream, into: StringBuilder) {
            try {
                val buffer = ByteArray(STDERR_KEEP_BYTES)
                var kept = 0
                while (true) {
                    val n: Int = stream.read(buffer)
                    if (n < 0) break
                    val room: Int = STDERR_KEEP_BYTES - kept
                    if (room > 0) {
                        val take: Int = minOf(n, room)
                        into.append(String(buffer, 0, take, Charsets.UTF_8))
                        kept += take
                    }
                }
            } catch (e: IOException) {
                Log.d(LOG_TAG, "probe stderr closed: ${e.message}")
            }
        }
    }
}
