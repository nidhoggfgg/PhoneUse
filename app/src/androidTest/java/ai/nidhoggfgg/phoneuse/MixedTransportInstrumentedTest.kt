package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.*
import ai.nidhoggfgg.phoneuse.server.HttpServer
import ai.nidhoggfgg.phoneuse.server.IdentityStore
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Real sockets and Keystore TLS; actions use a fake backend and never control the device UI. */
@RunWith(AndroidJUnit4::class)
class MixedTransportInstrumentedTest {
    private val port = 18445
    private data class Reply(val status: Int, val headers: Map<String, String>, val body: String) {
        fun json() = JSONObject(body)
    }
    private fun reply(socket: Socket): Reply {
        val input = DataInputStream(BufferedInputStream(socket.inputStream))
        fun line(): String {
            val chars = StringBuilder()
            while (true) {
                val byte = input.readUnsignedByte()
                if (byte == 10) return chars.toString().removeSuffix("\r")
                chars.append(byte.toChar())
            }
        }
        val status = line().split(' ')[1].toInt()
        val headers = mutableMapOf<String, String>()
        while (true) {
            val header = line()
            if (header.isEmpty()) break
            headers[header.substringBefore(':').lowercase()] = header.substringAfter(':').trim()
        }
        val body = ByteArray(headers.getValue("content-length").toInt())
        input.readFully(body)
        return Reply(status, headers, body.toString(Charsets.UTF_8))
    }
    private fun socket(identity: IdentityStore, address: String, secure: Boolean): Socket {
        if (!secure) return Socket(address, port).apply { soTimeout = 5000 }
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = error("Unused")
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                assertEquals(identity.tls.pin, ai.nidhoggfgg.phoneuse.server.PairingCrypto.hash(chain[0].publicKey.encoded))
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return tls.socketFactory.createSocket(address, port).apply { soTimeout = 5000 }
    }
    private fun request(identity: IdentityStore, address: String, path: String, secure: Boolean = false,
        body: JSONObject? = null, cookie: String? = null, token: String? = null,
        host: String = if (':' in address) "[$address]:$port" else "$address:$port",
        origin: String = "${if (secure) "https" else "http"}://$host", site: String = "same-origin",
        extra: String = ""): Reply = socket(identity, address, secure).use { socket ->
        val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val headers = "${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: $host\r\nOrigin: $origin\r\nSec-Fetch-Site: $site\r\n" +
            (cookie?.let { "Cookie: $it\r\n" } ?: "") + (token?.let { "Authorization: Bearer $it\r\n" } ?: "") +
            (if (body != null) "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" else "") + extra + "\r\n"
        socket.outputStream.write(headers.toByteArray(Charsets.US_ASCII)); socket.outputStream.write(bytes)
        socket.outputStream.flush()
        reply(socket)
    }

