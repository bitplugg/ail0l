package com.aiia.app.util

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts secrets that never leave the device: provider API keys, the local API token, the
 * Hugging Face token and the search key.
 *
 * The sync password is sealed here as well, but only for storage at rest. On the wire it still
 * goes through [Crypto], which is password based and therefore portable between devices: the
 * Android Keystore key cannot leave this phone, so it must not be part of that protocol.
 */
interface SecretBox {

    /** Stores [plain] as an opaque envelope. Throws when the backing key store is unusable. */
    fun seal(plain: String): String

    /** Returns the plaintext, or null when the envelope cannot be opened (e.g. invalidated key). */
    fun open(stored: String): String?

    /** True when [stored] carries the envelope produced by [seal]. */
    fun isSealed(stored: String): Boolean

    /** True when [stored] is a legacy plaintext value that should be re-sealed. */
    fun needsMigration(stored: String): Boolean
}

/**
 * Used when the device has no usable Android keystore and by unit tests. Values stay readable
 * as-is, which keeps settings working instead of dropping every configured key.
 */
object PlaintextSecretBox : SecretBox {
    override fun seal(plain: String): String = plain
    override fun open(stored: String): String = stored
    override fun isSealed(stored: String): Boolean = false
    override fun needsMigration(stored: String): Boolean = false
}

/**
 * AES-256-GCM with the key held in the Android keystore, so the ciphertext in DataStore is
 * useless on a rooted device or in an extracted app data directory.
 */
class KeystoreSecretBox(
    private val alias: String = DEFAULT_ALIAS,
    private val provider: String = ANDROID_KEYSTORE
) : SecretBox {

    companion object {
        const val DEFAULT_ALIAS = "aiia_secrets_v1"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val PREFIX = "ks1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_BITS = 128
        private const val KEY_SIZE_BITS = 256

        /** Builds a keystore box, falling back to plaintext storage when the keystore is absent. */
        fun createOrPlaintext(alias: String = DEFAULT_ALIAS): SecretBox = runCatching {
            KeystoreSecretBox(alias).also { it.key() }
        }.getOrElse { PlaintextSecretBox }
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(provider).apply { load(null) }
        (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, provider)
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun seal(plain: String): String {
        if (plain.isEmpty()) return ""
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, key()) }
        val ciphertext = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val encoder = Base64.getEncoder()
        return "$PREFIX:${encoder.encodeToString(cipher.iv)}:${encoder.encodeToString(ciphertext)}"
    }

    override fun open(stored: String): String? {
        if (stored.isEmpty()) return ""
        if (!isSealed(stored)) return stored
        return runCatching {
            val parts = stored.split(':')
            val iv = Base64.getDecoder().decode(parts[1])
            val ciphertext = Base64.getDecoder().decode(parts[2])
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun isSealed(stored: String): Boolean = stored.startsWith("$PREFIX:") && stored.count { it == ':' } == 2

    override fun needsMigration(stored: String): Boolean = stored.isNotEmpty() && !isSealed(stored)
}
