package dev.estaab.salchang.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

private const val HOSTS_DATASTORE_NAME: String = "hosts"
private const val HOSTS_JSON_KEY_NAME: String = "hosts_json"

/** Process-wide DataStore holding the host list; obtain it via `context.hostsDataStore`. */
val Context.hostsDataStore: DataStore<Preferences> by preferencesDataStore(name = HOSTS_DATASTORE_NAME)

/**
 * Persists [HostProfile]s as a single JSON array under one preferences key. The list is small
 * (a handful of hosts) so read-modify-write of the whole list is fine.
 */
class HostRepository(private val dataStore: DataStore<Preferences>) {

    val hosts: Flow<List<HostProfile>> = dataStore.data.map { prefs -> decode(prefs[HOSTS_KEY]) }

    suspend fun get(id: String): HostProfile? = hosts.first().firstOrNull { it.id == id }

    /** Inserts, or replaces the profile with the same [HostProfile.id], preserving list order. */
    suspend fun upsert(profile: HostProfile) {
        dataStore.edit { prefs ->
            val current: List<HostProfile> = decode(prefs[HOSTS_KEY])
            val index: Int = current.indexOfFirst { it.id == profile.id }
            val updated: List<HostProfile> =
                if (index < 0) current + profile
                else current.toMutableList().also { it[index] = profile }
            prefs[HOSTS_KEY] = encode(updated)
        }
    }

    suspend fun delete(id: String) {
        dataStore.edit { prefs ->
            val current: List<HostProfile> = decode(prefs[HOSTS_KEY])
            prefs[HOSTS_KEY] = encode(current.filterNot { it.id == id })
        }
    }

    companion object {
        private val HOSTS_KEY: Preferences.Key<String> = stringPreferencesKey(HOSTS_JSON_KEY_NAME)
        private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val listSerializer = ListSerializer(HostProfile.serializer())

        fun encode(hosts: List<HostProfile>): String = json.encodeToString(listSerializer, hosts)

        fun decode(text: String?): List<HostProfile> =
            if (text.isNullOrBlank()) emptyList() else json.decodeFromString(listSerializer, text)
    }
}
