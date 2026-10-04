package com.aiia.app.util

import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CryptoTest {
    private val password = "correct horse battery staple"
    private val plain = "{\"text\":\"привет, это синхронизация\"}"

    @Test
    fun `round trip returns the original text`() {
        val payload = Crypto.encrypt(password, plain)
        assertEquals(plain, Crypto.decrypt(password, payload))
    }

    @Test
    fun `current envelope uses the versioned format`() {
        val payload = Crypto.encrypt(password, plain)
        assertTrue(payload.startsWith("enc2:"))
        assertTrue(Crypto.isEncrypted(payload))
        assertTrue(Crypto.isCurrentEnvelope(payload))
        assertEquals(4, payload.removePrefix("enc2:").split(":").size)
    }

    @Test
    fun `same plaintext encrypts differently every time`() {
        val first = Crypto.encrypt(password, plain)
        val second = Crypto.encrypt(password, plain)
        assertNotEquals(first, second)
        assertEquals(plain, Crypto.decrypt(password, first))
        assertEquals(plain, Crypto.decrypt(password, second))
    }

    @Test
    fun `blank password leaves the payload untouched`() {
        assertEquals(plain, Crypto.encrypt("", plain))
        assertEquals(plain, Crypto.encrypt("   ", plain))
        assertNull(Crypto.decrypt("", Crypto.encrypt(password, plain)))
    }

    @Test
    fun `wrong password does not decrypt`() {
        val payload = Crypto.encrypt(password, plain)
        assertNull(Crypto.decrypt("wrong password", payload))
    }

    @Test
    fun `tampered ciphertext is rejected`() {
        val payload = Crypto.encrypt(password, plain)
        val parts = payload.removePrefix("enc2:").split(":")
        val tampered =
            "enc2:" + parts[0] + ":" + parts[1] + ":" + parts[2] + ":" +
                flipChar(parts[3], parts[3].length / 2)
        assertNull(Crypto.decrypt(password, tampered))
    }

    @Test
    fun `plaintext is not treated as encrypted`() {
        assertFalse(Crypto.isEncrypted(plain))
        assertFalse(Crypto.isCurrentEnvelope(plain))
        assertNull(Crypto.decrypt(password, plain))
    }

    @Test
    fun `legacy envelope stays readable`() {
        val legacy = legacyEncrypt(password, plain)
        assertTrue(legacy.startsWith("enc:"))
        assertTrue(Crypto.isEncrypted(legacy))
        assertFalse(Crypto.isCurrentEnvelope(legacy))
        assertEquals(plain, Crypto.decrypt(password, legacy))
    }

    @Test
    fun `malformed envelopes are rejected without throwing`() {
        assertNull(Crypto.decrypt(password, "enc2:"))
        assertNull(Crypto.decrypt(password, "enc2:1:2:3"))
        assertNull(Crypto.decrypt(password, "enc2:notanumber:aa:bb:cc"))
        assertNull(Crypto.decrypt(password, "enc2:1:aa:bb:cc"))
        assertNull(Crypto.decrypt(password, "enc:only-one-part"))
        assertNull(Crypto.decrypt(password, "enc2:999999999:aa:bb:cc"))
    }

    @Test
    fun `empty plaintext round trips`() {
        val payload = Crypto.encrypt(password, "")
        assertEquals("", Crypto.decrypt(password, payload))
    }

    /**
     * Flips one character away from both ends: the trailing Base64 character of a blob may carry
     * only two or four significant bits, so swapping it can leave the decoded bytes untouched.
     */
    private fun flipChar(value: String, index: Int): String {
        if (value.isEmpty()) return "A"
        val target = index.coerceIn(0, value.lastIndex)
        val current = value[target]
        val replacement = when {
            current == 'A' -> 'B'
            current == 'b' -> 'c'
            current.isDigit() && current < '8' -> '9'
            else -> 'A'
        }
        return value.substring(0, target) + replacement + value.substring(target + 1)
    }

    /** Reproduces the pre-`enc2` scheme so backward compatibility stays covered. */
    private fun legacyEncrypt(password: String, plain: String): String {
        val salt =
            MessageDigest.getInstance("SHA-256")
                .digest(password.toByteArray(Charsets.UTF_8)).copyOfRange(0, 16)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, 100_000, 256)
        val key = SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        val iv = ByteArray(12).also { java.security.SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        val encoder: Base64.Encoder = Base64.getEncoder()
        return "enc:" + encoder.encodeToString(iv) + ":" + encoder.encodeToString(ct)
    }
}
