package com.evanaronson.linguize.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import com.evanaronson.linguize.llm.Provider
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Stores provider API keys in app-private preferences, encrypted with a key
 * held in the Android Keystore. Keys are entered on the settings screen and
 * never live in the code. Each is kept under its provider's token; one saved under the
 * provider's name by an earlier build is moved there on first use.
 */
class ApiKeys(context: Context) : ProviderKeys {
    private val prefs = context.getSharedPreferences("keys", Context.MODE_PRIVATE).apply {
        moveProviderNamesToTokens { it }
    }

    /** Decrypted keys; reads and writes are synchronized so a slow read can't bring back a removed key. */
    private val cache = mutableMapOf<Provider, String>()

    @Synchronized
    override fun get(provider: Provider): String? = cache[provider] ?: prefs.getString(provider.token, null)
        ?.let { runCatching { decrypt(it) }.getOrNull() }
        ?.also { cache[provider] = it }

    /** What the settings screen shows about a provider's stored key. */
    fun status(provider: Provider): KeyStatus {
        if (!prefs.contains(provider.token)) return KeyStatus.None
        val key = get(provider) ?: return KeyStatus.Unreadable
        return KeyStatus.Saved(key.takeLast(4))
    }

    /**
     * Saves [value], or removes the key when it's blank. False when the Keystore couldn't
     * encrypt it (it can fail on some devices); the stored key is then left as it was.
     */
    @Synchronized
    fun set(provider: Provider, value: String): Boolean {
        val key = value.trim()
        if (key.isEmpty()) {
            prefs.edit().remove(provider.token).apply()
            cache.remove(provider)
            return true
        }
        val sealed = try {
            encrypt(key)
        } catch (e: Exception) {
            Log.w("ApiKeys", "Couldn't encrypt the key", e)
            return false
        }
        prefs.edit().putString(provider.token, sealed).apply()
        cache[provider] = key
        return true
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val sealed = cipher.iv + cipher.doFinal(plain.toByteArray())
        return Base64.encodeToString(sealed, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val sealed = Base64.decode(stored, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, sealed, 0, IV_BYTES))
        return String(cipher.doFinal(sealed, IV_BYTES, sealed.size - IV_BYTES))
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "provider-keys"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
    }
}

sealed interface KeyStatus {
    data object None : KeyStatus

    /** Stored but can't be decrypted, e.g. after the Keystore key was reset; it must be re-entered. */
    data object Unreadable : KeyStatus

    data class Saved(val lastFour: String) : KeyStatus
}
