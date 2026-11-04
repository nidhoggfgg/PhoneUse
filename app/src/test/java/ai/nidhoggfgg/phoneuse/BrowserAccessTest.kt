package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.server.BrowserAccess
import org.junit.Assert.*
import org.junit.Test

class BrowserAccessTest {
    private val host = "192.168.1.20:8443"
    private fun allowed(vararg extra: Pair<String, String>) = BrowserAccess.allowed(mapOf("host" to host, *extra), "192.168.1.20", 8443)
    @Test fun sameOriginAndNativeClientsAreAllowed() {
        assertTrue(allowed())
        assertTrue(allowed("origin" to "https://$host", "sec-fetch-site" to "same-origin"))
    }
    @Test fun foreignNullPlaintextAndWrongPortOriginsAreDenied() {
        for (origin in listOf("null", "https://example.com", "http://$host", "https://192.168.1.20:443"))
            assertFalse(allowed("origin" to origin))
        assertFalse(allowed("sec-fetch-site" to "cross-site"))
    }
    @Test fun domainHostCannotRebindToLocalAddress() {
        assertFalse(allowed("host" to "attacker.example:8443", "origin" to "https://attacker.example:8443"))
        assertFalse(BrowserAccess.allowed(emptyMap(), "192.168.1.20", 8443))
    }
    @Test fun androidAppCanOpenPublicPageWithoutAllowingCrossSiteApiAccess() {
        val navigation = mapOf("host" to host, "sec-fetch-site" to "cross-site")
        assertTrue(BrowserAccess.allowed(navigation, "192.168.1.20", 8443, publicPage = true))
        assertFalse(BrowserAccess.allowed(navigation, "192.168.1.20", 8443))
        assertFalse(BrowserAccess.allowed(navigation + ("host" to "attacker.example:8443"), "192.168.1.20", 8443, publicPage = true))
    }
    @Test fun httpUsesItsOwnExactOriginAndDoesNotTrustForwardingHeaders() {
        fun http(vararg headers: Pair<String, String>) = BrowserAccess.allowed(mapOf("host" to host, *headers), "192.168.1.20", 8443, secure = false)
        assertTrue(http("origin" to "http://$host"))
        for (origin in listOf("https://$host", "http://attacker.example:8443", "null", "http://192.168.1.20:80"))
            assertFalse(http("origin" to origin))
        assertFalse(http("sec-fetch-site" to "same-site"))
        assertFalse(http("host" to "attacker.example:8443", "x-forwarded-host" to host, "x-forwarded-for" to "127.0.0.1"))
    }
    @Test fun onlyListedHostsAreAcceptedWithSameOrigin() {
        val public = "phone.example:8443"
        val headers = mapOf("host" to public, "origin" to "http://$public")
        assertTrue(BrowserAccess.allowed(headers, "192.168.1.20", 8443, secure = false, allowedHosts = listOf("phone.example")))
        assertFalse(BrowserAccess.allowed(headers, "192.168.1.20", 8443, secure = false))
        assertFalse(BrowserAccess.allowed(headers + ("origin" to "https://$public"), "192.168.1.20", 8443, secure = false, allowedHosts = listOf("phone.example")))
        assertFalse(BrowserAccess.allowed(headers + ("origin" to "http://192.168.1.20:8443"), "192.168.1.20", 8443, secure = false, allowedHosts = listOf("phone.example")))
        for (host in listOf("user@phone.example:8443", "phone.example:8443/", "phone.example:8443#x", "phone.example:443"))
            assertFalse(BrowserAccess.allowed(mapOf("host" to host), "192.168.1.20", 8443, allowedHosts = listOf("phone.example")))
    }
    @Test fun ipv6LoopbackAllowsCanonicalEquivalentLiterals() {
        assertTrue(BrowserAccess.allowed(mapOf("host" to "[::1]:8443", "origin" to "http://[::1]:8443"),
            "0:0:0:0:0:0:0:1", 8443, secure = false))
    }

    @Test fun configuredListReplacesDefaultIpAccessIncludingLoopbackAndLandingPage() {
        for (secure in listOf(false, true)) for (publicPage in listOf(false, true)) {
            for (address in listOf("192.168.1.20", "127.0.0.1", "::1")) {
                val authority = if (':' in address) "[$address]:8443" else "$address:8443"
                assertFalse(BrowserAccess.allowed(mapOf("host" to authority), address, 8443,
                    secure = secure, publicPage = publicPage, allowedHosts = listOf("phone.example")))
                assertTrue(BrowserAccess.allowed(mapOf("host" to authority), address, 8443,
                    secure = secure, publicPage = publicPage, allowedHosts = listOf("phone.example", address)))
            }
        }
    }

    @Test fun multipleExactEntriesAllowPublicIpsAndCanonicalIpv6ButNotSubdomains() {
        val hosts = listOf("phone.example", "203.0.113.7", "2001:db8::7")
        for (host in listOf("PHONE.EXAMPLE:8443", "203.0.113.7:8443", "[2001:0db8:0:0:0:0:0:7]:8443"))
            assertTrue(BrowserAccess.allowed(mapOf("host" to host), "192.168.1.20", 8443, allowedHosts = hosts))
        for (host in listOf("192.168.1.20:8443", "other.example:8443", "sub.phone.example:8443", "203.0.113.8:8443", "phone.example:443"))
            assertFalse(BrowserAccess.allowed(mapOf("host" to host, "x-forwarded-host" to "phone.example:8443"),
                "192.168.1.20", 8443, allowedHosts = hosts))
        assertFalse(BrowserAccess.allowed(mapOf("host" to "203.0.113.7:8443"), "192.168.1.20", 8443))
    }

}
