package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.*
import ai.nidhoggfgg.phoneuse.device.AndroidBackend
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

/** Real production HTTP server running on Android. */
@RunWith(AndroidJUnit4::class)
class HttpServerInstrumentedTest {
    private fun tlsSocket(identity: IdentityStore, port: Int): Socket {
        val trust = object : javax.net.ssl.X509TrustManager {
            override fun getAcceptedIssuers() = emptyArray<java.security.cert.X509Certificate>()
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = throw java.security.cert.CertificateException("Client certificates unsupported")
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                if (chain.isEmpty() || ai.nidhoggfgg.phoneuse.server.PairingCrypto.hash(chain[0].publicKey.encoded) != identity.tls.pin)
                    throw java.security.cert.CertificateException("Wrong server key")
            }
        }
        val context = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        return context.socketFactory.createSocket("127.0.0.1", port)
    }

    @Test fun identityIsAttachedToRetainedActionsAndFailedFollowupObservations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val identity = IdentityStore(context)
        var actions = 0
        val backend = object : DeviceBackend {
            override fun capabilities() = JSONObject()
            override fun observe(args: JSONObject): JSONObject = fail("OBSERVATION_INCONSISTENT", "Test followup failed")
            override fun listApps() = JSONObject()
            override fun waitForChange(args: JSONObject, check: () -> Unit) = JSONObject()
            override fun act(name: String, args: JSONObject, check: () -> Unit): JSONObject {
                check(); actions++
                return json("accepted" to true)
            }
        }
        val controller = DeviceController(backend, SystemClock::elapsedRealtime, identity::exists)
        val server = HttpServer(identity, controller, context.assets, "test-instance") {}
        val created = identity.pairing.create("Identity metadata test", "localhost")
        identity.pairing.decide(created.getString("pairing_id"), true)
        val credential = identity.pairing.poll(created.getString("pairing_id"), created.getString("pairing_secret"))
        fun call(tool: String, args: JSONObject): JSONObject = tlsSocket(identity, 18444).use { socket ->
            socket.soTimeout = 10_000
            val body = json("tool" to tool, "arguments" to args).toString().toByteArray(Charsets.UTF_8)
            val header = "POST /api HTTP/1.1\r\nHost: 127.0.0.1:18444\r\nAuthorization: Bearer ${credential.getString("token")}\r\n" +
                "Content-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            socket.outputStream.write(header.toByteArray(Charsets.US_ASCII))
            socket.outputStream.write(body); socket.outputStream.flush()
            JSONObject(socket.inputStream.readBytes().toString(Charsets.UTF_8).substringAfter("\r\n\r\n"))
        }
        fun assertIdentity(result: JSONObject) {
            assertEquals(identity.deviceIdentity.id, result.getString("device_id"))
            assertEquals("test-instance", result.getString("service_instance_id"))
        }
        try {
            server.start(18444)
            val args = json("request_id" to "identity-action", "observe_after" to true)
            val result = call("home", args)
            assertEquals("executed", result.getString("state"))
            assertIdentity(result)
            assertIdentity(result.getJSONObject("execution"))
            assertIdentity(result.getJSONObject("observation"))
            assertEquals("OBSERVATION_INCONSISTENT", result.getJSONObject("observation").getJSONObject("error").getString("code"))
            assertIdentity(call("home", args))
            assertEquals(1, actions)
            val retained = call("get_status", json("request_id" to "identity-action")).getJSONObject("request")
            assertIdentity(retained)
            assertIdentity(retained.getJSONObject("observation"))
        } finally {
            identity.revoke(credential.getString("client_id"))
            identity.pairing.clear(); server.stop(); controller.stop()
        }
    }

    @Test fun httpServesWebPairingApiAndMcp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var identity = IdentityStore(context)
        var controller = DeviceController(AndroidBackend(context), SystemClock::elapsedRealtime, identity::exists) {}
        var server = HttpServer(identity, controller, context.assets) {}
        var responseHeaders = ""
        var responseBytes = ByteArray(0)
        fun request(path: String, body: JSONObject? = null, token: String? = null, site: String = "same-origin", cookie: String? = null): Pair<Int, String> {
            return tlsSocket(identity, 18443).use { socket ->
                socket.soTimeout = 10_000
                val bytes = body?.toString()?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                val header = "${if (body == null) "GET" else "POST"} $path HTTP/1.1\r\nHost: 127.0.0.1:18443\r\n" +
                    "Origin: https://127.0.0.1:18443\r\nSec-Fetch-Site: $site\r\nConnection: close\r\n" +
                    (token?.let { "Authorization: Bearer $it\r\n" } ?: "") +
                    (cookie?.let { "Cookie: $it\r\n" } ?: "") +
                    (if (body != null) "Content-Type: application/json\r\nContent-Length: ${bytes.size}\r\n" else "") + "\r\n"
                socket.outputStream.write(header.toByteArray(Charsets.US_ASCII))
                socket.outputStream.write(bytes); socket.outputStream.flush()
                val raw = socket.inputStream.readBytes()
                val response = raw.toString(Charsets.UTF_8)
                responseHeaders = response.substringBefore("\r\n\r\n")
                responseBytes = raw.copyOfRange(responseHeaders.toByteArray(Charsets.UTF_8).size + 4, raw.size)
                response.substringBefore("\r\n").split(' ')[1].toInt() to response.substringAfter("\r\n\r\n")
            }
        }
        var clientId: String? = null
        try {
            server.start(18443)
            // Loopback HTTP shares the listener with TLS and still requires authorization.
            Socket("127.0.0.1", 18443).use { plain ->
                plain.soTimeout = 2000
                plain.outputStream.write("GET /identity HTTP/1.1\r\nHost: 127.0.0.1:18443\r\n\r\n".toByteArray())
                val response = runCatching { plain.inputStream.readBytes().toString(Charsets.UTF_8) }.getOrDefault("")
                assertTrue(response.contains("200 OK"))
                assertTrue(response.contains("device_id"))
            }
            val page = request("/")
            assertEquals(200, page.first); assertTrue(page.second.contains("Phone Use"))
            assertEquals(200, request("/app.js").first)
            val translations = request("/i18n.js")
            assertEquals(200, translations.first)
            assertTrue(translations.second.contains("PhoneUseI18n"))
            assertEquals(200, request("/", site = "cross-site").first)
            val onboarding = request("/connect", site = "cross-site")
            assertEquals(200, onboarding.first)
            assertTrue(onboarding.second.contains("https://127.0.0.1:18443/skills/phone-use.zip"))
            val manifest = JSONObject(request("/skills/manifest.json").second)
            assertFalse(manifest.has("token"))
            assertFalse(manifest.has("device_id"))
            assertEquals(200, request(manifest.getString("archive")).first)
            assertTrue(responseHeaders.contains("Content-Type: application/zip"))
            assertTrue(responseHeaders.contains("Content-Disposition: attachment; filename=\"phone-use.zip\""))
            assertTrue(responseHeaders.contains("Content-Length: ${responseBytes.size}\r\n"))
            assertEquals(manifest.getInt("size"), responseBytes.size)
            assertEquals(manifest.getString("sha256"), PairingCrypto.hash(responseBytes))
            val entries = mutableMapOf<String, ByteArray>()
            java.util.zip.ZipInputStream(responseBytes.inputStream()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (!entry.isDirectory) entries[entry.name] = zip.readBytes()
                }
            }
            assertTrue(entries.keys.containsAll(listOf("phone-use/SKILL.md", "phone-use/scripts/phoneuse_client.py", "phone-use/references/api.md")))
            assertEquals(9, entries.size)
            assertEquals(200, request(manifest.getString("skill")).first)
            assertArrayEquals(entries.getValue("phone-use/SKILL.md"), responseBytes)
            assertEquals(405, request("/skills/phone-use.zip", JSONObject()).first)
            assertEquals(403, request("/skills/manifest.json", site = "cross-site").first)
            assertEquals(404, request("/skills/../identity").first)
            assertEquals(403, request("/pair/request", json("client_name" to "cross-site"), site = "cross-site").first)
            assertEquals(401, request("/api/tools").first)
            val publicIdentity = JSONObject(request("/identity").second)
            val deviceId = publicIdentity.getString("device_id")
            val instanceId = publicIdentity.getString("service_instance_id")
            assertEquals(setOf("device_id", "name", "model", "android_version", "is_emulator", "service_instance_id"), publicIdentity.keys().asSequence().toSet())
            assertEquals(deviceId, IdentityStore(context).deviceIdentity.id)
            assertEquals(405, request("/identity", JSONObject()).first)
            assertEquals(400, request("/pair/request", json("client_name" to "old protocol")).first)
            val clientNonce = "11".repeat(32)
            val pending = request("/pair/request", json("client_name" to "HTTPS regression test", "protocol" to PairingCrypto.PROTOCOL,
                "commitment" to PairingCrypto.commitment(identity.tls.pin, clientNonce)))
            assertEquals(202, pending.first)
            val created = JSONObject(pending.second)
            assertEquals(deviceId, created.getString("device_id"))
            assertEquals(instanceId, created.getString("service_instance_id"))
            val args = json("pairing_id" to created.getString("pairing_id"), "pairing_secret" to created.getString("pairing_secret"))
            assertEquals("committed", JSONObject(request("/pair/status", args).second).getString("status"))
            assertTrue(identity.pairing.pending().isEmpty())
            val revealArgs = JSONObject(args.toString()).put("client_nonce", clientNonce)
            val reveal = JSONObject(request("/pair/reveal", revealArgs).second)
            assertEquals("pending", reveal.getString("status"))
            assertFalse(reveal.has("verification_code"))
            assertEquals(PairingCrypto.code(identity.tls.pin, clientNonce, created.getString("server_nonce")), identity.pairing.pending().single().verificationCode)
            identity.pairing.decide(created.getString("pairing_id"), true)
            val credential = JSONObject(request("/pair/status", args).second)
            assertEquals(deviceId, credential.getString("device_id"))
            clientId = credential.getString("client_id")
            val token = credential.getString("token")
            assertTrue(JSONObject(request("/api/tools", token = token).second).getJSONArray("tools").length() > 0)
            val status = request("/api", json("tool" to "get_status"), token)
            assertEquals(200, status.first); assertFalse(JSONObject(status.second).has("error"))
            assertEquals(deviceId, JSONObject(status.second).getString("device_id"))
            val wrongDevice = JSONObject(request("/api", json("tool" to "get_status", "arguments" to json("device_id" to "another-device")), token).second)
            assertEquals("DEVICE_MISMATCH", wrongDevice.getJSONObject("error").getString("code"))
            assertFalse(wrongDevice.getBoolean("action_executed"))
            val wrongInstance = JSONObject(request("/api", json("tool" to "get_status", "arguments" to json("service_instance_id" to "old-service")), token).second)
            assertEquals("service_restarted", wrongInstance.getJSONObject("error").getString("reason"))
            val routed = JSONObject(request("/api", json("tool" to "get_status", "arguments" to json("device_id" to deviceId, "service_instance_id" to instanceId)), token).second)
            assertFalse(routed.has("error"))
            val rpc = request("/mcp", json("jsonrpc" to "2.0", "id" to 1, "method" to "tools/list"), token)
            assertEquals(200, rpc.first); assertTrue(JSONObject(rpc.second).has("result"))
            val toolRpc = JSONObject(request("/mcp", json("jsonrpc" to "2.0", "id" to 2, "method" to "tools/call",
                "params" to json("name" to "get_status")), token).second).getJSONObject("result")
            assertEquals(deviceId, toolRpc.getString("device_id"))
            assertEquals(instanceId, JSONObject(toolRpc.getJSONArray("content").getJSONObject(0).getString("text")).getString("service_instance_id"))
            val browserPair = JSONObject(request("/browser/pair/status", args).second)
            assertFalse(browserPair.has("token")); assertFalse(browserPair.has("token_type"))
            val cookieHeader = responseHeaders.lineSequence().first { it.startsWith("Set-Cookie:") }.substringAfter(": ")
            assertTrue(cookieHeader.contains("Secure")); assertTrue(cookieHeader.contains("HttpOnly")); assertTrue(cookieHeader.contains("SameSite=Strict"))
            assertTrue(cookieHeader.contains("Max-Age=34560000"))
            val cookie = cookieHeader.substringBefore(';')
            assertTrue(JSONObject(request("/browser/session", cookie = cookie).second).getBoolean("paired"))
            assertFalse(responseHeaders.contains("Set-Cookie:", ignoreCase = true))
            // An older tab can check before receiving the approval cookie. Its response must
            // never clear credentials that another request has just installed in the browser.
            assertFalse(JSONObject(request("/browser/session").second).getBoolean("paired"))
            assertFalse(responseHeaders.contains("Set-Cookie:", ignoreCase = true))
            assertFalse(JSONObject(request("/browser/session", cookie = "phoneuse_client_18443=invalid").second).getBoolean("paired"))
            assertFalse(responseHeaders.contains("Set-Cookie:", ignoreCase = true))
            assertEquals(200, request("/api/tools", cookie = cookie).first)
            assertEquals(403, request("/api", json("tool" to "get_status"), site = "cross-site", cookie = cookie).first)
            // Recreate every service-owned object; completed pairing must outlive them all.
            server.stop(); controller.stop(); identity.pairing.clear()
            val originalPin = identity.tls.pin
            identity = IdentityStore(context)
            assertEquals(originalPin, identity.tls.pin)
            controller = DeviceController(AndroidBackend(context), SystemClock::elapsedRealtime, identity::exists) {}
            server = HttpServer(identity, controller, context.assets) {}
            server.start(18443)
            val restartedIdentity = JSONObject(request("/identity").second)
            assertEquals(deviceId, restartedIdentity.getString("device_id"))
            assertNotEquals(instanceId, restartedIdentity.getString("service_instance_id"))
            assertTrue(JSONObject(request("/browser/session", cookie = cookie).second).getBoolean("paired"))
            assertEquals(200, request("/api", json("tool" to "get_status"), cookie = cookie).first)
            assertEquals(200, request("/api/tools", token = token).first)
            // Existing Bearer tabs can migrate to a persistent cookie without a new request.
            assertTrue(JSONObject(request("/browser/session", JSONObject(), token).second).getBoolean("paired"))
            assertTrue(responseHeaders.contains("Set-Cookie: $cookie;"))
            assertEquals(200, request("/browser/forget", JSONObject(), cookie = cookie).first)
            assertTrue(responseHeaders.contains("Max-Age=0"))
            assertEquals(401, request("/api/tools", cookie = cookie).first)
            assertEquals(401, request("/api/tools", token = token).first)
            assertFalse(JSONObject(request("/browser/session", cookie = cookie).second).getBoolean("paired"))
            assertFalse(responseHeaders.contains("Set-Cookie:", ignoreCase = true))
            assertFalse(IdentityStore(context).exists(clientId))
        } finally {
            clientId?.let { identity.revoke(it) }
            identity.pairing.clear(); server.stop(); controller.stop()
        }
    }
}
