package com.example.billy.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Holds the session token. Implementations must keep it encrypted at rest. */
interface TokenStore {
    suspend fun get(): String?
    suspend fun set(token: String)
    suspend fun clear()
}

// One DataStore instance per file per process, so this must be a top-level delegate.
private val Context.secureAuthStore: DataStore<Preferences> by preferencesDataStore(name = "secure_auth")

/**
 * Stores the token in Preferences DataStore, encrypted with AES-256-GCM. The key is generated
 * inside the Android Keystore and can't be exported, so the stored value is useless off this device.
 * Stored format: base64(iv || ciphertext+tag).
 *
 * Also migrates a token left in the old plaintext SharedPreferences("auth") and deletes that file.
 */
class KeystoreTokenStore(
    context: Context,
    private val keyAlias: String = KEY_ALIAS,
) : TokenStore {
    private val appContext = context.applicationContext
    private val store = appContext.secureAuthStore
    private val mutex = Mutex()
    private var migrated = false

    override suspend fun get(): String? = mutex.withLock {
        migrateLegacy()
        val stored = store.data.first()[KEY_TOKEN] ?: return null
        val token = runCatching { decrypt(stored) }.getOrNull()
        if (token == null) {
            // Wrong key (e.g. restored from another device, key invalidated) or corrupt data.
            // Treat as signed out rather than crash.
            store.edit { it.remove(KEY_TOKEN) }
        }
        token
    }

    override suspend fun set(token: String) = mutex.withLock {
        migrated = true // a fresh token supersedes anything legacy
        deleteLegacy()
        val value = encrypt(token)
        store.edit { it[KEY_TOKEN] = value }
        Unit
    }

    override suspend fun clear() = mutex.withLock {
        deleteLegacy()
        store.edit { it.remove(KEY_TOKEN) }
        Unit
    }

    private suspend fun migrateLegacy() {
        if (migrated) return
        migrated = true
        val legacy = appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val token = legacy.getString(LEGACY_KEY, null) ?: return
        val value = encrypt(token)
        store.edit { it[KEY_TOKEN] = value }
        deleteLegacy()
    }

    private fun deleteLegacy() {
        appContext.getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        appContext.deleteSharedPreferences(LEGACY_PREFS)
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + ct, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val bytes = Base64.decode(stored, Base64.NO_WRAP)
        if (bytes.size <= IV_BYTES) throw GeneralSecurityException("truncated")
        val key = existingKey() ?: throw GeneralSecurityException("no key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    private fun keyStore() = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? = keyStore().getKey(keyAlias, null) as SecretKey?

    private fun key(): SecretKey = existingKey() ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        .apply {
            init(
                KeyGenParameterSpec.Builder(
                    keyAlias,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build()
            )
        }
        .generateKey()

    /** Test hook: writes a raw (already-encoded) value, e.g. to simulate corruption. */
    internal suspend fun writeRawForTest(value: String) {
        store.edit { it[KEY_TOKEN] = value }
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "billy_session_token_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private val KEY_TOKEN = stringPreferencesKey("session_token")

        /** Pre-encryption storage; only read for migration. */
        internal const val LEGACY_PREFS = "auth"
        internal const val LEGACY_KEY = "session_token"
    }
}
