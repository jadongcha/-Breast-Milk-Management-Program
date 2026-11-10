package app.milkbank.server

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ServerConfigStoreTest {
    @get:Rule val tempFolder = TemporaryFolder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    @Test fun saveReadClear() = runBlocking {
        val ds = PreferenceDataStoreFactory.create(scope = scope, produceFile = { tempFolder.newFile("t.preferences_pb") })
        val store = ServerConfigStore(ds)
        assertNull(store.config.first())
        val c = ServerConfig("https://10.0.0.5:8443", "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
        store.save(c)
        assertEquals(c, store.config.first())
        store.clear()
        assertNull(store.config.first())
    }
}
