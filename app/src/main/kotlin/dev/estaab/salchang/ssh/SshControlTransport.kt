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
         * Meant for diagnostics such as `tmux -V`. Throws [RemoteCommandFailedException] on a
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
            args += TMUX_CONTROL_FLAG
            args += TMUX_NEW_SESSION
            args += TMUX_TARGET_FLAG
            args += profile.tmuxSession
            return args.joinToString(" ") { shellQuote(it) }
        }

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
