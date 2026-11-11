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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException

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

    private var files = 0

    private fun newDataStore(): DataStore<Preferences> {
        val f = tempFolder.newFile("s${files++}.preferences_pb")
        return PreferenceDataStoreFactory.create(scope = scope, produceFile = { f })
    }

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

    private class ThrowingCipher(var failure: Exception?) : TokenCipher {
        override fun encrypt(plain: String) = "enc:$plain"
        override fun decrypt(cipher: String): String {
            failure?.let { throw it }
            return cipher.removePrefix("enc:")
        }
    }

    private suspend fun storedAfterFailure(e: Exception): Pair<StationConfig?, Boolean> {
        val ds = newDataStore()
        ds.edit { it[tokenKey] = "enc:t"; it[nameKey] = "A" }
        val store = StationStore(ds, ThrowingCipher(e))
        val read = store.config.first()
        return read to (ds.data.first()[tokenKey] != null)
    }

    @Test fun definitiveDecryptFailures_clearTheStoredToken() = runBlocking {
        listOf(
            AEADBadTagException("tag"),
            IllegalArgumentException("base64"),
            UnrecoverableKeyException("key gone"),
        ).forEach { e ->
            val (read, kept) = storedAfterFailure(e)
            assertNull(e.toString(), read)
            assertFalse(e.toString(), kept)
        }
    }

    @Test fun keyPermanentlyInvalidated_clearsTheStoredToken() = runBlocking {
        val (read, kept) = storedAfterFailure(android.security.keystore.KeyPermanentlyInvalidatedException())
        assertNull(read)
        assertFalse(kept)
    }

    @Test fun otherDecryptFailures_emitNullButKeepTheStoredToken() = runBlocking {
        val reported = mutableListOf<Throwable>()
        val ds = newDataStore()
        ds.edit { it[tokenKey] = "enc:t"; it[nameKey] = "A" }
        val cipher = ThrowingCipher(java.security.GeneralSecurityException("keystore busy"))
        val store = StationStore(ds, cipher, onError = { reported += it })
        assertNull(store.config.first())
        assertEquals("enc:t", ds.data.first()[tokenKey])
        assertEquals(1, reported.size)
        cipher.failure = null // the hiccup passed: the token is still there
        assertEquals(StationConfig("t", "A"), store.config.first())
    }

    @Test fun clearFailingWithIOException_doesNotCrashCollectors() = runBlocking {
        val inner = newDataStore()
        inner.edit { it[tokenKey] = "garbage"; it[nameKey] = "A" }
        val failing = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = inner.data
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                throw IOException("disk full")
        }
        val reported = mutableListOf<Throwable>()
        val store = StationStore(failing, FakeCipher(), onError = { reported += it })
        assertNull(store.config.first())
        assertTrue(reported.single() is IOException)
    }

    @Test fun decryptionRunsOffTheCallerThread() = runBlocking {
        val ds = newDataStore()
        ds.edit { it[tokenKey] = "enc:t"; it[nameKey] = "A" }
        var decryptThread: Thread? = null
        val cipher = object : TokenCipher {
            override fun encrypt(plain: String) = plain
            override fun decrypt(cipher: String): String {
                decryptThread = Thread.currentThread()
                return "t"
            }
        }
        val caller = Thread.currentThread()
        StationStore(ds, cipher).config.first()
        assertTrue(decryptThread != null && decryptThread !== caller)
    }
}
