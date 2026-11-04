package ai.nidhoggfgg.phoneuse.server

import ai.nidhoggfgg.phoneuse.core.*
import ai.nidhoggfgg.phoneuse.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.net.ServerSocket
import java.net.Socket
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import java.util.UUID

/** HTTP/1.1 over TLS, one request per connection. Plaintext is never accepted. */
class HttpServer(private val identities: IdentityStore, private val controller: DeviceController,
    private val assets: android.content.res.AssetManager,
    private val serviceInstanceId: String = UUID.randomUUID().toString(),
    private val connected: (String) -> Unit) {
    private val workers = ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, ArrayBlockingQueue(16))
    private val largeUpload = java.util.concurrent.Semaphore(1)
    private val clients = ConcurrentHashMap.newKeySet<Socket>()
    private val deadlines = java.util.concurrent.ScheduledThreadPoolExecutor(1).apply { removeOnCancelPolicy = true }
    @Volatile private var listener: ServerSocket? = null
    fun start(port: Int = 8443) {
        val socket = (identities.tls.context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
            enabledProtocols = arrayOf("TLSv1.3", "TLSv1.2")
        }
        try {
            socket.reuseAddress = true
            socket.bind(InetSocketAddress("0.0.0.0", port), 16)
        } catch (e: Exception) { socket.close(); throw e }
        listener = socket
        Thread({
            while (!socket.isClosed) {
                try {
                    val client = socket.accept()
                    client.soTimeout = 10_000
                    clients.add(client)
                    try { workers.execute {
                        // Includes request parsing, bounded queueing and up to 60 seconds of condition observation.
                        val timeout = deadlines.schedule({ runCatching { client.close() } }, 90, TimeUnit.SECONDS)
                        try { client.use { handle(it) } } finally { timeout.cancel(false); clients.remove(client) }
                    } }
                    catch (_: java.util.concurrent.RejectedExecutionException) { clients.remove(client); client.close() }
                } catch (_: Exception) { if (!socket.isClosed) stop() }
            }
        }, "phone-use-http").start()
    }
    fun stop() {
        listener?.close(); listener = null
        clients.forEach { runCatching { it.close() } }; clients.clear()
        workers.shutdownNow()
        deadlines.shutdownNow()
    }
    private data class Response(val status: Int, val body: JSONObject? = null,
        val text: String? = null, val contentType: String = "application/json; charset=utf-8", val allow: String = "POST",
        val cookie: String? = null)
    private fun cookie(token: String, port: Int) =
        "phoneuse_client_$port=$token; Path=/; Secure; HttpOnly; SameSite=Strict; Max-Age=${if (token.isEmpty()) 0 else 34_560_000}"
    private class HttpError(val status: Int, message: String) : RuntimeException(message)
    private fun handle(socket: Socket) {
        var largeBody = false
        try {
            (socket as SSLSocket).startHandshake()
            val input = BufferedInputStream(socket.inputStream)
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12)
            fun line(): String {
                val bytes = ByteArrayOutputStream()
                while (true) {
                    if (System.nanoTime() > deadline) throw HttpError(408, "Request read deadline exceeded")
                    val b = input.read()
                    if (b < 0) throw HttpError(400, "Unexpected end of request")
                    if (b == 10) break
                    if (bytes.size() >= 8192) throw HttpError(431, "Header too large")
                    bytes.write(b)
                }
                return bytes.toString(Charsets.US_ASCII.name()).removeSuffix("\r")
            }
            val request = line().split(' ')
            if (request.size != 3 || request[2] != "HTTP/1.1") throw HttpError(400, "HTTP/1.1 required")
            val headers = HashMap<String, String>()
            var total = 0
            while (true) {
                val header = line(); if (header.isEmpty()) break
                total += header.length
                if (total > 16_384 || headers.size >= 64) throw HttpError(431, "Headers too large")
                val index = header.indexOf(':')
                if (index <= 0) throw HttpError(400, "Malformed header")
                val key = header.substring(0, index).lowercase()
                if (headers.put(key, header.substring(index + 1).trim()) != null) throw HttpError(400, "Duplicate header")
            }
            val path = request[1]
            // Android ACTION_VIEW launches can mark the public landing page as cross-site.
            // Only GET / is public navigation; scripts, pairing and API still require same origin.
            if (!BrowserAccess.allowed(headers, socket.localAddress.hostAddress.orEmpty(), socket.localPort,
                    publicPage = request[0] == "GET" && path == "/")) {
                throw HttpError(403, "Use the phone's IPv4 address and port; cross-origin access is not allowed")
            }
            if (request[0] == "GET" && path in setOf("/", "/app.js", "/i18n.js")) {
                val file = if (path == "/") "index.html" else path.removePrefix("/")
                val type = if (path == "/") "text/html; charset=utf-8" else "text/javascript; charset=utf-8"
                write(socket, Response(200, text = assets.open("web/$file").bufferedReader().use { it.readText() }, contentType = type))
                return
            }
            // Public identity lets a paired bridge verify an address before sending its token.
            if (path == "/identity") {
                write(socket, if (request[0] == "GET") Response(200, identities.deviceIdentity.describe(serviceInstanceId))
                    else Response(405, json("error" to "Only GET is supported"), allow = "GET"))
                return
            }
            val pairingPath = path.removePrefix("/browser")
            val isPairing = pairingPath in setOf("/pair/request", "/pair/reveal", "/pair/status")
            if (!isPairing && path !in setOf("/api", "/api/tools", "/mcp", "/browser/session", "/browser/forget"))
                throw HttpError(404, "Unknown endpoint")
            val cookieToken = headers["cookie"]?.split(';')?.map { it.trim() }
                ?.singleOrNull { it.startsWith("phoneuse_client_${socket.localPort}=") }?.substringAfter('=')
            val auth = headers["authorization"]
            val token = if (auth != null) auth.takeIf { it.startsWith("Bearer ") }?.removePrefix("Bearer ") else cookieToken
            val client = if (isPairing) null else token?.let(identities::authenticate)
            if (path == "/browser/session" && request[0] == "GET") {
                write(socket, Response(200, json("paired" to (client != null), "client_id" to client),
                    cookie = cookie(if (client != null) token!! else "", socket.localPort)))
                return
            }
            if (!isPairing && client == null) throw HttpError(401, "Invalid or missing client authorization")
            if (request[0] == "GET" && path == "/api/tools") {
                write(socket, Response(200, json("tools" to ToolCatalog.all())))
                return
            }
            if (path == "/api/tools") {
                write(socket, Response(405, json("error" to "Only GET is supported"), allow = "GET"))
                return
            }
            if (request[0] != "POST") throw HttpError(405, "Only POST is supported")
            if (headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json")
                throw HttpError(415, "Content-Type application/json required")
            if (path == "/mcp") {
                val version = headers["mcp-protocol-version"]
                if (version != null && version !in versions) throw HttpError(400, "Unsupported MCP protocol version")
            }
            val body = ByteArrayOutputStream()
            val normalLimit = 65_536
            val bodyLimit = if (client != null && path in setOf("/api", "/mcp")) 4 * 1024 * 1024 else normalLimit
            fun readBytes(size: Int) {
                if (size < 0 || size > bodyLimit - body.size()) throw HttpError(413, "Body exceeds allowed size")
                if (!largeBody && body.size() + size > normalLimit) {
                    if (!largeUpload.tryAcquire()) throw HttpError(429, "Another audio request is being handled")
                    largeBody = true
                }
                var remaining = size
                val buffer = ByteArray(4096)
                while (remaining > 0) {
                    if (System.nanoTime() > deadline) throw HttpError(408, "Request read deadline exceeded")
                    val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                    if (count < 0) throw HttpError(400, "Incomplete body")
                    body.write(buffer, 0, count); remaining -= count
                }
            }
            val transfer = headers["transfer-encoding"]
            if (transfer != null) {
                if (!transfer.equals("chunked", true) || headers.containsKey("content-length")) throw HttpError(400, "Invalid transfer framing")
                var chunks = 0
                while (true) {
                    if (++chunks > 1024) throw HttpError(413, "Too many chunks")
                    val size = line().substringBefore(';').toIntOrNull(16) ?: throw HttpError(400, "Invalid chunk")
                    if (size == 0) { if (line().isNotEmpty()) throw HttpError(400, "Trailers unsupported"); break }
                    readBytes(size)
                    if (line().isNotEmpty()) throw HttpError(400, "Invalid chunk delimiter")
                }
            } else readBytes(headers["content-length"]?.toIntOrNull() ?: throw HttpError(411, "Content-Length required"))
            val raw = body.toString(Charsets.UTF_8.name())
            var depth = 0; var quoted = false; var escaped = false
            raw.forEach { char ->
                if (quoted) {
                    if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
                } else when (char) { '"' -> quoted = true; '{', '[' -> { if (++depth > 64) throw HttpError(400, "JSON nesting too deep") }; '}', ']' -> depth-- }
            }
            val payload = try { JSONObject(raw) } catch (_: Exception) { throw HttpError(400, "Invalid JSON object") }
            if (body.size() > normalLimit) {
                val voice = if (path == "/api") payload.optString("tool") == "send_voice"
                    else payload.optString("method") == "tools/call" && payload.optJSONObject("params")?.optString("name") == "send_voice"
                if (!voice) throw HttpError(413, "Only send_voice accepts bodies over 64 KiB")
            }
            val response = if (isPairing) {
                try {
                    if (pairingPath == "/pair/request") {
                        val browser = path.startsWith("/browser/")
                        if (!browser && payload.optString("protocol") != PairingCrypto.PROTOCOL)
                            fail("PAIRING_PROTOCOL_REQUIRED", "Update the client: secure commit/reveal pairing is required")
                        Response(202, pairingIdentity(identities.pairing.create(payload.string("client_name", 80), socket.inetAddress.hostAddress.orEmpty(),
                            if (browser) null else payload.string("commitment", 64), if (browser) null else identities.tls.pin)))
                    } else if (pairingPath == "/pair/reveal") {
                        Response(200, pairingIdentity(identities.pairing.reveal(payload.string("pairing_id", 80),
                            payload.string("pairing_secret", 128), payload.string("client_nonce", 64))))
                    }
                    else {
                        val result = pairingIdentity(identities.pairing.poll(payload.string("pairing_id", 80), payload.string("pairing_secret", 128)))
                        if (path.startsWith("/browser/") && result.optString("status") == "approved") {
                            val browserToken = result.remove("token") as String
                            result.remove("token_type")
                            Response(200, result, cookie = cookie(browserToken, socket.localPort))
                        } else Response(200, result)
                    }
                } catch (e: DeviceError) {
                    Response(if (e.code == "PAIRING_BUSY") 429 else 400, pairingIdentity(errorResult(e)))
                }
            } else if (path == "/browser/session") {
                // Migrate a previously saved browser Bearer credential without pairing again.
                Response(200, json("paired" to true, "client_id" to client), cookie = cookie(token!!, socket.localPort))
            } else if (path == "/browser/forget") {
                identities.revoke(client!!)
                identities.pairing.revoked(client)
                controller.revokeClient(client)
                Response(200, json("paired" to false), cookie = cookie("", socket.localPort))
            } else {
                connected(client!!)
                if (path == "/mcp") mcp(client, payload)
                else {
                    val name = payload.string("tool")
                    if (payload.has("arguments") && payload.opt("arguments") !is JSONObject) throw HttpError(400, "arguments must be an object")
                    val args = payload.optJSONObject("arguments") ?: JSONObject()
                    Response(200, callTool(client, name, args))
                }
            }
            write(socket, response)
        } catch (e: HttpError) { runCatching { write(socket, Response(e.status, json("error" to e.message))) } }
        catch (e: DeviceError) { runCatching { write(socket, Response(400, errorResult(e))) } }
        catch (e: Exception) {
            android.util.Log.w("PhoneUseHttp", "HTTP request failed: ${e.javaClass.simpleName}", e)
            runCatching { write(socket, Response(500, json("error" to "Request failed"))) }
        } finally { if (largeBody) largeUpload.release() }
    }
    private fun mcp(client: String, request: JSONObject): Response {
        val id = request.opt("id")
        fun rpcError(code: Int, message: String) = Response(200, json("jsonrpc" to "2.0", "id" to id,
            "error" to json("code" to code, "message" to message)))
        if (request.optString("jsonrpc") != "2.0" || request.opt("method") !is String) return rpcError(-32600, "Invalid Request")
        val method = request.getString("method")
        if (!request.has("id")) {
            // Notifications cannot execute actions. No implicit control cancellation for JSON-RPC cancellation IDs.
            return Response(202)
        }
        if (id !is String && id !is Number) return rpcError(-32600, "Invalid request id")
        if (request.has("params") && request.opt("params") !is JSONObject) return rpcError(-32602, "params must be an object")
        val params = request.optJSONObject("params") ?: JSONObject()
        val result = when (method) {
            "initialize" -> json("protocolVersion" to params.optString("protocolVersion").takeIf { it in versions }.orEmpty().ifEmpty { "2025-11-25" },
                "capabilities" to json("tools" to json("listChanged" to false)),
                "serverInfo" to json("name" to "phone-use", "version" to BuildConfig.VERSION_NAME),
                "instructions" to "Paired clients can call actions directly. Always supply a unique request_id. Read tool results; Android acceptance is not business success.")
            "ping" -> JSONObject()
            "tools/list" -> json("tools" to ToolCatalog.all())
            "tools/call" -> {
                val name = params.opt("name") as? String ?: return rpcError(-32602, "Tool name required")
                if (params.has("arguments") && params.opt("arguments") !is JSONObject) return rpcError(-32602, "arguments must be an object")
                if ((0 until ToolCatalog.all().length()).none { ToolCatalog.all().getJSONObject(it).getString("name") == name })
                    return rpcError(-32602, "Unknown tool")
                val value = callTool(client, name, params.optJSONObject("arguments") ?: JSONObject())
                resultIdentity(serializeMcpToolResult(value))
            }
            else -> return rpcError(-32601, "Method not found")
        }
        return Response(200, json("jsonrpc" to "2.0", "id" to id, "result" to result))
    }
    private fun pairingIdentity(result: JSONObject): JSONObject = result.apply {
        val identity = identities.deviceIdentity.describe(serviceInstanceId)
        identity.keys().forEach { put(it, identity.get(it)) }
    }

    private fun callTool(client: String, name: String, args: JSONObject): JSONObject {
        fun mismatch(code: String, reason: String, expected: String, actual: String) = resultIdentity(json(
            "state" to "failed", "action_executed" to false,
            "error" to json("code" to code, "reason" to reason,
                "message" to "Device or service identity changed; reconnect and obtain a new observation",
                "expected" to expected, "actual" to actual)))
        for ((field, current) in listOf("device_id" to identities.deviceIdentity.id, "service_instance_id" to serviceInstanceId)) {
            if (!args.has(field)) continue
            val supplied = args.opt(field) as? String
            if (supplied.isNullOrEmpty()) return resultIdentity(errorResult(DeviceError("INVALID_ARGUMENT", "$field must be a non-empty string"))
                .put("action_executed", false))
            if (supplied != current) return if (field == "device_id") mismatch("DEVICE_MISMATCH", "device_changed", supplied, current)
                else mismatch("STALE_OBSERVATION", "service_restarted", supplied, current)
        }
        val arguments = JSONObject(args.toString()).apply { remove("device_id"); remove("service_instance_id") }
        // Controller retains action objects for deduplication. Decorate a copy, never those shared objects.
        return resultIdentity(JSONObject(controller.call(client, name, arguments).toString()))
    }

    private fun resultIdentity(result: JSONObject): JSONObject = attachResultIdentity(result, identities.deviceIdentity.id, serviceInstanceId)

    private fun write(socket: Socket, response: Response) {
        val body = response.body?.let { if (it.has("jsonrpc")) it else resultIdentity(it) }
        val bytes = (response.text ?: body?.toString())?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
        val reason = when (response.status) { 200 -> "OK"; 202 -> "Accepted"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"; else -> "Error" }
        val headers = "HTTP/1.1 ${response.status} $reason\r\nContent-Type: ${response.contentType}\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\nCache-Control: no-store\r\nX-Content-Type-Options: nosniff\r\n" +
            "Content-Security-Policy: default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; connect-src 'self'; img-src data:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'\r\nReferrer-Policy: no-referrer\r\n" +
            (if (response.status == 405) "Allow: ${response.allow}\r\n" else "") +
            (response.cookie?.let { "Set-Cookie: $it\r\n" } ?: "") + "\r\n"
        socket.outputStream.write(headers.toByteArray(Charsets.US_ASCII))
        socket.outputStream.write(bytes); socket.outputStream.flush()
    }
    companion object { val versions = setOf("2025-03-26", "2025-06-18", "2025-11-25") }
}

