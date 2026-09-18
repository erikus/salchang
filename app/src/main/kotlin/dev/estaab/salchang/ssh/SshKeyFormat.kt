package dev.estaab.salchang.ssh

import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/**
 * Pure helpers for the OpenSSH wire/file formats used by the key store and host-key prompts:
 * public key blobs, `ssh-keygen -lf` style fingerprints, and the unencrypted `openssh-key-v1`
 * private key container.
 */
object SshKeyFormat {
    const val ED25519_KEY_TYPE: String = "ssh-ed25519"
    const val ED25519_KEY_LENGTH: Int = 32

    const val OPENSSH_V1_BEGIN: String = "-----BEGIN OPENSSH PRIVATE KEY-----"
    const val OPENSSH_V1_END: String = "-----END OPENSSH PRIVATE KEY-----"

    private const val FINGERPRINT_DIGEST: String = "SHA-256"
    private const val FINGERPRINT_PREFIX: String = "SHA256:"

    private const val OPENSSH_V1_MAGIC: String = "openssh-key-v1"
    private const val OPENSSH_V1_CIPHER_NONE: String = "none"
    private const val OPENSSH_V1_KDF_NONE: String = "none"
    private const val OPENSSH_V1_KEY_COUNT: Int = 1

    /** Block size used to pad the private section when the cipher is "none". */
    private const val OPENSSH_V1_PAD_BLOCK: Int = 8

    /** Line width OpenSSH uses for the base64 body of PEM-style key files. */
    private const val PEM_LINE_WIDTH: Int = 70

    /** Header fields of an `openssh-key-v1` file that are readable without a passphrase. */
    data class OpenSshV1Header(
        val cipherName: String,
        val kdfName: String,
        val publicKeyBlob: ByteArray,
    ) {
        val encrypted: Boolean get() = cipherName != OPENSSH_V1_CIPHER_NONE
    }

    /** The `string type || type-specific fields` blob as found in `authorized_keys` (base64-decoded). */
    fun publicKeyBlob(publicKey: PublicKey): ByteArray =
        Buffer.PlainBuffer().putPublicKey(publicKey).compactData

    fun ed25519PublicKeyBlob(rawPublicKey: ByteArray): ByteArray {
        require(rawPublicKey.size == ED25519_KEY_LENGTH) { "ed25519 public key must be $ED25519_KEY_LENGTH bytes" }
        return Buffer.PlainBuffer().putString(ED25519_KEY_TYPE).putString(rawPublicKey).compactData
    }

    /** The key type string at the start of a public key blob, e.g. `ssh-ed25519`. */
    fun keyTypeOf(publicKeyBlob: ByteArray): String = Buffer.PlainBuffer(publicKeyBlob).readString()

    fun keyTypeOf(publicKey: PublicKey): String = KeyType.fromKey(publicKey).toString()

    /** `SHA256:<base64 without padding>` over the public key blob, matching `ssh-keygen -lf`. */
    fun fingerprintSha256(publicKeyBlob: ByteArray): String {
        val digest: ByteArray = MessageDigest.getInstance(FINGERPRINT_DIGEST).digest(publicKeyBlob)
        return FINGERPRINT_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun fingerprintSha256(publicKey: PublicKey): String = fingerprintSha256(publicKeyBlob(publicKey))

    /** One `authorized_keys` line: `<type> <base64 blob> <comment>`. */
    fun publicKeyLine(publicKeyBlob: ByteArray, comment: String): String {
        val body: String = keyTypeOf(publicKeyBlob) + " " + Base64.getEncoder().encodeToString(publicKeyBlob)
        return if (comment.isEmpty()) body else "$body $comment"
    }

    /**
     * Encodes an ed25519 key pair as an unencrypted `openssh-key-v1` PEM file (what
     * `ssh-keygen -t ed25519 -N ''` writes).
     *
     * @param seed the 32-byte private seed
     * @param rawPublicKey the 32-byte public key
     * @param checkInt the value written twice at the start of the private section; callers pass a
     *   random value, tests may pass a fixed one.
     */
    fun encodeOpenSshV1Ed25519(seed: ByteArray, rawPublicKey: ByteArray, comment: String, checkInt: Int): String {
        require(seed.size == ED25519_KEY_LENGTH) { "ed25519 seed must be $ED25519_KEY_LENGTH bytes" }
        require(rawPublicKey.size == ED25519_KEY_LENGTH) { "ed25519 public key must be $ED25519_KEY_LENGTH bytes" }

        val publicBlob: ByteArray = ed25519PublicKeyBlob(rawPublicKey)

        val privateSection: Buffer.PlainBuffer = Buffer.PlainBuffer()
            .putUInt32FromInt(checkInt)
            .putUInt32FromInt(checkInt)
            .putString(ED25519_KEY_TYPE)
            .putString(rawPublicKey)
            .putString(seed + rawPublicKey)
            .putString(comment)
        var pad: Int = 1
        while (privateSection.available() % OPENSSH_V1_PAD_BLOCK != 0) {
            privateSection.putByte(pad.toByte())
            pad++
        }

        val file: Buffer.PlainBuffer = Buffer.PlainBuffer()
            .putRawBytes(OPENSSH_V1_MAGIC.toByteArray(Charsets.US_ASCII))
            .putByte(0)
            .putString(OPENSSH_V1_CIPHER_NONE)
            .putString(OPENSSH_V1_KDF_NONE)
            .putString(ByteArray(0))
            .putUInt32FromInt(OPENSSH_V1_KEY_COUNT)
            .putString(publicBlob)
            .putString(privateSection.compactData)

        val base64: String = Base64.getEncoder().encodeToString(file.compactData)
        val sb = StringBuilder()
        sb.append(OPENSSH_V1_BEGIN).append('\n')
        base64.chunked(PEM_LINE_WIDTH).forEach { line -> sb.append(line).append('\n') }
        sb.append(OPENSSH_V1_END).append('\n')
        return sb.toString()
    }

    /** True if the text is an `openssh-key-v1` file (encrypted or not). */
    fun isOpenSshV1(pemText: String): Boolean = pemText.trimStart().startsWith(OPENSSH_V1_BEGIN)

    /**
     * Reads the unencrypted header of an `openssh-key-v1` file. Works for passphrase-protected keys
     * too, because only the private section is encrypted.
     */
    fun readOpenSshV1Header(pemText: String): OpenSshV1Header {
        val lines: List<String> = pemText.lines().map(String::trim)
        val begin: Int = lines.indexOf(OPENSSH_V1_BEGIN)
        val end: Int = lines.indexOf(OPENSSH_V1_END)
        require(begin >= 0 && end > begin) { "not an openssh-key-v1 file" }
        val body: ByteArray = Base64.getDecoder().decode(lines.subList(begin + 1, end).joinToString(""))

        val buffer = Buffer.PlainBuffer(body)
        val magic = ByteArray(OPENSSH_V1_MAGIC.length + 1)
        buffer.readRawBytes(magic)
        require(String(magic, 0, OPENSSH_V1_MAGIC.length, Charsets.US_ASCII) == OPENSSH_V1_MAGIC) { "bad openssh-key-v1 magic" }
        val cipherName: String = buffer.readString()
        val kdfName: String = buffer.readString()
        buffer.readStringAsBytes() // kdf options
        val keyCount: Int = buffer.readUInt32AsInt()
        require(keyCount == OPENSSH_V1_KEY_COUNT) { "expected $OPENSSH_V1_KEY_COUNT key, found $keyCount" }
        val publicBlob: ByteArray = buffer.readStringAsBytes()
        return OpenSshV1Header(cipherName, kdfName, publicBlob)
    }
}
