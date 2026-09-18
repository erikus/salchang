package dev.estaab.salchang.data

import com.hierynomus.sshj.userauth.keyprovider.OpenSSHKeyV1KeyFile
import dev.estaab.salchang.ssh.AndroidCrypto
import dev.estaab.salchang.ssh.SshKeyFormat
import net.schmizz.sshj.common.KeyType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.PublicKey
import java.util.concurrent.TimeUnit

class KeyStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var store: KeyStore

    @Before
    fun setUp() {
        AndroidCrypto.install()
        store = KeyStore(File(tmp.root, "keys"))
    }

    @Test
    fun generatedEd25519KeyRoundTripsThroughSshj() {
        val info: KeyInfo = store.generateEd25519("phone")
        assertFalse(info.encrypted)
        assertTrue(info.fingerprintSha256, FINGERPRINT_PATTERN.matches(info.fingerprintSha256))
        assertTrue(info.publicKeyOpenSsh.startsWith("ssh-ed25519 "))
        assertTrue(info.publicKeyOpenSsh.endsWith(" phone"))

        val pem: String = store.privateKeyText(info.id)
        assertTrue(pem.startsWith(SshKeyFormat.OPENSSH_V1_BEGIN))

        val parsed = OpenSSHKeyV1KeyFile()
        parsed.init(pem, null, null)
        val publicKey: PublicKey = parsed.getPublic()
        assertEquals(KeyType.ED25519, KeyType.fromKey(publicKey))
        assertNotNull(parsed.getPrivate())

        val blobFromParsed: ByteArray = SshKeyFormat.publicKeyBlob(publicKey)
        assertEquals(SshKeyFormat.publicKeyLine(blobFromParsed, "phone"), info.publicKeyOpenSsh)
        assertEquals(SshKeyFormat.fingerprintSha256(blobFromParsed), info.fingerprintSha256)

        // keyProvider() yields the same key pair.
        val provider = store.keyProvider(info.id, null)
        assertArrayEquals(blobFromParsed, SshKeyFormat.publicKeyBlob(provider.getPublic()))
    }

    @Test
    fun encoderIsDeterministicAndPaddedToBlockSize() {
        val seed = ByteArray(SshKeyFormat.ED25519_KEY_LENGTH) { it.toByte() }
        val pub = ByteArray(SshKeyFormat.ED25519_KEY_LENGTH) { (it + 100).toByte() }
        val a: String = SshKeyFormat.encodeOpenSshV1Ed25519(seed, pub, "c", 7)
        val b: String = SshKeyFormat.encodeOpenSshV1Ed25519(seed, pub, "c", 7)
        assertEquals(a, b)
        val header = SshKeyFormat.readOpenSshV1Header(a)
        assertFalse(header.encrypted)
        assertArrayEquals(SshKeyFormat.ed25519PublicKeyBlob(pub), header.publicKeyBlob)
    }

    @Test
    fun importListDeleteLifecycle() {
        val generated: KeyInfo = store.generateEd25519("gen")
        val pem: String = store.privateKeyText(generated.id)
        val imported: KeyInfo = store.importFromText("imported", pem)
        assertEquals(generated.fingerprintSha256, imported.fingerprintSha256)
        assertFalse(imported.encrypted)

        assertEquals(listOf("gen", "imported"), store.list().map { it.name })
        store.delete(generated.id)
        assertEquals(listOf("imported"), store.list().map { it.name })
        assertEquals(null, store.get(generated.id))
    }

    @Test(expected = KeyImportException::class)
    fun importRejectsGarbage() {
        store.importFromText("bad", "hello world")
    }

    @Test
    fun matchesSshKeygenWhenAvailable() {
        val sshKeygen: File? = System.getenv("PATH").split(File.pathSeparator).map { File(it, "ssh-keygen") }.firstOrNull { it.canExecute() }
        assumeTrue("ssh-keygen not on PATH", sshKeygen != null)

        val info: KeyInfo = store.generateEd25519("cmp")
        val keyFile = File(tmp.root, "keys/${info.id}")
        keyFile.setReadable(false, false)
        keyFile.setReadable(true, true)

        val fingerprintLine: String = run(sshKeygen!!, "-lf", keyFile.path)
        assertTrue(fingerprintLine, fingerprintLine.contains(info.fingerprintSha256))

        val publicLine: String = run(sshKeygen, "-yf", keyFile.path).trim()
        // ssh-keygen -y prints the comment stored in the openssh-key-v1 file as well.
        assertEquals(info.publicKeyOpenSsh, publicLine)
    }

    private fun run(exe: File, vararg args: String): String {
        val process: Process = ProcessBuilder(listOf(exe.path) + args).redirectErrorStream(true).start()
        val output: String = process.inputStream.readBytes().toString(Charsets.UTF_8)
        assertTrue(process.waitFor(SUBPROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        assertEquals(output, 0, process.exitValue())
        return output
    }

    companion object {
        private val FINGERPRINT_PATTERN = Regex("^SHA256:[A-Za-z0-9+/]{43}$")
        private const val SUBPROCESS_TIMEOUT_SECONDS: Long = 10
    }
}
