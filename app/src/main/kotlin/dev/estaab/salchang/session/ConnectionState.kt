package dev.estaab.salchang.session

import kotlinx.coroutines.CompletableDeferred

/** Lifecycle of one host connection as shown in the session screen's banner. */
sealed interface ConnectionState {
    /**
     * SSH + tmux attach in progress. [message] is extra text for the banner, currently the
     * server's SSH auth banner (Tailscale SSH check mode puts the approval URL there).
     */
    data class Connecting(val message: String? = null) : ConnectionState

    /** Waiting for the user to type the key passphrase (see [SessionPrompt.Passphrase]). */
    data object NeedsPassphrase : ConnectionState

    /** Attached to tmux; terminals are live. */
    data object Connected : ConnectionState

    /** Not connected; [reason] is null before the first connect attempt. */
    data class Disconnected(val reason: String?) : ConnectionState

    /** The last connect attempt threw; the message is shown in the banner. */
    data class Failed(val error: Throwable) : ConnectionState
}

/**
 * A question the controller needs the UI to answer. Exactly one is outstanding at a time;
 * the UI shows a dialog and completes [reply]. Completing with the negative answer (or
 * cancelling the deferred) is safe at any time.
 */
sealed interface SessionPrompt {
    /** Unknown host key (trust on first use). Reply true to trust and persist it. */
    data class HostKey(
        val hostname: String,
        val keyType: String,
        val fingerprintSha256: String,
        val reply: CompletableDeferred<Boolean>,
    ) : SessionPrompt

    /** The selected private key is encrypted. Reply null to abort connecting. */
    data class Passphrase(
        val keyName: String,
        val reply: CompletableDeferred<CharArray?>,
    ) : SessionPrompt
}
