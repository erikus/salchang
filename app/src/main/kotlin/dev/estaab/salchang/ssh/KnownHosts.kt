package dev.estaab.salchang.ssh

import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.OpenSSHKnownHosts
import java.io.File
import java.io.IOException
import java.security.PublicKey

/** Asked once per unknown host; return true to trust the key and persist it. */
fun interface HostKeyPrompt {
    suspend fun confirm(hostname: String, keyType: String, fingerprintSha256: String): Boolean
}

/** Base for host key failures so callers can `catch (e: HostKeyException)`. */
sealed class HostKeyException(
    message: String,
    val hostname: String,
    val keyType: String,
    val fingerprintSha256: String,
) : IOException(message)

/** The host presented a key of a type we have an entry for, but it differs. Never auto-accepted. */
class HostKeyMismatchException(hostname: String, keyType: String, fingerprintSha256: String) :
    HostKeyException("host key for $hostname changed ($keyType $fingerprintSha256)", hostname, keyType, fingerprintSha256)

/** The host was unknown and the user declined the fingerprint. */
class HostKeyRejectedException(hostname: String, keyType: String, fingerprintSha256: String) :
    HostKeyException("host key for $hostname rejected by user ($keyType $fingerprintSha256)", hostname, keyType, fingerprintSha256)

/**
 * Trust-on-first-use host key store backed by an OpenSSH `known_hosts` file.
 *
 * Create one per app (it is cheap), then call [createVerifier] per connection: the verifier reads
 * the file when created, so entries added or removed elsewhere are picked up by the next connect.
 */
class KnownHosts(private val file: File, private val prompt: HostKeyPrompt) {

    @Throws(IOException::class)
    fun createVerifier(): TofuHostKeyVerifier = TofuHostKeyVerifier(file, prompt)

    /** Raw `known_hosts` lines, for a settings screen. */
    fun lines(): List<String> = if (file.isFile) file.readLines().filter { it.isNotBlank() } else emptyList()

    /** Forgets every stored host key. */
    fun clear() {
        file.delete()
    }

    /**
     * Removes entries whose host field matches [hostname]:[port] (as sshj writes them). Returns
     * the number of lines removed.
     */
    fun forget(hostname: String, port: Int): Int {
        val hostField: String = hostFieldFor(hostname, port)
        val before: List<String> = lines()
        val after: List<String> = before.filterNot { line -> line.split(' ').firstOrNull() == hostField }
        if (after.size != before.size) file.writeText(after.joinToString("\n", postfix = if (after.isEmpty()) "" else "\n"))
        return before.size - after.size
    }

    companion object {
        private const val DEFAULT_SSH_PORT: Int = 22

        /** Mirrors sshj's `OpenSSHKnownHosts.adjustHostname`: `host` on port 22, `[host]:port` otherwise. */
        fun hostFieldFor(hostname: String, port: Int): String {
            val lower: String = hostname.lowercase()
            return if (port == DEFAULT_SSH_PORT) lower else "[$lower]:$port"
        }
    }
}

/**
 * sshj [net.schmizz.sshj.transport.verification.HostKeyVerifier] with TOFU semantics.
 *
 * sshj calls `verify` synchronously on its transport reader thread during key exchange, so the
 * suspendable [HostKeyPrompt] is bridged with [runBlocking]: that reader thread (not the caller
 * of `connect`) blocks until the user answers. The thread waiting in `SSHClient.connect` is bounded
 * by `SSHClient.setTimeout`, so the prompt must be answered within that window
 * (see `SshControlTransport.HANDSHAKE_TIMEOUT_MS`).
 *
 * `verify` can only return a boolean; sshj turns `false` into a generic `TransportException`
 * (reason `HOST_KEY_NOT_VERIFIABLE`). The specific reason is recorded here and re-thrown by
 * `SshControlTransport` via [takeFailure].
 */
class TofuHostKeyVerifier internal constructor(file: File, private val prompt: HostKeyPrompt) : OpenSSHKnownHosts(file) {

    @Volatile
    private var failure: HostKeyException? = null

    /** Returns and clears the reason the last `verify` returned false, if it was ours. */
    fun takeFailure(): HostKeyException? {
        val f: HostKeyException? = failure
        failure = null
        return f
    }

    override fun hostKeyUnverifiableAction(hostname: String, key: PublicKey): Boolean {
        val keyType: KeyType = KeyType.fromKey(key)
        val fingerprint: String = SshKeyFormat.fingerprintSha256(key)
        val accepted: Boolean = try {
            runBlocking { prompt.confirm(hostname, keyType.toString(), fingerprint) }
        } catch (e: Exception) {
            log.warn("host key prompt failed for {}", hostname, e)
            false
        }
        if (!accepted) {
            failure = HostKeyRejectedException(hostname, keyType.toString(), fingerprint)
            return false
        }
        try {
            write(HostEntry(null, hostname, keyType, key))
        } catch (e: IOException) {
            log.error("could not append {} to {}", hostname, file, e)
            // Still trust it for this session; the prompt will simply show again next time.
        }
        return true
    }

    override fun hostKeyChangedAction(hostname: String, key: PublicKey): Boolean {
        val keyType: KeyType = KeyType.fromKey(key)
        failure = HostKeyMismatchException(hostname, keyType.toString(), SshKeyFormat.fingerprintSha256(key))
        return false
    }
}
