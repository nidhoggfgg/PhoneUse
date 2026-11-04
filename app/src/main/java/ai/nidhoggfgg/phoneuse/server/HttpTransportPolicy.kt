package ai.nidhoggfgg.phoneuse.server

import ai.nidhoggfgg.phoneuse.core.fail
import java.net.Socket

/** Registration and disabling share a lock so a concurrent admission cannot escape shutdown. */
class HttpTransportPolicy(allowInsecureHttp: Boolean = false) {
    private val lock = Any()
    private var enabled = allowInsecureHttp
    private var generation = 0L
    private val insecureHttp = mutableSetOf<Socket>()

    fun update(allow: Boolean) {
        val closing = synchronized(lock) {
            if (enabled == allow) return
            enabled = allow
            if (allow) emptyList() else {
                generation++
                insecureHttp.toList().also { insecureHttp.clear() }
            }
        }
        closing.forEach { runCatching { it.close() } }
    }

    /** Check actual socket endpoints first, then call again after parsing the requested Host. */
    fun register(socket: Socket, secure: Boolean, localConsoleHost: Boolean = true): (() -> Unit)? = synchronized(lock) {
        val localConsole = socket.inetAddress.hostAddress == "127.0.0.1" &&
            socket.localAddress.hostAddress == "127.0.0.1" && localConsoleHost
        if (secure || localConsole) return@synchronized {}
        if (!enabled) return@synchronized null
        insecureHttp.add(socket)
        val admittedGeneration = generation
        val check: () -> Unit = {
            synchronized(lock) {
                if (!enabled || generation != admittedGeneration)
                    fail("TRANSPORT_DISABLED", HTTP_DISABLED)
            }
        }
        check
    }

    fun remove(socket: Socket) { synchronized(lock) { insecureHttp.remove(socket) } }

    companion object {
        const val HTTP_DISABLED = "HTTP is disabled except for local access through 127.0.0.1"
    }
}
