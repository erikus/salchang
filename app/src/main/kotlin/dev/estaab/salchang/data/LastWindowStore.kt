package dev.estaab.salchang.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first

private const val SESSION_STATE_DATASTORE_NAME: String = "session_state"
private const val LAST_WINDOW_KEY_PREFIX: String = "last_window:"

/** Process-wide DataStore holding per-host session state; obtain it via `context.sessionStateDataStore`. */
val Context.sessionStateDataStore: DataStore<Preferences> by preferencesDataStore(name = SESSION_STATE_DATASTORE_NAME)

/**
 * Remembers, per host profile, the tmux window (`@N`) that was current when the app was last
 * connected, so a reconnect can select it again. Our grouped session is killed on disconnect
 * and a fresh `new-session -t` starts on the group's lowest window, so tmux itself does not
 * keep this for us. Window ids are only meaningful while the same tmux server is running; a
 * remembered window that no longer exists is simply ignored by the caller.
 */
class LastWindowStore(private val dataStore: DataStore<Preferences>) {

    suspend fun get(hostId: String): String? = dataStore.data.first()[key(hostId)]

    suspend fun set(hostId: String, windowId: String) {
        dataStore.edit { prefs -> prefs[key(hostId)] = windowId }
    }

    /** Forgets the window for [hostId]; call when the host profile is deleted. */
    suspend fun clear(hostId: String) {
        dataStore.edit { prefs -> prefs.remove(key(hostId)) }
    }

    private fun key(hostId: String): Preferences.Key<String> = stringPreferencesKey(LAST_WINDOW_KEY_PREFIX + hostId)
}
