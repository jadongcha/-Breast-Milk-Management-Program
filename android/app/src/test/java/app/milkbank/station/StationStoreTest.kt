package app.milkbank.station

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StationStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private class FakeCipher : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(cipher: String): String {
            require(cipher.startsWith("enc:")) { "bad cipher" }
            return cipher.removePrefix("enc:")
        }
    }

    private fun newDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = scope, produceFile = { tempFolder.newFile("s.preferences_pb") })

    private val tokenKey = stringPreferencesKey("station_token_enc")
    private val nameKey = stringPreferencesKey("station_name")

    @Test fun saveThenLoad_roundTrips() = runBlocking {
        val store = StationStore(newDataStore(), FakeCipher())
        assertNull(store.config.first())
        val c = StationConfig("secret-token-123", "신생아중환자실 A")
        store.save(c)
        assertEquals(c, store.config.first())
    }

    @Test fun storedTokenIsNotPlaintext() = runBlocking {
        val ds = newDataStore()
        StationStore(ds, FakeCipher()).save(StationConfig("secret-token-123", "A"))
        val prefs = ds.data.first()
        assertEquals("enc:secret-token-123", prefs[tokenKey])
        assertFalse(prefs.asMap().values.any { it.toString() == "secret-token-123" })
        assertEquals("A", prefs[nameKey])
    }

    @Test fun decryptFailure_clearsAndReturnsNull() = runBlocking {
        val ds = newDataStore()
        ds.edit { it[tokenKey] = "garbage"; it[nameKey] = "A" }
        val store = StationStore(ds, FakeCipher())
        assertNull(store.config.first())
        val prefs = ds.data.first()
        assertNull(prefs[tokenKey])
        assertNull(prefs[nameKey])
    }

    @Test fun clear_removesBoth() = runBlocking {
        val ds = newDataStore()
        val store = StationStore(ds, FakeCipher())
        store.save(StationConfig("t", "A"))
        store.clear()
        assertNull(store.config.first())
        val prefs = ds.data.first()
        assertNull(prefs[tokenKey])
        assertNull(prefs[nameKey])
    }
}
