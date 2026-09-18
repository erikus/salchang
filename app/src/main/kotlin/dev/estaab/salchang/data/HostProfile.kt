package dev.estaab.salchang.data

import kotlinx.serialization.Serializable
import java.util.UUID

const val DEFAULT_SSH_PORT: Int = 22
const val DEFAULT_TMUX_SESSION: String = "main"
const val DEFAULT_TMUX_BINARY: String = "tmux"

/** How the SSH connection to a host authenticates. */
@Serializable
enum class AuthMethod {
    /**
     * No SSH-level authentication (the `none` method). For Tailscale SSH, which accepts every
     * client and authenticates by tailnet identity instead; in "check mode" the server holds
     * the auth open and sends the approval URL as an auth banner.
     */
    NONE,

    /** OpenSSH `publickey` with the key in [HostProfile.keyId]. */
    KEY,
}

/**
 * A saved remote tmux host. Exactly one of [tmuxSocketName] (`tmux -L`) / [tmuxSocketPath]
 * (`tmux -S`) may be set; both null means the default socket.
 */
@Serializable
data class HostProfile(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = DEFAULT_SSH_PORT,
    val username: String,
    /** Id of a key in [KeyStore]; required when [authMethod] is [AuthMethod.KEY], ignored otherwise. */
    val keyId: String?,
    /**
     * Defaults to [AuthMethod.NONE] so profiles persisted before this field existed (JSON
     * without `authMethod`) still load; a legacy profile with a key must be switched to
     * [AuthMethod.KEY] in the edit screen to use it.
     */
    val authMethod: AuthMethod = AuthMethod.NONE,
    val tmuxSession: String = DEFAULT_TMUX_SESSION,
    val tmuxSocketName: String? = null,
    val tmuxSocketPath: String? = null,
    val tmuxBinary: String = DEFAULT_TMUX_BINARY,
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