    @Test fun transportMatrixPairingCookiesAndImmediateDisconnect() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = IdentityStore(context)
        val remote = NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<Inet4Address>().first { !it.isLoopbackAddress }.hostAddress!!
        val waiting = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val actions = AtomicInteger()
        val backend = object : DeviceBackend {
            override fun capabilities() = JSONObject()
            override fun observe(args: JSONObject) = JSONObject()
            override fun listApps() = JSONObject()
            override fun act(name: String, args: JSONObject, check: () -> Unit): JSONObject {
                check(); actions.incrementAndGet(); return json("accepted" to true)
            }
            override fun waitForChange(args: JSONObject, check: () -> Unit): JSONObject {
                waiting.countDown()
                try { repeat(1000) { check(); Thread.sleep(10) } }
                finally { cancelled.countDown() }
                return JSONObject()
            }
        }
        val controller = DeviceController(backend, SystemClock::elapsedRealtime, identity::exists)
        val allowedHosts = java.util.concurrent.atomic.AtomicReference(emptyList<String>())
        val server = HttpServer(identity, controller, context.assets, allowedHosts = { allowedHosts.get() }) {}
        val pool = Executors.newSingleThreadExecutor()
        var clientId: String? = null
        try {
            server.start(port)
            for (secure in listOf(false, true)) {
                for (address in if (secure) listOf("127.0.0.1", "::1") else listOf("127.0.0.1")) {
                    assertEquals(200, request(identity, address, "/", secure).status)
                    assertEquals(200, request(identity, address, "/app.js", secure).status)
                    assertEquals(401, request(identity, address, "/api/tools", secure).status)
                    assertEquals(403, request(identity, address, "/browser/pair/request", secure,
                        json("client_name" to "evil"), origin = "http://evil.example").status)
                }
            }
            for (path in listOf("/", "/identity", "/browser/session", "/api/tools", "/browser/pair/request"))
                assertEquals(403, request(identity, remote, path,
                    body = if (path.endsWith("request")) json("client_name" to "blocked") else null,
                    host = "127.0.0.1:$port", extra = "X-Forwarded-For: 127.0.0.1\r\nForwarded: for=127.0.0.1\r\n").status)
            assertTrue(identity.pairing.pending().isEmpty())
            assertEquals(200, request(identity, remote, "/identity", secure = true).status)
            allowedHosts.set(listOf(remote, "phone.example", "127.0.0.1", "127.0.0.2", "::1"))
            for (address in listOf(remote, "127.0.0.2", "::1")) {
                assertEquals(403, request(identity, address, "/identity").status)
                assertEquals(200, request(identity, address, "/identity", secure = true).status)
            }
            assertEquals(200, request(identity, "127.0.0.1", "/identity").status)
            assertEquals(403, request(identity, "127.0.0.1", "/identity", host = "phone.example:$port").status)
            assertEquals(200, request(identity, "127.0.0.1", "/identity", secure = true, host = "phone.example:$port").status)
            assertEquals(200, request(identity, remote, "/identity", secure = true, host = "phone.example:$port").status)
            allowedHosts.set(emptyList())
            server.setAllowInsecureHttp(true)
            assertEquals(403, request(identity, remote, "/identity", host = "phone.example:$port").status)
            allowedHosts.set(listOf("phone.example", "other.example"))
            for (secure in listOf(false, true)) {
                for (address in listOf(remote, "127.0.0.1", "::1")) {
                    for (path in listOf("/", "/app.js", "/identity", "/api/tools", "/mcp", "/browser/pair/request"))
                        assertEquals(403, request(identity, address, path, secure,
                            body = if (path.endsWith("request")) json("client_name" to "blocked") else null).status)
                    assertEquals(200, request(identity, address, "/identity", secure, host = "phone.example:$port").status)
                    assertEquals(401, request(identity, address, "/api/tools", secure, host = "other.example:$port").status)
                }
            }
            assertTrue(identity.pairing.pending().isEmpty())
            allowedHosts.set(listOf(remote, "127.0.0.1", "::1", "phone.example"))
            assertEquals(200, request(identity, remote, "/identity").status)
            assertEquals(200, request(identity, "::1", "/identity").status)
            assertEquals(200, request(identity, remote, "/identity", host = "phone.example:$port").status)
            allowedHosts.set(emptyList())
            assertEquals(403, request(identity, remote, "/identity", host = "phone.example:$port").status)
            assertEquals(403, request(identity, remote, "/identity", host = "evil.example:$port").status)
            assertEquals(401, request(identity, remote, "/api/tools").status)
            val created = request(identity, remote, "/browser/pair/request", body = json("client_name" to "HTTP test")).also {
                assertEquals(202, it.status)
            }.json()
            val claim = json("pairing_id" to created.getString("pairing_id"), "pairing_secret" to created.getString("pairing_secret"))
            identity.pairing.decide(created.getString("pairing_id"), true)
            val approved = request(identity, remote, "/browser/pair/status", body = claim)
            clientId = approved.json().getString("client_id")
            assertFalse(approved.json().has("token"))
            val header = approved.headers.getValue("set-cookie")
            assertTrue(header.startsWith("phoneuse_http_client_$port="))
            assertFalse(header.contains("Secure"))
            assertTrue(header.contains("HttpOnly")); assertTrue(header.contains("SameSite=Strict"))
            val cookie = header.substringBefore(';')
            assertTrue(request(identity, remote, "/browser/session", cookie = cookie).json().getBoolean("paired"))
            assertFalse(request(identity, remote, "/browser/session", secure = true, cookie = cookie).json().getBoolean("paired"))
            val token = cookie.substringAfter('=')
            val tlsCookie = request(identity, remote, "/browser/session", secure = true, token = token, body = JSONObject())
                .headers.getValue("set-cookie")
            assertTrue(tlsCookie.startsWith("phoneuse_client_$port=")); assertTrue(tlsCookie.contains("Secure"))
            assertFalse(request(identity, remote, "/browser/session", cookie = tlsCookie.substringBefore(';')).json().getBoolean("paired"))
            val action = json("tool" to "home", "arguments" to json("request_id" to "http-home"))
            assertEquals("executed", request(identity, remote, "/api", body = action, cookie = cookie).json().getString("state"))
            assertEquals(1, actions.get())
            for (site in listOf("cross-site", "same-site"))
                assertEquals(403, request(identity, remote, "/api", body = action, cookie = cookie, site = site).status)
            assertEquals(403, request(identity, remote, "/api", body = action, cookie = cookie, origin = "https://$remote:$port").status)
            assertEquals(1, actions.get())
            val pending = pool.submit<Reply> {
                request(identity, remote, "/api", body = json("tool" to "wait_for_change", "arguments" to json("ui_version" to 0)), cookie = cookie)
            }
            assertTrue(waiting.await(5, TimeUnit.SECONDS))
            // Partially received requests must also close, before pairing/authentication/body completion.
            Socket(remote, port).use { partial ->
                partial.soTimeout = 2000
                partial.outputStream.write("POST /api HTTP/1.1\r\nHost: $remote:$port\r\n".toByteArray())
                Thread.sleep(100)
                server.setAllowInsecureHttp(false)
                val closed = runCatching { partial.inputStream.read() }.getOrDefault(-1)
                assertEquals(-1, closed)
            }
            assertThrows(java.util.concurrent.ExecutionException::class.java) { pending.get(2, TimeUnit.SECONDS) }
            assertTrue(cancelled.await(2, TimeUnit.SECONDS))
            assertEquals(403, request(identity, remote, "/api/tools", cookie = cookie).status)
            assertEquals(200, request(identity, "127.0.0.1", "/api/tools", cookie = cookie).status)
            assertEquals(200, request(identity, remote, "/api/tools", secure = true, token = token).status)
            server.setAllowInsecureHttp(true)
            assertEquals(200, request(identity, remote, "/api/tools", cookie = cookie).status)
        } finally {
            server.stop(); controller.stop(); pool.shutdownNow()
            clientId?.let(identity::revoke); identity.pairing.clear()
        }
    }
}
