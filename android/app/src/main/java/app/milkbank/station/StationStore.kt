package app.milkbank.station

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

data class StationConfig(val token: String, val name: String)

/** This tablet's submission-station info. The token is stored encrypted; a token that cannot be decrypted is dropped. */
class StationStore(private val dataStore: DataStore<Preferences>, private val cipher: TokenCipher) {
    val config: Flow<StationConfig?> = dataStore.data.map { p ->
        val enc = p[TOKEN_KEY]
        val name = p[NAME_KEY]
        if (enc == null || name == null) {
            null
        } else {
            try {
                StationConfig(cipher.decrypt(enc), name)
            } catch (e: Exception) {
                // Key lost (restore, OS reset) or data corrupt: treat as no station.
                clear()
                null
            }
        }
    }.distinctUntilChanged()

    suspend fun save(c: StationConfig) {
        val enc = cipher.encrypt(c.token)
        dataStore.edit {
            it[TOKEN_KEY] = enc
            it[NAME_KEY] = c.name
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(TOKEN_KEY)
            it.remove(NAME_KEY)
        }
    }

    private companion object {
        val TOKEN_KEY = stringPreferencesKey("station_token_enc")
        val NAME_KEY = stringPreferencesKey("station_name")
    }
}
