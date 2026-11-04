package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.server.ConnectionAddresses
import org.junit.Assert.*
import org.junit.Test

class ConnectionAddressesTest {
    @Test fun displayedAddressesFollowTheAllowlistAndTransport() {
        assertEquals(listOf("https://192.168.1.2:8443"),
            ConnectionAddresses.urls(listOf("192.168.1.2"), emptyList(), false))
        assertEquals(listOf("https://phone.example:8443", "https://203.0.113.7:8443"),
            ConnectionAddresses.urls(listOf("192.168.1.2"), listOf("phone.example", "203.0.113.7"), false))
        assertEquals(listOf("https://[2001:db8::1]:8443", "http://[2001:db8::1]:8443"),
            ConnectionAddresses.urls(listOf("2001:db8::1"), emptyList(), true))
        assertEquals("http://127.0.0.1:8443/", ConnectionAddresses.LOCAL_CONSOLE)
        assertTrue(ConnectionAddresses.urls(emptyList(), emptyList(), false).isEmpty())
    }
    @Test fun addressesAreValidatedWithoutResolvingDomains() {
        for (host in listOf("phone.example", "203.0.113.7", "2001:db8::7", "127.0.0.1", "::1", "localhost"))
            assertEquals(host, ConnectionAddresses.allowedHost(host))
        assertEquals("phone.example", ConnectionAddresses.allowedHost(" Phone.Example "))
        assertEquals("2001:db8::7", ConnectionAddresses.allowedHost("[2001:db8::7]"))
        for (host in listOf("", "0.0.0.0", "::", "127.1", "192.168.001.1", "224.0.0.1", "ff02::1", "fe80::1%wlan0",
            "https://phone.example", "phone.example:8443", "phone.example/", "phone.example?x", "user@phone.example",
            "*.example", "phone.example#x", "999.2.3.4", "phone.example other.example", "a.example,b.example", "[::1]:8443"))
            assertThrows(host, Exception::class.java) { ConnectionAddresses.allowedHost(host) }
    }

    @Test fun multilineListsIgnoreBlankLinesAndDeduplicateEquivalentAddresses() {
        assertEquals(listOf("phone.example", "127.0.0.1", "::1"),
            ConnectionAddresses.parseAllowlist(" Phone.Example \r\n\r\n127.0.0.1\nphone.example\n[::1]\n0:0:0:0:0:0:0:1\n"))
        assertTrue(ConnectionAddresses.parseAllowlist(" \r\n\t").isEmpty())
        assertThrows(Exception::class.java) { ConnectionAddresses.parseAllowlist("phone.example\nhttps://invalid.example") }
    }

    @Test fun localConsoleNeedsExplicitPermissionWhenAListIsConfigured() {
        assertTrue(ConnectionAddresses.localConsoleAllowed(emptyList()))
        assertFalse(ConnectionAddresses.localConsoleAllowed(listOf("phone.example", "192.168.1.2", "::1")))
        assertTrue(ConnectionAddresses.localConsoleAllowed(listOf("phone.example", "127.0.0.1")))
        assertTrue(ConnectionAddresses.localConsoleAllowed(listOf("::ffff:127.0.0.1")))
    }
}
