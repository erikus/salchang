package dev.estaab.salchang.ssh

import dev.estaab.salchang.data.AuthMethod
import dev.estaab.salchang.data.HostProfile
import dev.estaab.salchang.data.KeyStore
import dev.estaab.salchang.tmuxctl.ControlTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.keepalive.KeepAlive
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.keepalive.KeepAliveRunner
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session
import net.schmizz.sshj.transport.TransportException
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.method.AuthNone
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext

/** Name of the short-lived thread [SshControlTransport.close] runs the sshj shutdown on. */
private const val CLOSE_THREAD_NAME: String = "salchang-ssh-close"

/** A one-off remote command exited non-zero. */
class RemoteCommandFailedException(val command: String, val exitStatus: Int?, val stderr: String) :
    IOException("`$command` exited with status $exitStatus: ${stderr.trim()}")

/** One line of `tmux list-sessions` as parsed by [SshControlTransport.parseListSessions]. */
data class RemoteTmuxSession(
    /** Session group name, null for an ungrouped session. */
    val group: String?,
    val name: String,
    val windows: Int,
    /** Number of clients attached to this session. */
    val attached: Int,
)

/**
 * Something `tmux new-session -t <name>` can join: a session group (all its members share the
 * windows) or a single ungrouped session. Derived from [RemoteTmuxSession]s by
 * [SshControlTransport.attachTargets].
 */
data class TmuxAttachTarget(
    /** What to put in `HostProfile.tmuxSession`. */
    val name: String,
    /** True for a group; false for an ungrouped session. */
    val grouped: Boolean,
    /** Sessions in the group (1 for an ungrouped session). */
    val sessions: Int,
    val windows: Int,
)

/**
 * [ControlTransport] over an sshj exec channel running `tmux -C`.
 *
 * Failure modes of [connect], for the UI:
 * - `com.hierynomus.sshj.common.KeyDecryptionFailedException`: wrong/missing key passphrase
 *   (raised before any network I/O).
 * - `java.net.UnknownHostException`, `java.net.ConnectException`, `java.net.SocketTimeoutException`:
 *   network / DNS / TCP problems (plain `IOException`s, not `SSHException`s).
 * - [HostKeyMismatchException] / [HostKeyRejectedException]: host key problems.
 * - `net.schmizz.sshj.userauth.UserAuthException`: server refused the key, or (for
 *   [AuthMethod.NONE]) does not accept the `none` method, i.e. it is not Tailscale SSH.
 * - `net.schmizz.sshj.transport.TransportException`: other protocol failures / connection lost.
 * - `net.schmizz.sshj.connection.ConnectionException`: session channel / exec request refused.
 */
