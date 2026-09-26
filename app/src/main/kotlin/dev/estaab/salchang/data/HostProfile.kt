package dev.estaab.salchang.data

import kotlinx.serialization.Serializable
import java.util.UUID

const val DEFAULT_SSH_PORT: Int = 22
const val DEFAULT_TMUX_SESSION: String = "main"
const val DEFAULT_TMUX_BINARY: String = "tmux"

/**
 * A saved remote tmux host, reached over Tailscale SSH: the connection uses the SSH `none`
 * auth method and the server authenticates by tailnet identity (in "check mode" it holds the
 * auth open and sends the approval URL as an auth banner). Exactly one of [tmuxSocketName]
 * (`tmux -L`) / [tmuxSocketPath] (`tmux -S`) may be set; both null means the default socket.
 *
 * Profiles saved by older versions carry `authMethod` / `keyId` fields; the repository's JSON
 * decoder ignores unknown keys, so they still load.
 */
@Serializable
data class HostProfile(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = DEFAULT_SSH_PORT,
    val username: String,
    val tmuxSession: String = DEFAULT_TMUX_SESSION,
    val tmuxSocketName: String? = null,
    val tmuxSocketPath: String? = null,
    val tmuxBinary: String = DEFAULT_TMUX_BINARY,
) {
    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
