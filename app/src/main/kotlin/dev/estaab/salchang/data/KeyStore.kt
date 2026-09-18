package dev.estaab.salchang.data

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import dev.estaab.salchang.ssh.SshKeyFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.schmizz.sshj.userauth.keyprovider.FileKeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyFormat
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.keyprovider.KeyProviderUtil
import net.schmizz.sshj.userauth.keyprovider.OpenSSHKeyFile
import net.schmizz.sshj.userauth.keyprovider.PKCS8KeyFile
import net.schmizz.sshj.userauth.keyprovider.PuTTYKeyFile
import net.schmizz.sshj.userauth.password.PasswordFinder
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.AsymmetricCipherKeyPair
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.io.File
import java.io.IOException
import java.security.PublicKey
import java.security.SecureRandom
import java.util.UUID

/** Thrown by [KeyStore.importFromText] when the text is not a usable private key. */
class KeyImportException(message: String, cause: Throwable? = null) : IOException(message, cause)

@Serializable
data class KeyInfo(
    val id: String,
    val name: String,
    /** `authorized_keys` line for this key (`<type> <base64> <name>`). */
    val publicKeyOpenSsh: String,
    /** `SHA256:<base64 no padding>` as printed by `ssh-keygen -lf`. */
    val fingerprintSha256: String,
    /** True if the private key file needs a passphrase. */
    val encrypted: Boolean,
)

/**
 * Stores SSH private keys as text files under [keysDir] (`<keyId>` = private key file as given or
 * generated, `<keyId>.json` = [KeyInfo] sidecar). Takes a plain directory rather than a Context so
 * it runs in JVM unit tests.
 *
 * Supported private key formats: `openssh-key-v1` (encrypted or not), legacy PEM (`RSA/EC/DSA
 * PRIVATE KEY`), PKCS#8, PuTTY. For encrypted keys other than `openssh-key-v1` the public key is
 * only recoverable by decrypting, so [importFromText] needs the passphrase for those.
 */
class KeyStore(private val keysDir: File) {

    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val random: SecureRandom = SecureRandom()

    fun list(): List<KeyInfo> {
        val files: Array<File> = keysDir.listFiles { f -> f.isFile && f.name.endsWith(META_SUFFIX) } ?: return emptyList()
        return files.mapNotNull { readMeta(it) }.sortedBy { it.name.lowercase() }
    }

    fun get(id: String): KeyInfo? = readMeta(metaFile(id))

    /** The private key file contents; the UI should not normally need this. */
    @Throws(IOException::class)
    fun privateKeyText(id: String): String {
        val file: File = keyFile(id)
        if (!file.isFile) throw IOException("no key with id $id")
        return file.readText(Charsets.UTF_8)
    }

    /**
     * Validates [pemText], stores it verbatim, and returns its metadata. [passphrase] is only
     * needed for encrypted keys that are not `openssh-key-v1`; when given for an encrypted key of
     * any format it is also used to verify the key actually decrypts.
     */
    @Throws(KeyImportException::class)
    fun importFromText(name: String, pemText: String, passphrase: CharArray? = null): KeyInfo {
        val text: String = pemText.trim() + "\n"
        val format: KeyFormat = try {
            KeyProviderUtil.detectKeyFileFormat(text, false)
        } catch (e: IOException) {
            throw KeyImportException("unrecognised key format", e)
        }
        if (format == KeyFormat.Unknown) throw KeyImportException("unrecognised key format")

        val encrypted: Boolean = when (format) {
            KeyFormat.OpenSSHv1 -> SshKeyFormat.readOpenSshV1Header(text).encrypted
            else -> LEGACY_ENCRYPTED_MARKERS.any { text.contains(it) }
        }

        val publicKeyBlob: ByteArray = if (format == KeyFormat.OpenSSHv1 && (!encrypted || passphrase == null)) {
            // Public key lives in the clear header; parse fully only when we can.
            if (!encrypted) parsePublicKeyOrThrow(text, format, null)
            SshKeyFormat.readOpenSshV1Header(text).publicKeyBlob
        } else {
            if (encrypted && passphrase == null) {
                throw KeyImportException("key is encrypted; passphrase required to import this format")
            }
            SshKeyFormat.publicKeyBlob(parsePublicKeyOrThrow(text, format, passphrase))
        }

        val info = KeyInfo(
            id = newId(),
            name = name,
            publicKeyOpenSsh = SshKeyFormat.publicKeyLine(publicKeyBlob, name),
            fingerprintSha256 = SshKeyFormat.fingerprintSha256(publicKeyBlob),
            encrypted = encrypted,
        )
        write(info, text)
        return info
    }

