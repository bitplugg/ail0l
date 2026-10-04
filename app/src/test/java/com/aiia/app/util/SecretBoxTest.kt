package com.aiia.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretBoxTest {

    @Test
    fun `plaintext box keeps values readable and never asks for migration`() {
        assertEquals("sk-abc", PlaintextSecretBox.seal("sk-abc"))
        assertEquals("sk-abc", PlaintextSecretBox.open("sk-abc"))
        assertFalse(PlaintextSecretBox.needsMigration("sk-abc"))
        assertFalse(PlaintextSecretBox.isSealed("ks1:a:b"))
    }

    @Test
    fun `keystore box recognises its own envelope`() {
        val box = KeystoreSecretBox()
        assertTrue(box.isSealed("ks1:aXY=:ZGF0YQ=="))
        assertFalse(box.isSealed("sk-abc"))
        assertFalse(box.isSealed("ks1:only-one-part"))
        assertFalse(box.isSealed(""))
    }

    @Test
    fun `keystore box wants legacy plaintext re-sealed`() {
        val box = KeystoreSecretBox()
        assertTrue(box.needsMigration("sk-legacy"))
        assertFalse(box.needsMigration("ks1:aXY=:ZGF0YQ=="))
        assertFalse(box.needsMigration(""))
    }

    @Test
    fun `keystore box returns empty for empty input`() {
        val box = KeystoreSecretBox()
        assertEquals("", box.open(""))
    }

    @Test
    fun `unopenable envelope yields null instead of throwing`() {
        val box = KeystoreSecretBox()
        assertNull(box.open("ks1:not-base-64:also-not"))
    }

    @Test
    fun `factory never throws when no keystore is available`() {
        assertNotNull(KeystoreSecretBox.createOrPlaintext())
    }
}
