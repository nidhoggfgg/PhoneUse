package ai.nidhoggfgg.phoneuse.server

import java.net.URI

/** An empty allowlist permits the receiving IP only; a configured list replaces that default. */
object BrowserAccess {
    fun allowed(headers: Map<String, String>, address: String, port: Int, publicPage: Boolean = false,
        secure: Boolean = true, allowedHosts: List<String> = emptyList()): Boolean {
        val host = headers["host"] ?: return false
        val authority = normalizedAuthority(host) ?: return false
        return authority.second == port && ConnectionAddresses.permitsHost(authority.first, address, allowedHosts) && (publicPage ||
            ((headers["origin"] == null || headers["origin"] == "${if (secure) "https" else "http"}://$host") &&
                headers["sec-fetch-site"] !in setOf("cross-site", "same-site")))
    }

    private fun normalizedAuthority(value: String): Pair<String, Int>? = runCatching {
        val uri = URI("https://$value")
        val host = requireNotNull(uri.host).removeSurrounding("[", "]").lowercase()
        require(uri.rawUserInfo == null && uri.port in 1..65535 && uri.rawPath.isNullOrEmpty() &&
            uri.rawQuery == null && uri.rawFragment == null && '%' !in host)
        ConnectionAddresses.canonicalHost(host) to uri.port
    }.getOrNull()
}
