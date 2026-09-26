package dev.estaab.salchang

import android.app.Application
import dev.estaab.salchang.data.HostRepository
import dev.estaab.salchang.data.hostsDataStore
import dev.estaab.salchang.ssh.AndroidCrypto
import dev.estaab.salchang.ssh.HostKeyPrompt
import dev.estaab.salchang.ssh.KnownHosts
import java.io.File

/** Application singleton: installs the crypto provider and owns the persistence objects. */
class SalchangApp : Application() {

    lateinit var hostRepository: HostRepository
        private set

    lateinit var knownHostsFile: File
        private set

    override fun onCreate() {
        super.onCreate()
        AndroidCrypto.install()
        hostRepository = HostRepository(hostsDataStore)
        knownHostsFile = File(filesDir, KNOWN_HOSTS_FILE_NAME)
    }

    /** A [KnownHosts] whose unknown-host prompt is answered by [prompt] (typically a Compose dialog). */
    fun knownHosts(prompt: HostKeyPrompt): KnownHosts = KnownHosts(knownHostsFile, prompt)

    companion object {
        const val KNOWN_HOSTS_FILE_NAME: String = "known_hosts"
    }
}
