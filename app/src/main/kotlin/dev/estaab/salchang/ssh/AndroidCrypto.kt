package dev.estaab.salchang.ssh

import net.schmizz.sshj.common.SecurityUtils
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Provider
import java.security.Security

/**
 * Makes the full BouncyCastle provider the JCA provider sshj uses.
 *
 * Android ships a stripped-down provider also named "BC" that lacks the algorithms sshj needs
 * (Ed25519, curve25519 key agreement, ...). sshj's [SecurityUtils] only calls `Security.addProvider`
 * when no provider named "BC" exists, so on Android it would silently pick up the stripped one.
 * We therefore remove any existing "BC" provider, insert the bundled BouncyCastle at the highest
 * priority, and tell sshj explicitly to use it.
 *
 * Safe to call more than once; only the first call does work. Must run before any sshj class that
 * touches [SecurityUtils] (i.e. call it from `Application.onCreate`).
 */
object AndroidCrypto {
    /** The provider name shared by Android's stripped provider and the real BouncyCastle. */
    private const val PROVIDER_NAME: String = BouncyCastleProvider.PROVIDER_NAME

    /** `Security.insertProviderAt` positions are 1-based; 1 is the highest priority. */
    private const val HIGHEST_PRIORITY_POSITION: Int = 1

    @Volatile
    private var installed: Boolean = false

    @Synchronized
    fun install() {
        if (installed) return
        val existing: Provider? = Security.getProvider(PROVIDER_NAME)
        if (existing !is BouncyCastleProvider) {
            if (existing != null) {
                Security.removeProvider(PROVIDER_NAME)
            }
            Security.insertProviderAt(BouncyCastleProvider(), HIGHEST_PRIORITY_POSITION)
        }
        SecurityUtils.setRegisterBouncyCastle(true)
        SecurityUtils.setSecurityProvider(PROVIDER_NAME)
        installed = true
    }
}
