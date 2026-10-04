package com.aiia.app.util

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password based AES-256-GCM used for sync payloads (P2P) and terminal history.
 *
 * Two envelope formats exist:
 *
 * - [LEGACY_PREFIX] `enc:<iv>:<ciphertext>` — written by older builds. The KDF salt is
 *   derived from the password itself, which is why this format is read-only now.
 * - [PREFIX] `enc2:<iterations>:<salt>:<iv>:<ciphertext>` — current format with a random
 *   per-message salt.
 *
 * Legacy payloads stay readable so devices on different versions can still exchange
 * messages; nothing is ever written back in the legacy format.
 */
object Crypto {
    private const val IV_BYTES = 12
    private const val SALT_BYTES = 16
    private const val TAG_BITS = 128

    /** Legacy iteration count, kept only to read old payloads. */
    private const val LEGACY_ITERATIONS = 100_000

    /**
     * PBKDF2 rounds for the current format. Deliberately lower than the 600k recommended
     * for desktop password storage: every sync message pays this cost on a mobile CPU and
     * the key protects a short-lived transport secret, not a credential store.
     */
    private const val ITERATIONS = 210_000

    private const val PREFIX = "enc2:"
    private const val LEGACY_PREFIX = "enc:"

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getEncoder().withoutPadding()
    private val decoder: Base64.Decoder = Base64.getDecoder()

    fun encrypt(password: String, plain: String): String {
        if (password.isBlank()) return plain
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            keyFor(password, salt, ITERATIONS),
            GCMParameterSpec(TAG_BITS, iv)
        )
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX + ITERATIONS + ":" + b64(salt) + ":" + b64(iv) + ":" + b64(ct)
    }

    fun decrypt(password: String, payload: String): String? {
        if (password.isBlank()) return null
        return when {
            payload.startsWith(PREFIX) ->
                runCatching {
                    val parts = payload.removePrefix(PREFIX).split(":")
                    if (parts.size != 4) return null
                    val iterations = parts[0].toIntOrNull() ?: return null
                    if (iterations < 1_000 || iterations > 5_000_000) return null
                    openDecrypting(
                        password,
                        decoder.decode(parts[1]),
                        decoder.decode(parts[2]),
                        decoder.decode(parts[3]),
                        iterations
                    )
                }.getOrNull()

            payload.startsWith(LEGACY_PREFIX) -> decryptLegacy(password, payload)

            else -> null
        }
    }

    /**
     * Reproduces the pre-`enc2` scheme: PBKDF2 with a salt that is itself derived from the
     * password, and no iteration count on the wire.
     */
    private fun decryptLegacy(password: String, payload: String): String? = runCatching {
        val parts = payload.removePrefix(LEGACY_PREFIX).split(":")
        if (parts.size != 2) return null
        val salt =
            MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
        openDecrypting(
            password,
            salt,
            decoder.decode(parts[0]),
            decoder.decode(parts[1]),
            LEGACY_ITERATIONS
        )
    }.getOrNull()

    private fun openDecrypting(password: String, salt: ByteArray, iv: ByteArray, ciphertext: ByteArray, iterations: Int): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyFor(password, salt, iterations),
            GCMParameterSpec(TAG_BITS, iv)
        )
        return String(cipher.doFinal(ciphertext), Charsets.UTF_8)
    }

    private fun keyFor(password: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, iterations, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun b64(value: ByteArray): String = encoder.encodeToString(value)

    fun isEncrypted(payload: String): Boolean = payload.startsWith(PREFIX) || payload.startsWith(LEGACY_PREFIX)

    /** True when [payload] uses the current, randomly salted envelope. */
    fun isCurrentEnvelope(payload: String): Boolean = payload.startsWith(PREFIX)
}
