package ai.nidhoggfgg.phoneuse.core

import org.json.JSONObject
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.UUID

class DeviceController(
    private val backend: DeviceBackend,
    private val clock: () -> Long,
    private val authorized: (String) -> Boolean,
    private val changed: () -> Unit = {}
) {
    val gate = ControlGate()
    private val executor = DeviceWorkQueue()
    private data class Request(val client: String, val id: String, val signature: String, val created: Long,
        val result: CompletableFuture<JSONObject> = CompletableFuture(),
        @Volatile var phase: String = "accepted", @Volatile var cancelled: Boolean = false,
        @Volatile var actionExecuted: Boolean? = false,
        @Volatile var observationStatus: String = "not_requested",
        @Volatile var observationPurpose: String? = null,
        @Volatile var retainedChars: Int = 0)
    private val requests = LinkedHashMap<String, Request>()
    private val retentionBudget = 8_000_000L
    private val terminalResultReserve = 4096
    private fun retainedSize(request: Request) = maxOf(request.retainedChars, terminalResultReserve).toLong()
    private data class Reservation(val client: String, val id: String, val expires: Long)
    private var reservation: Reservation? = null // Guarded by requests, including queue admission.
    @Volatile private var active: Request? = null
    val actionNames = setOf("tap", "long_press", "swipe", "scroll", "input_text", "back", "home", "recents", "launch_app")

    fun call(client: String, name: String, args: JSONObject, checkTransport: () -> Unit = {}): JSONObject {
        try {
            checkTransport()
            checkAccess(client)
            ToolCatalog.validate(name, args)
            return when (name) {
                "get_capabilities" -> backend.capabilities()
                "get_status" -> status(client, args.optString("request_id"))
                "cancel" -> cancel(client, args)
                "acquire_device" -> acquireDevice(client, args)
                "release_device" -> releaseDevice(client, args)
                "observe", "list_apps" -> serialRead(client, name, args, checkTransport)
                "wait_for_change" -> {
                    val generation = gate.generation
                    backend.waitForChange(args) { checkTransport(); checkAccess(client); gate.check(generation) }
                }
                in actionNames -> action(client, name, args, checkTransport)
                else -> fail("UNKNOWN_TOOL", "Unknown tool: $name")
            }
        } catch (e: DeviceError) {
            return errorResult(e).apply {
                if (name in actionNames) {
                    put("action_executed", false)
                    if (args.has("request_id")) put("request_id", args.opt("request_id"))
                    addRecovery(this)
                }
            }
        }
    }
    private fun checkAccess(client: String) {
        if (gate.stopped) fail("STOPPED", "Service is stopped")
        if (!authorized(client)) fail("UNAUTHORIZED", "Client credential was revoked")
    }
    private fun serialRead(client: String, name: String, args: JSONObject, checkTransport: () -> Unit): JSONObject {
        val generation = gate.generation
        val result = CompletableFuture<JSONObject>()
        try { executor.execute(onExpired = {
            val busy = DeviceError("BUSY", "Device queue wait exceeded 1000 ms; read not started")
            result.complete(if (name == "observe") observationFailure(busy, args) else errorResult(busy))
        }) {
            try {
                checkTransport()
                checkAccess(client)
                if (gate.generation != generation || result.isDone) fail("CANCELLED", "Read invalidated")
                val check = {
                    checkTransport()
                    checkAccess(client)
                    gate.check(generation)
                    if (result.isDone) fail("CANCELLED", "Read invalidated")
                }
                val value = if (name == "observe") backend.observe(args, client, check) else backend.listApps()
                checkTransport()
                checkAccess(client)
                // A backend failure may include a valid earlier snapshot. Preserve it after cancellation,
                // while a revoked credential or stopped service still cannot receive scene data.
                if (!value.has("error") && gate.generation != generation) fail("CANCELLED", "Read invalidated")
                if (name == "observe") { normalizeObservation(value); addRecovery(value) }
                result.complete(value)
            } catch (e: Exception) { result.complete(if (name == "observe") observationFailure(e, args) else errorResult(e)) }
        } } catch (_: java.util.concurrent.RejectedExecutionException) { fail("BUSY", "Device queue is full") }
        // A condition observation can legitimately outlive the action's 15-second response window.
        // The backend owns the collection budget; queue admission is separately capped at 1 s.
        val waitMs = if (name == "observe") args.optLong("timeout_ms", if (args.has("wait_until")) 10_000 else 20_000) + 15_000 else 15_000
        return try { result.get(waitMs, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) {
            result.complete(if (name == "observe") observationFailure(DeviceError("TIMEOUT", "Observation queue or collection timed out"), args)
                else errorResult(DeviceError("TIMEOUT", "Read timed out")))
            result.get()
        }
    }
    private fun action(client: String, name: String, args: JSONObject, checkTransport: () -> Unit): JSONObject {
        val id = args.string("request_id", 128)
        val key = "$client:$id"
        val signature = name + canonical(args)
        val request: Request
        synchronized(requests) {
            requests.entries.removeAll { it.value.result.isDone && clock() - it.value.created > 600_000 }
            val old = requests[key]
            if (old != null) {
                if (old.signature != signature) fail("REQUEST_ID_CONFLICT", "Request ID was used with different arguments")
                request = old
            } else {
                checkReservation(client, args)
                if (requests.size >= 256 || requests.values.sumOf(::retainedSize) + terminalResultReserve > retentionBudget)
                    fail("BUSY", "Deduplication cache full; retry later")
                val generation = gate.generation
                gate.check(generation)
                request = Request(client, id, signature, clock())
                requests[key] = request
                try { executor.execute(onExpired = {
                    synchronized(requests) {
                        if (!request.result.isDone) complete(request,
                            errorResult(DeviceError("BUSY", "Device queue wait exceeded 1000 ms; action not started"))
                                .put("request_id", id).put("action_executed", false))
                    }
                }) { execute(request, name, args, generation, checkTransport) } }
                catch (_: java.util.concurrent.RejectedExecutionException) {
                    complete(request, errorResult(DeviceError("BUSY", "Device queue is full"))
                        .put("request_id", id).put("action_executed", false))
                }
            }
        }
        return try { request.result.get(15, TimeUnit.SECONDS) } catch (_: TimeoutException) {
            request.result.getNow(pending(request))
        }
    }
    private fun execute(request: Request, name: String, args: JSONObject, generation: Long, checkTransport: () -> Unit) {
        if (request.result.isDone) return
        val check = {
            checkTransport()
            checkAccess(request.client)
            gate.check(generation)
            if (request.cancelled) fail("CANCELLED", "Request cancelled")
            checkReservation(request.client, args)
        }
        var dispatched = false
        try {
            check()
            active = request
            request.phase = "executing"
            request.actionExecuted = null
            dispatched = true
            val value = backend.act(name, args, request.client, check)
            request.actionExecuted = true
            val result = json("state" to "executed", "request_id" to request.id, "action_executed" to true, "execution" to value)
            if (args.optBoolean("observe_after", false)) {
                result.put("observation", observeForRequest(request, args, "after_action", check))
                result.put("observation_status", request.observationStatus)
            }
            complete(request, result)
        } catch (e: Exception) {
            val state = when ((e as? DeviceError)?.code) {
                "CANCELLED", "UNAUTHORIZED", "STOPPED", "TRANSPORT_DISABLED" -> "cancelled"
                "RESULT_UNKNOWN" -> "unknown"
                else -> if (e is DeviceError) "failed" else "unknown"
            }
            val result = errorResult(e, state).put("request_id", request.id)
            if (!result.has("action_executed")) result.put("action_executed", if (!dispatched) false else JSONObject.NULL)
            request.actionExecuted = result.opt("action_executed") as? Boolean
            // Only terminal, explicitly non-executed target-validation errors may read a recovery snapshot.
            // Access, generation, cancellation and reservation checks also govern this read.
            if (args.optBoolean("observe_on_rejection") && result.opt("action_executed") == false &&
                state == "failed" && (e as? DeviceError)?.code in recoveryObservationErrors) {
                result.put("recovery_observation", observeForRequest(request, args, "after_rejection", check))
                result.put("observation_status", request.observationStatus)
            }
            complete(request, result)
        } finally { active = null }
    }
    private fun observationFailure(error: Exception, args: JSONObject): JSONObject = errorResult(error).apply {
        put("observation_status", "failed")
        if (args.has("wait_until")) put("condition", json("status" to "unknown", "timed_out" to ((error as? DeviceError)?.code in setOf("TIMEOUT", "OBSERVATION_TIMEOUT"))))
        addRecovery(this)
    }
    private fun observeForRequest(request: Request, args: JSONObject, purpose: String, check: () -> Unit): JSONObject {
        request.observationPurpose = purpose
        request.observationStatus = "observing"
        val options = args.optJSONObject("observation_options") ?: JSONObject()
        val observation = try {
            check()
            backend.observe(JSONObject(options.toString()), request.client, check).also {
                checkAccess(request.client)
                if (!it.has("error")) check()
            }
        } catch (e: Exception) { observationFailure(e, options) }
        normalizeObservation(observation)
        request.observationStatus = observation.getString("observation_status")
        return observation
    }
    private fun normalizeObservation(observation: JSONObject) {
        val status = when {
            observation.has("error") -> "failed"
            observation.has("observation_status") -> observation.getString("observation_status")
            observation.optJSONObject("condition")?.optString("status")?.let { it != "satisfied" } == true -> "condition_timeout"
            else -> "complete"
        }
        observation.put("observation_status", status)
    }
    private fun pending(request: Request): JSONObject = json(
        "state" to request.phase, "request_id" to request.id, "action_executed" to request.actionExecuted,
        "message" to "Still pending; query get_status with this request_id. Do not replay with a new ID."
    ).apply {
        if (request.observationPurpose != null) put("observation_status", request.observationStatus).put("observation_purpose", request.observationPurpose)
    }.also(::addRecovery)
    private fun complete(request: Request, result: JSONObject) = synchronized(requests) {
        addRecovery(result)
        val remaining = retentionBudget - requests.values.filter { it !== request }.sumOf(::retainedSize)
        if (result.toString().length > remaining) {
            var observationOmitted = false
            for (key in listOf("observation", "recovery_observation")) if (result.has(key)) {
                val previous = result.getJSONObject(key)
                val omitted = observationFailure(DeviceError("CACHE_BUDGET_EXCEEDED", "Attached observation omitted to preserve the retained action result"), JSONObject())
                    .put("omitted_for_retention", true)
                if (previous.has("condition")) omitted.put("condition", json("status" to "unknown", "timed_out" to false))
                result.put(key, omitted)
                observationOmitted = true
            }
            if (observationOmitted) {
                result.put("observation_status", "failed")
                request.observationStatus = "failed"
                addRecovery(result)
            }
        }
        if (result.toString().length > remaining) {
            // Window/event diagnostics and backend execution metadata can also exceed the last
            // reserved slot. Keep the terminal facts and original rejection classification.
            result.put("diagnostics_omitted_for_retention", true)
            result.optJSONObject("error")?.let { error ->
                val retained = JSONObject()
                listOf("code", "message", "reason", "age_ms", "snapshot_age_ms", "snapshot_ui_version", "current_ui_version").forEach { key ->
                    if (error.has(key)) retained.put(key, error.get(key))
                }
                result.put("error", retained)
            }
            result.optJSONObject("execution")?.let { execution ->
                val retained = json("details_omitted_for_retention" to true)
                listOf("mechanism", "confirmation", "business_success", "cursor_restored").forEach { key ->
                    if (execution.has(key)) retained.put(key, execution.get(key))
                }
                result.put("execution", retained)
            }
            // Error prose may contain platform-supplied text. Bound it by serialized size, not
            // raw character count (JSON escaping can multiply that count), without changing code/reason.
            val error = result.optJSONObject("error")
            val message = error?.opt("message") as? String
            if (message != null && result.toString().length > remaining) {
                error.put("message_truncated_for_retention", true)
                var low = 0
                var high = message.length
                while (low < high) {
                    val middle = (low + high + 1) / 2
                    error.put("message", message.take(middle))
                    if (result.toString().length <= remaining) low = middle else high = middle - 1
                }
                error.put("message", message.take(low))
            }
        }
        request.retainedChars = result.toString().length
        request.result.complete(result)
    }
    private fun unavailableChannel(result: JSONObject): Boolean =
        result.optJSONObject("screenshot")?.opt("available") == false || result.optJSONObject("tree")?.opt("available") == false
    private fun addRecovery(result: JSONObject) {
        val state = result.optString("state")
        val channelFailed = unavailableChannel(result) || result.optJSONObject("observation")?.let(::unavailableChannel) == true
        val category = when {
            state in setOf("accepted", "executing") -> "wait_original_request"
            !result.has("action_executed") && (channelFailed || result.optString("observation_status") in setOf("failed", "condition_timeout")) -> "refresh_observation"
            state == "unknown" || (state in setOf("failed", "cancelled") && result.opt("action_executed") != false) -> "reconcile_result"
            state == "executed" && (channelFailed || result.optString("observation_status") in setOf("failed", "condition_timeout")) -> "refresh_observation"
            state in setOf("failed", "cancelled") && result.opt("action_executed") == false -> "refresh_observation"
            else -> return
        }
        val message = when (category) {
            "wait_original_request" -> "Query the original request_id until terminal; do not submit a replacement action."
            "reconcile_result" -> "Verify the original request and current scene before deciding; execution is uncertain, so do not replay automatically."
            else -> if (result.opt("action_executed") == true)
                "The input executed. Obtain a fresh observation before deciding the next action; do not repeat the original input."
            else if (!result.has("action_executed")) "The observation is incomplete. Obtain a fresh observation before deciding the next action."
            else "The request terminated without executing. Observe the current scene and select a validated target before deciding a new action."
        }
        result.put("recovery", json("category" to category, "advisory" to true, "replay_action" to false,
            "suggested_tool" to if (category == "wait_original_request") "get_status" else "observe", "message" to message))
    }
    private val recoveryObservationErrors = setOf("STALE_OBSERVATION", "STALE_ELEMENT", "ELEMENT_UNAVAILABLE", "WINDOW_MISMATCH", "ACTION_UNSUPPORTED")
    private fun cancel(client: String, args: JSONObject): JSONObject {
        val id = args.optString("request_id")
        cancelPending(client, id.takeIf { it.isNotEmpty() })
        return json("state" to "cancel_requested", "in_flight_may_complete" to (active?.client == client))
    }
    private fun cancelPending(client: String? = null, id: String? = null) { synchronized(requests) {
        requests.values.filter { !it.result.isDone && (client == null || it.client == client) && (id == null || it.id == id) }.forEach {
            it.cancelled = true
            if (it.phase == "accepted") complete(it, errorResult(DeviceError("CANCELLED", "Request cancelled before execution"), "cancelled")
                .put("request_id", it.id).put("action_executed", false))
        }
    } }
    private fun currentReservation(): Reservation? {
        if (reservation?.let { clock() >= it.expires } == true) reservation = null
        return reservation
    }
    private fun checkReservation(client: String, args: JSONObject) = synchronized(requests) {
        val current = currentReservation()
        val supplied = args.optString("lease_id")
        if (current == null) {
            if (supplied.isNotEmpty()) fail("LEASE_EXPIRED", "Reservation expired or was released", json("reason" to "RESERVATION_INACTIVE"))
        } else if (current.client != client || current.id != supplied) {
            fail("DEVICE_RESERVED", "Device is reserved for another test sequence", json("expires_in_ms" to (current.expires - clock()).coerceAtLeast(0)))
        }
    }
    private fun acquireDevice(client: String, args: JSONObject): JSONObject = synchronized(requests) {
        checkAccess(client)
        gate.check()
        val ttl = args.integer("ttl_ms", 60_000, 1_000, 300_000)
        val current = currentReservation()
        if (current != null) {
            if (current.client != client || args.optString("lease_id") != current.id)
                fail("DEVICE_RESERVED", "Device already has a test reservation", json("expires_in_ms" to (current.expires - clock()).coerceAtLeast(0)))
        } else {
            if (args.has("lease_id")) fail("LEASE_EXPIRED", "Cannot renew an expired or released reservation")
            if (requests.values.any { !it.result.isDone }) fail("BUSY", "Wait for outstanding device actions before reserving")
        }
        val updated = Reservation(client, current?.id ?: UUID.randomUUID().toString(), clock() + ttl)
        reservation = updated
        json("state" to "reserved", "lease_id" to updated.id, "expires_in_ms" to ttl,
            "lease_scope" to "device_service", "local_controls_override" to true)
    }
    private fun releaseDevice(client: String, args: JSONObject): JSONObject = synchronized(requests) {
        val id = args.string("lease_id", 128)
        val current = currentReservation() ?: fail("LEASE_EXPIRED", "Reservation expired or was released")
        if (current.client != client || current.id != id) fail("DEVICE_RESERVED", "Only the reservation owner can release it")
        reservation = null
        // Queued reserved actions fail their lease check; an injected action may still complete.
        json("state" to "released", "in_flight_may_complete" to (active?.client == client))
    }
    fun revokeClient(client: String) { synchronized(requests) { if (reservation?.client == client) reservation = null }; cancelPending(client); changed() }
    fun stop() { gate.stop(); synchronized(requests) { reservation = null }; cancelPending(); executor.shutdown(); changed() }
    fun status(client: String, id: String = ""): JSONObject {
        // Keep the legacy field for clients that read it; control is always available while running.
        val result = json("paused" to false, "stopped" to gate.stopped,
            "executing_request_id" to active?.takeIf { it.client == client }?.id)
        synchronized(requests) {
            val lease = currentReservation()
            result.put("reservation", lease?.let { json("reserved" to true, "owned_by_client" to (it.client == client),
                "expires_in_ms" to (it.expires - clock()).coerceAtLeast(0)) } ?: json("reserved" to false))
        }
        if (id.isNotEmpty()) synchronized(requests) {
            val request = requests["$client:$id"] ?: fail("REQUEST_NOT_FOUND", "No retained request with this ID")
            result.put("request", request.result.getNow(pending(request)))
        }
        return result
    }
}
