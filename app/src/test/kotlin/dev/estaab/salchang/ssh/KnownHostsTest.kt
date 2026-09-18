package dev.estaab.salchang.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.PublicKey

class KnownHostsTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var file: File
    private lateinit var keyA: PublicKey
    private lateinit var keyB: PublicKey

    @Before
    fun setUp() {
        AndroidCrypto.install()
        file = File(tmp.root, "known_hosts")
        val generator: KeyPairGenerator = KeyPairGenerator.getInstance("Ed25519", "BC")
        keyA = generator.generateKeyPair().public
        keyB = generator.generateKeyPair().public
    }

    @Test
    fun unknownHostIsPromptedAndStoredOnAccept() {
        val prompts = mutableListOf<Triple<String, String, String>>()
        val knownHosts = KnownHosts(file) { host, type, fp -> prompts.add(Triple(host, type, fp)); true }

        assertTrue(knownHosts.createVerifier().verify("Box.local", PORT, keyA))
        assertEquals(1, prompts.size)
        assertEquals("[box.local]:$PORT", prompts[0].first)
        assertEquals("ssh-ed25519", prompts[0].second)
        assertEquals(SshKeyFormat.fingerprintSha256(keyA), prompts[0].third)
        assertEquals(1, knownHosts.lines().size)

        // Second connection: known, no prompt.
        assertTrue(knownHosts.createVerifier().verify("box.local", PORT, keyA))
        assertEquals(1, prompts.size)
    }

    @Test
    fun declinedHostIsNotStoredAndReportsRejection() {
        val knownHosts = KnownHosts(file) { _, _, _ -> false }
        val verifier: TofuHostKeyVerifier = knownHosts.createVerifier()
        assertFalse(verifier.verify("box.local", PORT, keyA))
        assertTrue(verifier.takeFailure() is HostKeyRejectedException)
        assertNull(verifier.takeFailure())
        assertEquals(0, knownHosts.lines().size)
    }

    @Test
    fun changedKeyIsMismatchAndNeverPrompted() {
        var prompted = 0
        val knownHosts = KnownHosts(file) { _, _, _ -> prompted++; true }
        assertTrue(knownHosts.createVerifier().verify("box.local", PORT, keyA))

        val verifier: TofuHostKeyVerifier = knownHosts.createVerifier()
        assertFalse(verifier.verify("box.local", PORT, keyB))
        val failure: HostKeyException? = verifier.takeFailure()
        assertTrue(failure is HostKeyMismatchException)
        assertEquals(SshKeyFormat.fingerprintSha256(keyB), failure!!.fingerprintSha256)
        assertEquals(1, prompted)
    }

    @Test
    fun forgetRemovesEntry() {
        val knownHosts = KnownHosts(file) { _, _, _ -> true }
        assertTrue(knownHosts.createVerifier().verify("box.local", PORT, keyA))
        assertTrue(knownHosts.createVerifier().verify("other.local", DEFAULT_PORT, keyB))
        assertEquals(1, knownHosts.forget("box.local", PORT))
        assertEquals(listOf("other.local"), knownHosts.lines().map { it.substringBefore(' ') })
    }

    companion object {
        private const val PORT: Int = 2222
        private const val DEFAULT_PORT: Int = 22
    }
}
