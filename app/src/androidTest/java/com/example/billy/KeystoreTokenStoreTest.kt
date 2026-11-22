package com.example.billy

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.billy.data.KeystoreTokenStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Needs a real device/emulator: the encryption key lives in the Android Keystore. */
@RunWith(AndroidJUnit4::class)
class KeystoreTokenStoreTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = KeystoreTokenStore(context)

    @Before fun setUp() = runBlocking { store.clear() }
    @After fun tearDown() = runBlocking { store.clear() }

    @Test fun roundTripAndClear() = runBlocking {
        assertNull(store.get())
        store.set("secret-session-token")
        assertEquals("secret-session-token", store.get())
        // A fresh instance (e.g. after process restart) can still decrypt it.
        assertEquals("secret-session-token", KeystoreTokenStore(context).get())
        store.clear()
        assertNull(store.get())
    }

    @Test fun tokenIsNotStoredInPlaintext() = runBlocking {
        store.set("secret-session-token")
        val file = File(context.filesDir, "datastore/secure_auth.preferences_pb")
        val bytes = file.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(bytes.contains("secret-session-token"))
    }

    @Test fun migratesLegacyPlaintextToken() = runBlocking {
        context.getSharedPreferences("auth", Context.MODE_PRIVATE)
            .edit().putString("session_token", "legacy-token").commit()
        val fresh = KeystoreTokenStore(context)
        assertEquals("legacy-token", fresh.get())
        assertNull(
            context.getSharedPreferences("auth", Context.MODE_PRIVATE).getString("session_token", null)
        )
    }

    @Test fun corruptValueReadsAsSignedOut() = runBlocking {
        store.writeRawForTest("bm90IHZhbGlkIGNpcGhlcnRleHQgYXQgYWxs")
        assertNull(store.get())
        assertNull(store.get()) // and it was removed
    }
}