class SshControlTransport private constructor(
    private val client: SSHClient,
    private val session: Session,
    private val command: Session.Command,
) : ControlTransport {

    /** stdout of the remote tmux control client. */
    override val input: InputStream = command.inputStream

    /** stdin of the remote tmux control client. */
    override val output: OutputStream = command.outputStream

    /** stderr of the remote tmux control client (e.g. "no server running on ..."). */
    val errorStream: InputStream = command.errorStream

    /** Exit status once the remote command has finished, null while it runs. */
    val exitStatus: Int? get() = command.exitStatus

    val isOpen: Boolean get() = command.isOpen && client.isConnected

    /**
     * The SSH auth banner (`SSH_MSG_USERAUTH_BANNER`) the server sent during authentication,
     * null if none. Tailscale SSH in check mode puts the approval URL here.
     */
    val authBanner: String? get() = client.userAuth.banner

    private val closed = AtomicBoolean(false)

    /**
     * Idempotent and asynchronous: sshj writes the channel-close and disconnect packets on the
     * calling thread, which would be a `NetworkOnMainThreadException` from the UI, so the
     * shutdown runs on a daemon thread. Close errors are swallowed because the peer is usually
     * already gone.
     */
    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val closer = Thread({
            closeQuietly(command)
            closeQuietly(session)
            closeQuietly(client)
        }, CLOSE_THREAD_NAME)
        closer.isDaemon = true
        closer.start()
    }

    companion object {
        /** TCP connect timeout. */
        const val CONNECT_TIMEOUT_MS: Int = 15_000

        /**
         * Bound on each protocol step (key exchange, auth, channel open) and the socket read
         * timeout. It also bounds how long the host-key prompt may stay open, because the KEX
         * waits on it; see [TofuHostKeyVerifier]. With [AuthMethod.NONE] against Tailscale SSH
         * in check mode it likewise bounds how long the user has to visit the approval URL,
         * because the server holds the auth request open until then. Idle reads time out
         * harmlessly (sshj's reader loops on `SocketTimeoutException`), liveness comes from the
         * keepalive below.
         */
        const val HANDSHAKE_TIMEOUT_MS: Int = 120_000

        /**
         * How often [authBanner] is sampled while the blocking auth call runs, so a check-mode
         * URL reaches the UI while the server is still waiting for approval.
         */
        const val AUTH_BANNER_POLL_MS: Long = 250

        /** Interval between `keepalive@openssh.com` global requests. */
        const val KEEPALIVE_SECONDS: Int = 15

        /** Unanswered keepalives before sshj declares the connection dead. */
        const val KEEPALIVE_MAX_MISSED: Int = 4

        /** How long [runOnce] waits for the remote command to exit after stdout hits EOF. */
        const val RUN_ONCE_EXIT_WAIT_MS: Long = 10_000

        private const val TMUX_CONTROL_FLAG: String = "-C"
        private const val TMUX_SOCKET_NAME_FLAG: String = "-L"
        private const val TMUX_SOCKET_PATH_FLAG: String = "-S"
        private const val TMUX_NEW_SESSION: String = "new-session"
        private const val TMUX_TARGET_FLAG: String = "-t"
        private const val TMUX_LIST_SESSIONS: String = "list-sessions"
        private const val TMUX_FORMAT_FLAG: String = "-F"

        /** Column separator of [LIST_SESSIONS_FORMAT]; a literal tab, which tmux passes through. */
        private const val LIST_SESSIONS_SEPARATOR: Char = '\t'

        /** `-F` format for [buildListSessionsCommand]; columns match [RemoteTmuxSession] in order. */
        const val LIST_SESSIONS_FORMAT: String =
            "#{session_group}" + LIST_SESSIONS_SEPARATOR + "#{session_name}" + LIST_SESSIONS_SEPARATOR +
                "#{session_windows}" + LIST_SESSIONS_SEPARATOR + "#{session_attached}"
        private const val LIST_SESSIONS_COLUMNS: Int = 4

        /**
         * Substrings of tmux's stderr that mean "there is no server on this socket" (tmux 3.4:
         * `no server running on <path>`, `error connecting to <path> (No such file or directory)`,
         * and the transient `server exited unexpectedly` right after a `kill-server`).
         */
        private val NO_SERVER_MARKERS: List<String> = listOf("no server running", "error connecting", "server exited unexpectedly")

        /**
         * Connects, authenticates per [HostProfile.authMethod] and starts `tmux -C new-session -t <session>`.
         * Runs on [Dispatchers.IO]. On any failure the client is closed before the exception propagates.
         * [onAuthBanner] is called at most once, from an IO thread, with the auth banner as soon as
         * the server sends one (see [authBanner]).
         *
         * The blocking sshj calls cannot observe cancellation, so the block runs [NonCancellable]
         * (a cancelling `withContext` would otherwise discard the connected transport, leaking the
         * SSH client and the grouped tmux session it already created); cancellation is honoured
         * afterwards by closing the transport and rethrowing.
         */
        suspend fun connect(
            profile: HostProfile,
            keyStore: KeyStore,
            passphrase: CharArray?,
            knownHosts: KnownHosts,
            onAuthBanner: ((String) -> Unit)? = null,
        ): SshControlTransport {
            val transport: SshControlTransport = withContext(Dispatchers.IO + NonCancellable) {
                val client: SSHClient = openAuthenticatedClient(profile, keyStore, passphrase, knownHosts, onAuthBanner)
                try {
                    val session: Session = client.startSession()
                    val command: Session.Command = session.exec(buildTmuxCommand(profile))
                    SshControlTransport(client, session, command)
                } catch (e: Throwable) {
                    closeQuietly(client)
                    throw e
                }
            }
            if (!coroutineContext.isActive) transport.close()
            coroutineContext.ensureActive()
            return transport
        }

        /**
         * Connects, runs [command] on the remote login shell, returns its stdout, and disconnects.
         * Meant for diagnostics such as `tmux -V` and the host editor's session list
         * ([buildListSessionsCommand]). Throws [RemoteCommandFailedException] on a
         * non-zero exit status.
         */
        suspend fun runOnce(
            profile: HostProfile,
            keyStore: KeyStore,
            passphrase: CharArray?,
            knownHosts: KnownHosts,
            command: String,
            onAuthBanner: ((String) -> Unit)? = null,
        ): String = withContext(Dispatchers.IO) {
            val client: SSHClient = openAuthenticatedClient(profile, keyStore, passphrase, knownHosts, onAuthBanner)
            try {
                client.startSession().use { session ->
                    val cmd: Session.Command = session.exec(command)
                    val stdout: String = cmd.inputStream.readBytes().toString(Charsets.UTF_8)
                    val stderr: String = cmd.errorStream.readBytes().toString(Charsets.UTF_8)
                    cmd.join(RUN_ONCE_EXIT_WAIT_MS, TimeUnit.MILLISECONDS)
                    val status: Int? = cmd.exitStatus
                    if (status != 0) throw RemoteCommandFailedException(command, status, stderr)
                    stdout
                }
            } finally {
                closeQuietly(client)
            }
        }

        /**
         * `<tmuxBinary> [-L <name> | -S <path>] -C new-session -t <session>`, every argument
         * single-quoted for the remote POSIX shell. Pure.
         */
        fun buildTmuxCommand(profile: HostProfile): String {
            val args: MutableList<String> = tmuxBaseArgs(profile)
            args += TMUX_CONTROL_FLAG
            args += TMUX_NEW_SESSION
            args += TMUX_TARGET_FLAG
            args += profile.tmuxSession
            return args.joinToString(" ") { shellQuote(it) }
        }

        /**
         * `<tmuxBinary> [-L <name> | -S <path>] list-sessions -F '<LIST_SESSIONS_FORMAT>'` for
         * [runOnce]; parse the stdout with [parseListSessions]. Single-quoting keeps the `#` of
         * the format out of the remote shell's hands. Pure.
         */
        fun buildListSessionsCommand(profile: HostProfile): String {
            val args: MutableList<String> = tmuxBaseArgs(profile)
            args += TMUX_LIST_SESSIONS
            args += TMUX_FORMAT_FLAG
            args += LIST_SESSIONS_FORMAT
            return args.joinToString(" ") { shellQuote(it) }
        }

        /** `<tmuxBinary>` plus the socket flag shared by every tmux invocation for [profile]. */
        private fun tmuxBaseArgs(profile: HostProfile): MutableList<String> {
            val args: MutableList<String> = mutableListOf(profile.tmuxBinary)
            val socketPath: String? = profile.tmuxSocketPath
            val socketName: String? = profile.tmuxSocketName
            if (!socketPath.isNullOrEmpty()) {
                args += TMUX_SOCKET_PATH_FLAG
                args += socketPath
            } else if (!socketName.isNullOrEmpty()) {
                args += TMUX_SOCKET_NAME_FLAG
                args += socketName
            }
            return args
        }

        /**
         * Parses the stdout of [buildListSessionsCommand]. Blank lines are skipped; an empty
         * group column becomes null. Throws [IllegalArgumentException] on a line that does not
         * have exactly the expected columns or non-numeric counts. Pure.
         */
        fun parseListSessions(stdout: String): List<RemoteTmuxSession> =
            stdout.lineSequence().filter { it.isNotBlank() }.map { line ->
                val columns: List<String> = line.split(LIST_SESSIONS_SEPARATOR)
                require(columns.size == LIST_SESSIONS_COLUMNS) {
                    "expected $LIST_SESSIONS_COLUMNS tab-separated columns from list-sessions, got ${columns.size}: `$line`"
                }
                RemoteTmuxSession(
                    group = columns[0].ifEmpty { null },
                    name = columns[1],
                    windows = requireNotNull(columns[2].toIntOrNull()) { "bad session_windows in `$line`" },
                    attached = requireNotNull(columns[3].toIntOrNull()) { "bad session_attached in `$line`" },
                )
            }.toList()

        /**
         * Collapses [sessions] into what `new-session -t` accepts: one target per group (members
         * share windows, so the window count is that of any member) followed by one per
         * ungrouped session, each list in first-seen order. Pure.
         */
        fun attachTargets(sessions: List<RemoteTmuxSession>): List<TmuxAttachTarget> {
            val groups: LinkedHashMap<String, MutableList<RemoteTmuxSession>> = LinkedHashMap()
            val ungrouped: MutableList<RemoteTmuxSession> = ArrayList()
            for (session: RemoteTmuxSession in sessions) {
                val group: String? = session.group
                if (group == null) ungrouped += session else groups.getOrPut(group) { ArrayList() } += session
            }
            val targets: MutableList<TmuxAttachTarget> = ArrayList()
            for ((group: String, members: MutableList<RemoteTmuxSession>) in groups) {
                targets += TmuxAttachTarget(name = group, grouped = true, sessions = members.size, windows = members.first().windows)
            }
            for (session: RemoteTmuxSession in ungrouped) {
                targets += TmuxAttachTarget(name = session.name, grouped = false, sessions = 1, windows = session.windows)
            }
            return targets
        }

        /** True if [e] is tmux reporting that no server is listening on the profile's socket. */
        fun isNoServerError(e: RemoteCommandFailedException): Boolean =
            NO_SERVER_MARKERS.any { marker -> e.stderr.contains(marker) }

        /** POSIX single-quoting: `'` becomes `'\''`, everything else is literal. */
        fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

        private suspend fun openAuthenticatedClient(
            profile: HostProfile,
            keyStore: KeyStore,
            passphrase: CharArray?,
            knownHosts: KnownHosts,
            onAuthBanner: ((String) -> Unit)?,
        ): SSHClient {
            val keyProvider: KeyProvider? = when (profile.authMethod) {
                AuthMethod.NONE -> null
                AuthMethod.KEY -> {
                    val keyId: String = profile.keyId
                        ?: throw IllegalArgumentException("host '${profile.name}' uses key authentication but has no key selected")
                    keyStore.keyProvider(keyId, passphrase).also {
                        // Decrypt now so a bad passphrase fails before we touch the network.
                        it.getPrivate()
                    }
                }
            }

            val verifier: TofuHostKeyVerifier = knownHosts.createVerifier()
            val config = DefaultConfig()
            config.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE
            val client = SSHClient(config)
            client.addHostKeyVerifier(verifier)
            client.connectTimeout = CONNECT_TIMEOUT_MS
            client.timeout = HANDSHAKE_TIMEOUT_MS
            // Must be configured before connect(): sshj only starts the keepalive thread then.
            val keepAlive: KeepAlive = client.connection.keepAlive
            keepAlive.keepAliveInterval = KEEPALIVE_SECONDS
            (keepAlive as? KeepAliveRunner)?.maxAliveCount = KEEPALIVE_MAX_MISSED

            try {
                try {
                    client.connect(profile.hostname, profile.port)
                } catch (e: TransportException) {
                    throw verifier.takeFailure() ?: e
                }
                authenticate(client, profile.username, keyProvider, onAuthBanner)
            } catch (e: Throwable) {
                closeQuietly(client)
                throw e
            }
            return client
        }

        /**
         * Runs the blocking sshj auth (publickey with [keyProvider], `none` without) while a
         * sibling coroutine samples the banner every [AUTH_BANNER_POLL_MS] and reports it once.
         * A banner that arrived right before auth finished is reported after the fact, so
         * [onAuthBanner] sees it exactly once either way.
         */
        private suspend fun authenticate(
            client: SSHClient,
            username: String,
            keyProvider: KeyProvider?,
            onAuthBanner: ((String) -> Unit)?,
        ) {
            if (onAuthBanner == null) {
                authenticateBlocking(client, username, keyProvider)
                return
            }
            val reported = AtomicBoolean(false)
            fun reportBannerOnce() {
                if (reported.get()) return
                val banner: String = client.userAuth.banner?.takeIf { it.isNotBlank() } ?: return
                if (reported.compareAndSet(false, true)) onAuthBanner(banner)
            }
            coroutineScope {
                val poller: Job = launch {
                    while (!reported.get()) {
                        delay(AUTH_BANNER_POLL_MS)
                        reportBannerOnce()
                    }
                }
                try {
                    authenticateBlocking(client, username, keyProvider)
                } finally {
                    poller.cancel()
                }
            }
            reportBannerOnce()
        }

        private fun authenticateBlocking(client: SSHClient, username: String, keyProvider: KeyProvider?) {
            if (keyProvider == null) client.auth(username, AuthNone()) else client.authPublickey(username, keyProvider)
        }

        private fun closeQuietly(closeable: AutoCloseable?) {
            if (closeable == null) return
            try {
                closeable.close()
            } catch (e: Exception) {
                // Peer is usually already gone; nothing useful to do.
            }
        }
    }
}
