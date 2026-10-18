package dev.autorasqr.xuimanager

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.autorasqr.xuimanager.data.secure.KeystoreSecretStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SecretStoreDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val store = KeystoreSecretStore(context)
    private val prefs = context.getSharedPreferences("secrets_v1", Context.MODE_PRIVATE)

    @After
    fun tearDown() = store.removeByPrefix("test.")

    @Test
    fun roundTripWithoutPlaintextOnDisk() {
        store.put("test.a", "s3cret-пароль")
        assertEquals("s3cret-пароль", store.get("test.a"))
        val stored = prefs.getString("test.a", null).orEmpty()
        assertTrue(stored.isNotEmpty())
        assertFalse(stored.contains("s3cret"))
    }

    @Test
    fun eachWriteUsesAFreshIv() {
        store.put("test.a", "same")
        val first = prefs.getString("test.a", null)
        store.put("test.a", "same")
        assertFalse(first == prefs.getString("test.a", null))
    }

    @Test
    fun ciphertextIsBoundToItsKeyName() {
        store.put("test.a", "value")
        prefs.edit().putString("test.b", prefs.getString("test.a", null)).commit()
        assertTrue(runCatching { store.get("test.b") }.isFailure)
    }

    @Test
    fun removeByPrefix() {
        store.put("test.x.1", "1")
        store.put("test.x.2", "2")
        store.put("test.y", "3")
        store.removeByPrefix("test.x.")
        assertNull(store.get("test.x.1"))
        assertNull(store.get("test.x.2"))
        assertEquals("3", store.get("test.y"))
    }
}
