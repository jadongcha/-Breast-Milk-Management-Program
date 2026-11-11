package app.milkbank.station

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import android.security.keystore.KeyPermanentlyInvalidatedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

data class StationConfig(val token: String, val name: String)

/**
 * This tablet's submission-station info. The token is stored encrypted; a token that can definitely never be
 * decrypted again is dropped, while a passing failure only makes this read come back empty.
 * Decryption runs on [ioDispatcher] (Keystore calls can block).
 */
class StationStore(
    private val dataStore: DataStore<Preferences>,
    private val cipher: TokenCipher,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val onError: (Throwable) -> Unit = {},
) {
    val config: Flow<StationConfig?> = dataStore.data.map { p ->
        val enc = p[TOKEN_KEY]
        val name = p[NAME_KEY]
        if (enc == null || name == null) {
            null
        } else {
            try {
                StationConfig(cipher.decrypt(enc), name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.isPermanent()) {
                    // Key lost (restore, OS reset, lock-screen change) or data corrupt: treat as no station.
                    try {
                        clear()
                    } catch (io: IOException) {
                        onError(io) // collectors must not crash; the next read tries again
                    }
                } else {
                    onError(e) // maybe a passing Keystore hiccup: keep the stored value for the next read
                }
                null
            }
        }
    }.distinctUntilChanged().flowOn(ioDispatcher)

    private fun Exception.isPermanent() =
        this is AEADBadTagException || this is IllegalArgumentException ||
            this is KeyPermanentlyInvalidatedException || this is UnrecoverableKeyException

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
