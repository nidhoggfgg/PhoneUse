package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.*
import ai.nidhoggfgg.phoneuse.server.HttpServer
import ai.nidhoggfgg.phoneuse.server.IdentityStore
import ai.nidhoggfgg.phoneuse.server.PairingCrypto
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/** Real TLS/Keystore, HTTP parsing and socket handoff, without controlling the device UI. */
@RunWith(AndroidJUnit4::class)
class HttpServerConcurrencyInstrumentedTest {
    @Test fun saturatedRequestsDoNotDelayTlsIdentityStatusOrCancellation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = IdentityStore(context)
        val entered = CountDownLatch(8)
        val release = CountDownLatch(1)
        val backend = object : DeviceBackend {
            override fun capabilities() = JSONObject()
            override fun observe(args: JSONObject) = JSONObject()
            override fun listApps() = JSONObject()
            override fun act(name: String, args: JSONObject, check: () -> Unit) = JSONObject()
            override fun waitForChange(args: JSONObject, check: () -> Unit): JSONObject {
                entered.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "Test did not release the request" }
                check()
                return json("changed" to true)
            }
        }
        val controller = DeviceController(backend, SystemClock::elapsedRealtime, identity::exists)
        val server = HttpServer(identity, controller, context.assets, "concurrency-test") {}
        val created = identity.pairing.create("Concurrency regression", "localhost")
        identity.pairing.decide(created.getString("pairing_id"), true)
        val credential = identity.pairing.poll(created.getString("pairing_id"), created.getString("pairing_secret"))
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) =
                throw java.security.cert.CertificateException("Client certificates unsupported")
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                if (chain.isEmpty() || PairingCrypto.hash(chain[0].publicKey.encoded) != identity.tls.pin)
                    throw java.security.cert.CertificateException("Wrong server key")
            }
        }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        fun request(path: String, body: JSONObject? = null): Pair<Int, JSONObject> =
            tls.socketFactory.createSocket("127.0.0.1", 18445).use { socket ->
                socket.soTimeout = 15_000
                val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val header = "${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: 127.0.0.1:18445\r\n" +
                    "Authorization: Bearer ${credential.getString("token")}\r\nConnection: close\r\n" +
                    (if (body != null) "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" else "") + "\r\n"
                socket.outputStream.write(header.toByteArray(Charsets.US_ASCII))
                socket.outputStream.write(bytes)
                socket.outputStream.flush()
                val raw = socket.inputStream.readBytes().toString(Charsets.UTF_8)
                raw.substringBefore("\r\n").split(' ')[1].toInt() to JSONObject(raw.substringAfter("\r\n\r\n"))
            }
        fun fast(label: String, call: () -> Pair<Int, JSONObject>): Pair<Int, JSONObject> {
            val start = SystemClock.elapsedRealtime()
            val result = call()
            val elapsed = SystemClock.elapsedRealtime() - start
            android.util.Log.i("PhoneUseConcurrencyTest", "$label: ${elapsed}ms")
            assertTrue("$label took ${elapsed}ms", elapsed < 1000)
            return result
        }
        val callers = Executors.newFixedThreadPool(8)
        try {
            server.start(18445)
            assertEquals(200, request("/identity").first) // Warm up TLS/Keystore before measuring contention.
            val wait = json("tool" to "wait_for_change", "arguments" to json("ui_version" to 0, "timeout_ms" to 10_000))
            val pending = (0 until 8).map { callers.submit<Pair<Int, JSONObject>> { request("/api", wait) } }
            assertTrue("All request workers must be occupied", entered.await(10, TimeUnit.SECONDS))
            assertTrue(pending.none { it.isDone })
            assertEquals(200, fast("identity under load") { request("/identity") }.first)
            assertEquals(200, fast("status under load") { request("/api", json("tool" to "get_status")) }.first)
            assertEquals(200, fast("cancel under load") { request("/api", json("tool" to "cancel")) }.first)
            val busy = fast("overload response") { request("/api", wait) }
            assertEquals(503, busy.first)
            assertEquals("BUSY", busy.second.getJSONObject("error").getString("code"))
            assertFalse(busy.second.getBoolean("request_admitted"))
            assertTrue(busy.second.isNull("action_executed"))
            val rpcBusy = fast("MCP overload response") { request("/mcp", json("jsonrpc" to "2.0", "id" to 1,
                "method" to "tools/call", "params" to json("name" to "wait_for_change", "arguments" to wait.getJSONObject("arguments")))) }
            assertEquals(503, rpcBusy.first)
            // Successful handoff must keep the connection open until the real result is available.
            release.countDown()
            pending.forEach {
                val result = it.get(3, TimeUnit.SECONDS)
                assertEquals(200, result.first)
                assertTrue(result.second.getBoolean("changed"))
            }
            assertEquals(200, request("/api", wait).first)
        } finally {
            release.countDown()
            callers.shutdownNow()
            server.stop(); controller.stop()
            identity.revoke(credential.getString("client_id")); identity.pairing.clear()
        }
    }

    @Test fun excessConnectionsAreClosedWithoutWaitingForSilentPeers() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = IdentityStore(context)
        val backend = object : DeviceBackend {
            override fun capabilities() = JSONObject()
            override fun observe(args: JSONObject) = JSONObject()
            override fun listApps() = JSONObject()
            override fun waitForChange(args: JSONObject, check: () -> Unit) = JSONObject()
            override fun act(name: String, args: JSONObject, check: () -> Unit) = JSONObject()
        }
        val controller = DeviceController(backend, SystemClock::elapsedRealtime, identity::exists)
        val server = HttpServer(identity, controller, context.assets) {}
        val silent = mutableListOf<Socket>()
        try {
            server.start(18446)
            repeat(8) { silent.add(Socket("127.0.0.1", 18446)) }
            val start = SystemClock.elapsedRealtime()
            Socket("127.0.0.1", 18446).use { extra ->
                extra.soTimeout = 2000
                assertEquals(-1, extra.inputStream.read())
            }
            assertTrue("Excess connection waited for the 10 s protocol timeout", SystemClock.elapsedRealtime() - start < 1000)
        } finally {
            silent.forEach { it.close() }
            server.stop(); controller.stop()
        }
    }
}
