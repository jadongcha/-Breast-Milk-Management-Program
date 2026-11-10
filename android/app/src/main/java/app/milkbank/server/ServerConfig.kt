package app.milkbank.server

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

data class ServerConfig(val url: String, val pin: String)

class ServerConfigStore(private val dataStore: DataStore<Preferences>) {
    val config: Flow<ServerConfig?> = dataStore.data.map { p ->
        val url = p[URL_KEY]
        val pin = p[PIN_KEY]
        if (url != null && pin != null) ServerConfig(url, pin) else null
    }

    suspend fun save(c: ServerConfig) {
        dataStore.edit {
            it[URL_KEY] = c.url
            it[PIN_KEY] = c.pin
        }
    }

    suspend fun clear() {
        dataStore.edit {
            it.remove(URL_KEY)
            it.remove(PIN_KEY)
        }
    }

    private companion object {
        val URL_KEY = stringPreferencesKey("server_url")
        val PIN_KEY = stringPreferencesKey("server_pin")
    }
}