private val nestedResultKeys = listOf("request", "execution", "observation", "recovery_observation", "last_observation")

/** Decorates protocol result containers only; node metadata is deliberately not treated as a result. */
internal fun attachResultIdentity(result: JSONObject, deviceId: String, serviceInstanceId: String): JSONObject = result.apply {
    put("device_id", deviceId)
    put("service_instance_id", serviceInstanceId)
    nestedResultKeys.forEach { key -> optJSONObject(key)?.let { attachResultIdentity(it, deviceId, serviceInstanceId) } }
}

/** Traverses retained and recovery snapshots without mutating objects kept in the deduplication cache. */
internal fun serializeMcpToolResult(value: JSONObject): JSONObject {
    val copy = JSONObject(value.toString())
    val content = JSONArray()
    var incomplete = false
    fun visit(result: JSONObject) {
        if (result.has("error") || result.optString("observation_status") in setOf("failed", "condition_timeout") ||
            result.optJSONObject("screenshot")?.opt("available") == false || result.optJSONObject("tree")?.opt("available") == false ||
            result.optJSONObject("condition")?.optString("status")?.let { it != "satisfied" } == true) incomplete = true
        result.optJSONObject("screenshot")?.let { screenshot ->
            if (screenshot.optBoolean("available") && screenshot.opt("data_base64") is String) {
                content.put(json("type" to "image", "mimeType" to screenshot.getString("mime_type"), "data" to screenshot.remove("data_base64")))
            }
        }
        nestedResultKeys.forEach { key -> result.optJSONObject(key)?.let(::visit) }
    }
    visit(copy)
    content.put(json("type" to "text", "text" to copy.toString()))
    return json("content" to content, "isError" to incomplete)
}