    /** Generates a new unencrypted ed25519 key (BouncyCastle) and stores it in `openssh-key-v1` format. */
    fun generateEd25519(name: String): KeyInfo {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(random))
        val pair: AsymmetricCipherKeyPair = generator.generateKeyPair()
        val seed: ByteArray = (pair.private as Ed25519PrivateKeyParameters).encoded
        val rawPublic: ByteArray = (pair.public as Ed25519PublicKeyParameters).encoded

        val pem: String = SshKeyFormat.encodeOpenSshV1Ed25519(seed, rawPublic, name, random.nextInt())
        val publicKeyBlob: ByteArray = SshKeyFormat.ed25519PublicKeyBlob(rawPublic)
        val info = KeyInfo(
            id = newId(),
            name = name,
            publicKeyOpenSsh = SshKeyFormat.publicKeyLine(publicKeyBlob, name),
            fingerprintSha256 = SshKeyFormat.fingerprintSha256(publicKeyBlob),
            encrypted = false,
        )
        write(info, pem)
        seed.fill(0)
        return info
    }

    fun delete(id: String) {
        keyFile(id).delete()
        metaFile(id).delete()
    }

    /**
     * An sshj [KeyProvider] for [SshControlTransport]. Decryption happens lazily on first
     * `getPrivate()`/`getPublic()`; a wrong passphrase surfaces as
     * `com.hierynomus.sshj.common.KeyDecryptionFailedException`.
     */
    @Throws(IOException::class)
    fun keyProvider(id: String, passphrase: CharArray?): KeyProvider {
        val text: String = privateKeyText(id)
        val format: KeyFormat = KeyProviderUtil.detectKeyFileFormat(text, false)
        val provider: FileKeyProvider = providerFor(format)
        val finder: PasswordFinder? = passphrase?.let { PasswordUtils.createOneOff(it) }
        provider.init(text, null, finder)
        return provider
    }

    private fun parsePublicKeyOrThrow(text: String, format: KeyFormat, passphrase: CharArray?): PublicKey {
        val provider: FileKeyProvider = providerFor(format)
        provider.init(text, null, passphrase?.let { PasswordUtils.createOneOff(it) })
        return try {
            provider.getPrivate() // forces full decode (and decryption) so bad keys fail here
            provider.getPublic()
        } catch (e: Exception) {
            throw KeyImportException("could not parse private key: ${e.message}", e)
        }
    }

    private fun providerFor(format: KeyFormat): FileKeyProvider = when (format) {
        KeyFormat.OpenSSHv1 -> OpenSSHKeyV1KeyFile()
        KeyFormat.OpenSSH -> OpenSSHKeyFile()
        KeyFormat.PKCS8 -> PKCS8KeyFile()
        KeyFormat.PuTTY -> PuTTYKeyFile()
        KeyFormat.Unknown -> throw IOException("unrecognised key format")
    }

    private fun write(info: KeyInfo, privateText: String) {
        keysDir.mkdirs()
        keyFile(info.id).writeText(privateText, Charsets.UTF_8)
        metaFile(info.id).writeText(json.encodeToString(KeyInfo.serializer(), info), Charsets.UTF_8)
    }

    private fun readMeta(file: File): KeyInfo? {
        if (!file.isFile) return null
        return try {
            json.decodeFromString(KeyInfo.serializer(), file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    private fun keyFile(id: String): File = File(keysDir, id)
    private fun metaFile(id: String): File = File(keysDir, id + META_SUFFIX)
    private fun newId(): String = UUID.randomUUID().toString()

    companion object {
        private const val META_SUFFIX: String = ".json"

        /** Header markers of passphrase-protected legacy PEM / PKCS#8 / PuTTY files. */
        private val LEGACY_ENCRYPTED_MARKERS: List<String> = listOf(
            "Proc-Type: 4,ENCRYPTED",
            "-----BEGIN ENCRYPTED PRIVATE KEY-----",
            "Encryption: aes256-cbc",
        )
    }
}
