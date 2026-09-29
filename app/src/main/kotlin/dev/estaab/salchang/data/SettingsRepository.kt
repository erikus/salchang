package dev.estaab.salchang.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val SETTINGS_DATASTORE_NAME: String = "settings"
private const val EXTRA_KEYS_KEY_NAME: String = "extra_keys"

/** Process-wide DataStore holding app-level settings; obtain it via `context.settingsDataStore`. */
val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = SETTINGS_DATASTORE_NAME)

/** App-level (not per-host) settings. */
class SettingsRepository(private val dataStore: DataStore<Preferences>) {

    /** The extra keys bar spec (see `ExtraKeysSpec`), or null when the default is in use. */
    val extraKeysSpec: Flow<String?> = dataStore.data.map { prefs -> prefs[EXTRA_KEYS_KEY] }

    /** Stores [spec]; null goes back to the default. */
    suspend fun setExtraKeysSpec(spec: String?) {
        dataStore.edit { prefs ->
            if (spec == null) prefs.remove(EXTRA_KEYS_KEY) else prefs[EXTRA_KEYS_KEY] = spec
        }
    }

    companion object {
        private val EXTRA_KEYS_KEY: Preferences.Key<String> = stringPreferencesKey(EXTRA_KEYS_KEY_NAME)
    }
}
