package dev.estaab.salchang.data

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class HostProfileTest {
    @Test
    fun jsonRoundTrip() {
        val profile = HostProfile(
            id = HostProfile.newId(),
            name = "desk",
            hostname = "desk.tailnet.ts.net",
            port = 2222,
            username = "estaab",
            keyId = "abc",
            tmuxSession = "work 'stuff'",
            tmuxSocketName = "dev",
            tmuxSocketPath = null,
            tmuxBinary = "/opt/homebrew/bin/tmux",
        )
        val text: String = Json.encodeToString(HostProfile.serializer(), profile)
        assertEquals(profile, Json.decodeFromString(HostProfile.serializer(), text))
    }

    @Test
    fun defaultsAreAppliedWhenFieldsMissing() {
        val decoded: HostProfile = Json.decodeFromString(
            HostProfile.serializer(),
            """{"id":"x","name":"n","hostname":"h","username":"u","keyId":null}""",
        )
        assertEquals(DEFAULT_SSH_PORT, decoded.port)
        assertEquals(DEFAULT_TMUX_SESSION, decoded.tmuxSession)
        assertEquals(DEFAULT_TMUX_BINARY, decoded.tmuxBinary)
        assertEquals(null, decoded.tmuxSocketName)
    }

    @Test
    fun authMethodDefaultsToNoneWhenMissing() {
        val decoded: HostProfile = Json.decodeFromString(
            HostProfile.serializer(),
            """{"id":"x","name":"n","hostname":"h","username":"u","keyId":"k"}""",
        )
        assertEquals(AuthMethod.NONE, decoded.authMethod)
        assertEquals("k", decoded.keyId)
    }

    @Test
    fun authMethodRoundTrips() {
        val profile = HostProfile(id = "a", name = "a", hostname = "a", username = "u", keyId = "k", authMethod = AuthMethod.KEY)
        val text: String = Json.encodeToString(HostProfile.serializer(), profile)
        assertEquals(profile, Json.decodeFromString(HostProfile.serializer(), text))
    }

    @Test
    fun repositoryListEncodingRoundTrips() {
        val a = HostProfile(id = "a", name = "a", hostname = "a", username = "u", keyId = null)
        val b = a.copy(id = "b", name = "b")
        assertEquals(listOf(a, b), HostRepository.decode(HostRepository.encode(listOf(a, b))))
        assertEquals(emptyList<HostProfile>(), HostRepository.decode(null))
        assertEquals(emptyList<HostProfile>(), HostRepository.decode(""))
    }
}
