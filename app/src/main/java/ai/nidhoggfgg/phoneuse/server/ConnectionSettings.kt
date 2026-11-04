package ai.nidhoggfgg.phoneuse.server

import android.content.Context
import java.net.InetAddress
import java.net.URI

/** Addresses are explicit authorities, never resolved from an untrusted request Host. */
object ConnectionAddresses {
    const val PORT = 8443
    const val LOCAL_CONSOLE = "http://127.0.0.1:8443/"

    fun host(address: String): String = if (':' in address) "[${address.removeSurrounding("[", "]")}]" else address

    fun allowedHost(value: String): String {
        val text = value.trim().lowercase()
        require(text.isNotEmpty())
        require(text.none { it.isWhitespace() } && '%' !in text)
        val uri = URI("https://${host(text)}")
        val parsed = requireNotNull(uri.host).removeSurrounding("[", "]")
        require(uri.rawUserInfo == null && uri.port == -1 && uri.rawPath.isNullOrEmpty() &&
            uri.rawQuery == null && uri.rawFragment == null)
        if (':' in parsed || parsed.all { it.isDigit() || it == '.' }) {
            // Numeric literals only: do not perform DNS queries while validating settings.
            if (':' !in parsed) require(parsed.split('.').let { parts ->
                parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 && (it == "0" || !it.startsWith('0')) }
            })
            val ip = InetAddress.getByName(parsed)
            require(!ip.isAnyLocalAddress && !ip.isMulticastAddress && !ip.isLinkLocalAddress)
        } else {
            require(parsed.length <= 253 &&
                parsed.split('.').all { it.matches(Regex("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) })
        }
        return parsed
    }

    /** Canonicalize numeric IPv6 only; never resolve a domain while checking a request. */
    fun canonicalHost(address: String): String = address.removeSurrounding("[", "]").lowercase().let {
        if (':' in it) requireNotNull(InetAddress.getByName(it).hostAddress) else it
    }

    fun parseAllowlist(value: String): List<String> = value.lineSequence().filter { it.isNotBlank() }
        .map(::allowedHost).distinctBy(::canonicalHost).toList()

    fun permitsHost(address: String, receivingAddress: String, allowedHosts: List<String>): Boolean {
        val candidates = allowedHosts.ifEmpty { listOf(receivingAddress) }
        return candidates.any { canonicalHost(it) == canonicalHost(address) }
    }

    fun localConsoleAllowed(allowedHosts: List<String>): Boolean = permitsHost("127.0.0.1", "127.0.0.1", allowedHosts)

    fun urls(addresses: List<String>, allowedHosts: List<String>, allowHttp: Boolean, port: Int = PORT): List<String> =
        allowedHosts.ifEmpty { addresses }.filter { it.isNotEmpty() }.distinctBy(::canonicalHost).flatMap {
            listOfNotNull("https://${host(it)}:$port", if (allowHttp) "http://${host(it)}:$port" else null)
        }
}

class ConnectionSettings(context: Context) {
    val preferences = context.applicationContext.getSharedPreferences("connection_settings", Context.MODE_PRIVATE)
    init {
        if (!preferences.contains(ALLOWED_HOSTS) && preferences.contains(LEGACY_PUBLIC_HOST)) {
            preferences.edit().putString(ALLOWED_HOSTS, preferences.getString(LEGACY_PUBLIC_HOST, ""))
                .remove(LEGACY_PUBLIC_HOST).apply()
        }
    }
    var allowInsecureHttp: Boolean
        get() = preferences.getBoolean(ALLOW_HTTP, false)
        set(value) { preferences.edit().putBoolean(ALLOW_HTTP, value).apply() }
    var allowedHosts: List<String>
        get() = ConnectionAddresses.parseAllowlist(preferences.getString(ALLOWED_HOSTS, "").orEmpty())
        set(value) {
            val normalized = value.map(ConnectionAddresses::allowedHost).distinctBy(ConnectionAddresses::canonicalHost)
            preferences.edit().putString(ALLOWED_HOSTS, normalized.joinToString("\n")).remove(LEGACY_PUBLIC_HOST).apply()
        }

    companion object {
        const val ALLOW_HTTP = "allow_insecure_http"
        const val ALLOWED_HOSTS = "allowed_hosts"
        private const val LEGACY_PUBLIC_HOST = "public_host"
    }
}
