package dev.estaab.salchang.ssh

import net.schmizz.sshj.common.Buffer
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64

/** Pure helpers for `ssh-keygen -lf` style host-key fingerprints shown by the TOFU prompt. */
object SshKeyFormat {
    private const val FINGERPRINT_DIGEST: String = "SHA-256"
    private const val FINGERPRINT_PREFIX: String = "SHA256:"

    /** The `string type || type-specific fields` blob as found in `authorized_keys` (base64-decoded). */
    fun publicKeyBlob(publicKey: PublicKey): ByteArray =
        Buffer.PlainBuffer().putPublicKey(publicKey).compactData

    /** `SHA256:<base64 without padding>` over the public key blob, matching `ssh-keygen -lf`. */
    fun fingerprintSha256(publicKeyBlob: ByteArray): String {
        val digest: ByteArray = MessageDigest.getInstance(FINGERPRINT_DIGEST).digest(publicKeyBlob)
        return FINGERPRINT_PREFIX + Base64.getEncoder().withoutPadding().encodeToString(digest)
    }

    fun fingerprintSha256(publicKey: PublicKey): String = fingerprintSha256(publicKeyBlob(publicKey))
}
