package com.aiia.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EndpointPolicyTest {
    @Test
    fun `https is always allowed`() {
        assertTrue(EndpointPolicy.isAllowed("https://api.mistral.ai/v1", allowPrivateHosts = false))
        assertTrue(EndpointPolicy.isAllowed("https://api.mistral.ai/v1", allowPrivateHosts = true))
    }

    @Test
    fun `cleartext loopback is allowed for local endpoints`() {
        assertTrue(EndpointPolicy.isAllowed("http://127.0.0.1:8080/v1", allowPrivateHosts = true))
        assertTrue(EndpointPolicy.isAllowed("http://localhost:8081/p2p/receive", allowPrivateHosts = true))
    }

    @Test
    fun `cleartext lan peers are allowed for local endpoints`() {
        assertTrue(EndpointPolicy.isAllowed("http://192.168.1.20:8081/p2p/receive", allowPrivateHosts = true))
        assertTrue(EndpointPolicy.isAllowed("http://10.0.0.5/v1", allowPrivateHosts = true))
        assertTrue(EndpointPolicy.isAllowed("http://172.16.4.4/v1", allowPrivateHosts = true))
        assertTrue(EndpointPolicy.isAllowed("http://172.31.255.254/v1", allowPrivateHosts = true))
    }

    @Test
    fun `cleartext is rejected when private hosts are not allowed`() {
        assertFalse(EndpointPolicy.isAllowed("http://127.0.0.1:8080/v1", allowPrivateHosts = false))
        assertFalse(EndpointPolicy.isAllowed("http://192.168.1.20/v1", allowPrivateHosts = false))
    }

    @Test
    fun `cleartext to a public host is rejected`() {
        val verdict = EndpointPolicy.check("http://api.example.com/v1", allowPrivateHosts = true)
        assertTrue(verdict is EndpointPolicy.Verdict.Rejected)
        assertFalse(EndpointPolicy.isAllowed("http://93.184.216.34/v1", allowPrivateHosts = true))
    }

    @Test
    fun `blank address is rejected`() {
        assertFalse(EndpointPolicy.isAllowed("", allowPrivateHosts = true))
        assertFalse(EndpointPolicy.isAllowed("   ", allowPrivateHosts = true))
    }

    @Test
    fun `missing scheme is rejected`() {
        assertFalse(EndpointPolicy.isAllowed("api.example.com/v1", allowPrivateHosts = true))
        assertFalse(EndpointPolicy.isAllowed("example.com", allowPrivateHosts = true))
    }

    @Test
    fun `unknown scheme is rejected`() {
        assertFalse(EndpointPolicy.isAllowed("ftp://example.com", allowPrivateHosts = true))
        assertFalse(EndpointPolicy.isAllowed("file:///etc/passwd", allowPrivateHosts = true))
        assertFalse(EndpointPolicy.isAllowed("ws://127.0.0.1:8080", allowPrivateHosts = true))
    }

    @Test
    fun `userinfo in the authority is rejected`() {
        assertFalse(EndpointPolicy.isAllowed("http://user:pass@example.com/v1", allowPrivateHosts = true))
    }

    @Test
    fun `private ranges are detected precisely`() {
        assertTrue(EndpointPolicy.isPrivateHost("127.0.0.1"))
        assertTrue(EndpointPolicy.isPrivateHost("10.255.255.255"))
        assertTrue(EndpointPolicy.isPrivateHost("192.168.0.1"))
        assertTrue(EndpointPolicy.isPrivateHost("172.16.0.1"))
        assertTrue(EndpointPolicy.isPrivateHost("169.254.1.1"))
        assertFalse(EndpointPolicy.isPrivateHost("172.15.0.1"))
        assertFalse(EndpointPolicy.isPrivateHost("172.32.0.1"))
        assertFalse(EndpointPolicy.isPrivateHost("8.8.8.8"))
        assertFalse(EndpointPolicy.isPrivateHost("example.com"))
        assertFalse(EndpointPolicy.isPrivateHost("127.0.0.256"))
        assertFalse(EndpointPolicy.isPrivateHost(""))
    }

    @Test
    fun `public looking hosts that merely start with a private number are not private`() {
        assertFalse(EndpointPolicy.isPrivateHost("118.0.0.1"))
        assertFalse(EndpointPolicy.isPrivateHost("192.169.0.1"))
        assertFalse(EndpointPolicy.isPrivateHost("111.0.0.1"))
    }

    @Test
    fun `require returns the trimmed address`() {
        assertEquals("https://api.example.com/v1", EndpointPolicy.require("  https://api.example.com/v1 ", allowPrivateHosts = false))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `require throws on an insecure address`() {
        EndpointPolicy.require("http://api.example.com", allowPrivateHosts = false)
    }

    @Test
    fun `rejection reasons are human readable`() {
        val verdict = EndpointPolicy.check("http://api.example.com", allowPrivateHosts = true)
        assertTrue((verdict as EndpointPolicy.Verdict.Rejected).reason.contains("HTTPS"))
    }
}
