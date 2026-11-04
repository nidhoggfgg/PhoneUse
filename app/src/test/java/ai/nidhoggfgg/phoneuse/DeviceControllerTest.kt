package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DeviceControllerTest {
    private class Backend : DeviceBackend {
        val calls = AtomicInteger()
        var entered: CountDownLatch? = null
        var release: CountDownLatch? = null
        var failure: DeviceError? = null
        var executionResult: JSONObject? = null
        var observationFailure: DeviceError? = null
        var observationResult: JSONObject? = null
        var observing: CountDownLatch? = null
        var finishObservation: CountDownLatch? = null
        val observedClients = mutableListOf<String>()
        val observedOptions = mutableListOf<JSONObject>()
        val actingClients = mutableListOf<String>()
        override fun capabilities() = json("available" to true)
        override fun observe(args: JSONObject) = json("observation_id" to "test")
        override fun observe(args: JSONObject, client: String, check: () -> Unit): JSONObject {
            check(); observedClients.add(client)
            observedOptions.add(JSONObject(args.toString()))
            observing?.countDown(); finishObservation?.await(3, TimeUnit.SECONDS)
            check()
            observationFailure?.let { throw it }
            return observationResult?.let { JSONObject(it.toString()) } ?: observe(args)
        }
        override fun listApps() = JSONObject()
        override fun waitForChange(args: JSONObject, check: () -> Unit): JSONObject { check(); return JSONObject() }
        override fun act(name: String, args: JSONObject, check: () -> Unit): JSONObject {
            check(); calls.incrementAndGet(); entered?.countDown(); release?.await(3, TimeUnit.SECONDS)
            failure?.let { throw it }
            return executionResult ?: json("confirmation" to "android_accepted")
        }
        override fun act(name: String, args: JSONObject, client: String, check: () -> Unit): JSONObject {
            actingClients.add(client)
            return act(name, args, check)
        }
    }
    private fun action(id: String) = json("request_id" to id)

    @Test fun queuedActionsExpireWithoutRunningWhileLongOperationsStillReturnTheirResult() {
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val controller = DeviceController(backend, { 100L }, { true })
        val thread = Executors.newSingleThreadExecutor()
        try {
            val first = thread.submit<JSONObject> { controller.call("a", "home", action("slow")) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            val rejected = controller.call("a", "back", action("expires"))
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
            assertEquals("BUSY", rejected.getJSONObject("error").getString("code"))
            assertFalse(rejected.getBoolean("action_executed"))
            assertTrue("Queue took $elapsedMs ms", elapsedMs < 2000)
            assertFalse("Running action must still wait for completion", first.isDone)
            backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            // The expired action is retained, and replaying its ID cannot execute it later.
            assertEquals(rejected.toString(), controller.call("a", "back", action("expires")).toString())
            assertEquals("executed", controller.call("a", "recents", action("next")).getString("state"))
            assertEquals(2, backend.calls.get())
        } finally { backend.release!!.countDown(); controller.stop(); thread.shutdownNow() }
    }

    @Test fun queuedObservationsExpireWithoutCollectingAndDoNotInterruptRunningAction() {
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val controller = DeviceController(backend, { 100L }, { true })
        val thread = Executors.newSingleThreadExecutor()
        try {
            val first = thread.submit<JSONObject> { controller.call("a", "home", action("slow")) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val start = System.nanoTime()
            val rejected = controller.call("a", "observe", json("timeout_ms" to 60_000))
            assertEquals("BUSY", rejected.getJSONObject("error").getString("code"))
            assertEquals("failed", rejected.getString("observation_status"))
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 2000)
            assertFalse(first.isDone)
            backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            assertEquals("executed", controller.call("a", "back", action("barrier")).getString("state"))
            assertTrue(backend.observedClients.isEmpty())
        } finally { backend.release!!.countDown(); controller.stop(); thread.shutdownNow() }
    }

    @Test fun unconfirmedLaunchIsRetainedAsUnknownAndNeverReplayed() {
        val backend = Backend().apply {
            failure = DeviceError("RESULT_UNKNOWN", "Launch not confirmed", json(
                "reason" to "launch_not_confirmed", "package_name" to "target", "action_executed" to JSONObject.NULL))
        }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = json("request_id" to "launch-once", "package_name" to "target", "observe_after" to true)
            val result = controller.call("a", "launch_app", args)
            assertEquals("unknown", result.getString("state"))
            assertTrue(result.isNull("action_executed"))
            assertEquals("launch_not_confirmed", result.getJSONObject("error").getString("reason"))
            assertEquals("reconcile_result", result.getJSONObject("recovery").getString("category"))
            assertEquals(result.toString(), controller.call("a", "launch_app", args).toString())
            assertEquals(1, backend.calls.get())
            assertTrue(backend.observedOptions.isEmpty())
        } finally { controller.stop() }
    }

    @Test fun authorizedClientsCanActWithoutAcquiringControl() {
        var now = 100L
        val controller = DeviceController(Backend(), { now }, { true })
        try {
            assertEquals("executed", controller.call("a", "home", action("one")).getString("state"))
            now += TimeUnit.DAYS.toMillis(30)
            assertEquals("executed", controller.call("a", "home", action("later")).getString("state"))
            assertEquals("executed", controller.call("b", "back", action("one")).getString("state"))
            assertFalse(controller.call("a", "get_status", JSONObject()).has("control_owner"))
        } finally { controller.stop() }
    }
    @Test fun serviceDestructionInvalidatesPendingWork() {
        val gate = ControlGate()
        val generation = gate.generation
        gate.check(generation)
        gate.stop()
        assertTrue(gate.stopped)
        assertNotEquals(generation, gate.generation)
        assertThrows(DeviceError::class.java) { gate.check(generation) }
        assertThrows(DeviceError::class.java) { gate.check() }
    }
    @Test fun reservationsAreOptionalAndLegacyControlToolsStayRemoved() {
        val catalog = ToolCatalog.all()
        for (i in 0 until catalog.length()) {
            val tool = catalog.getJSONObject(i)
            assertFalse(tool.getString("name") in setOf("acquire_control", "release_control"))
            val required = tool.getJSONObject("inputSchema").getJSONArray("required")
            if (tool.getString("name") != "release_device")
                assertFalse((0 until required.length()).any { required.getString(it) == "lease_id" })
        }
    }
    @Test fun duplicateActionsAreNotReplayedAndConflictingPayloadsFail() {
        val backend = Backend()
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action("one")
            assertEquals("executed", controller.call("a", "home", args).getString("state"))
            assertEquals("executed", controller.call("a", "home", JSONObject(args.toString())).getString("state"))
            assertEquals("REQUEST_ID_CONFLICT", controller.call("a", "back", args).getJSONObject("error").getString("code"))
            assertEquals(1, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun unknownResultsAreRetainedWithoutRetry() {
        val backend = Backend().apply { failure = DeviceError("RESULT_UNKNOWN", "Callback lost") }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action("one")
            repeat(2) { assertEquals("unknown", controller.call("a", "home", args).getString("state")) }
            assertEquals(1, backend.calls.get())
            assertEquals("unknown", controller.call("a", "get_status", json("request_id" to "one")).getJSONObject("request").getString("state"))
        } finally { controller.stop() }
    }
    @Test fun serviceDestructionCancelsQueuedActionButReportsCompletedInFlightAction() {
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val controller = DeviceController(backend, { 100L }, { true })
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit<JSONObject> { controller.call("a", "home", action("one")) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val second = threads.submit<JSONObject> { controller.call("a", "back", action("two")) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (controller.call("a", "get_status", json("request_id" to "two")).has("error") && System.nanoTime() < deadline) Thread.yield()
            controller.stop()
            backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            assertEquals("cancelled", second.get(2, TimeUnit.SECONDS).getString("state"))
            assertEquals("STOPPED", controller.call("a", "back", action("late")).getJSONObject("error").getString("code"))
            assertEquals(1, backend.calls.get())
        } finally { backend.release!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
    @Test fun revokedCredentialCannotReadOrAct() {
        var allowed = true
        val controller = DeviceController(Backend(), { 100L }, { allowed })
        try {
            allowed = false
            assertEquals("UNAUTHORIZED", controller.call("a", "observe", JSONObject()).getJSONObject("error").getString("code"))
            assertEquals("UNAUTHORIZED", controller.call("a", "home", action("one")).getJSONObject("error").getString("code"))
        } finally { controller.stop() }
    }
    @Test fun malformedActionsAreRejectedBeforeDispatch() {
        val backend = Backend()
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action("bad").put("x", "12").put("y", 2)
            assertEquals("INVALID_ARGUMENT", controller.call("a", "tap", args).getJSONObject("error").getString("code"))
            assertEquals(0, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun stopRejectsLateRequestsAndCannotBeResumed() {
        val controller = DeviceController(Backend(), { 100L }, { true })
        controller.stop()
        assertEquals("STOPPED", controller.call("a", "home", action("late")).getJSONObject("error").getString("code"))
    }
    private fun awaitQueued(controller: DeviceController, client: String, id: String) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (System.nanoTime() < deadline) {
            if (controller.call(client, "get_status", json("request_id" to id)).has("request")) return
            Thread.yield()
        }
        fail("Request was not queued: $client/$id")
    }
    private fun verifyClientCancellation(revoke: Boolean) {
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val allowed = java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply { addAll(listOf("a", "b")) }
        val controller = DeviceController(backend, { 100L }, { it in allowed })
        val threads = Executors.newFixedThreadPool(3)
        try {
            val first = threads.submit<JSONObject> { controller.call("b", "home", action("first")) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val own = threads.submit<JSONObject> { controller.call("a", "home", action("same-id")) }
            val other = threads.submit<JSONObject> { controller.call("b", "back", action("same-id")) }
            awaitQueued(controller, "a", "same-id"); awaitQueued(controller, "b", "same-id")
            assertEquals(1, backend.calls.get()) // Other clients share the same serial device queue.
            if (revoke) { allowed.remove("a"); controller.revokeClient("a") }
            else controller.call("a", "cancel", JSONObject())
            assertEquals("cancelled", own.get(2, TimeUnit.SECONDS).getString("state"))
            assertFalse(other.isDone)
            backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            assertEquals("executed", other.get(2, TimeUnit.SECONDS).getString("state"))
            assertEquals(2, backend.calls.get())
        } finally { backend.release!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
    @Test fun cancelOnlyAffectsRequestsFromTheCallingClient() { verifyClientCancellation(false) }
    @Test fun credentialRevocationOnlyCancelsThatClientsPendingActions() { verifyClientCancellation(true) }
    @Test fun canonicalPayloadIgnoresObjectKeyOrder() {
        assertEquals(canonical(json("a" to 1, "b" to json("x" to "中文"))), canonical(json("b" to json("x" to "中文"), "a" to 1)))
    }
    @Test fun defaultActionsCanRunConsecutivelyWithoutAnyObservation() {
        val backend = Backend()
        val controller = DeviceController(backend, { 0 }, { true })
        try {
            for ((index, tool) in listOf("home", "back", "recents").withIndex()) {
                val result = controller.call("client", tool, action("action-$index"))
                assertEquals("executed", result.getString("state"))
                assertTrue(result.getBoolean("action_executed"))
                assertFalse(result.has("observation"))
                assertFalse(result.has("observation_status"))
            }
            assertTrue(backend.observedOptions.isEmpty())
        } finally { controller.stop() }
    }

    @Test fun observationFailurePreservesExecutedActionAndItsClientWithoutReplay() {
        val backend = Backend().apply {
            observationFailure = DeviceError("OBSERVATION_INCONSISTENT", "Changing", json("reason" to "content_changed", "attempts" to 3))
        }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action("once").put("observe_after", true)
            repeat(2) {
                val result = controller.call("client-a", "home", args)
                assertEquals("executed", result.getString("state"))
                assertTrue(result.getBoolean("action_executed"))
                assertEquals("OBSERVATION_INCONSISTENT", result.getJSONObject("observation").getJSONObject("error").getString("code"))
            }
            assertEquals(1, backend.calls.get())
            assertEquals(listOf("client-a"), backend.observedClients)
            controller.call("client-b", "observe", JSONObject())
            assertEquals(listOf("client-a", "client-b"), backend.observedClients)
            assertEquals(listOf("client-a"), backend.actingClients)
        } finally { controller.stop() }
    }
    @Test fun staleDiagnosticsAndNonExecutionArePreserved() {
        val backend = Backend().apply { failure = DeviceError("STALE_OBSERVATION", "Expired", json(
            "reason" to "expired", "age_ms" to 30_001, "snapshot_ui_version" to 12, "current_ui_version" to 13, "action_executed" to false)) }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val result = controller.call("a", "home", action("stale"))
            assertFalse(result.getBoolean("action_executed"))
            assertEquals("expired", result.getJSONObject("error").getString("reason"))
            assertEquals(30_001, result.getJSONObject("error").getInt("age_ms"))
            assertFalse(result.getJSONObject("error").has("action_executed"))
            val malformed = controller.call("a", "tap", action("coord").put("x", 1).put("y", 2))
            assertFalse(malformed.getBoolean("action_executed"))
            assertEquals("INVALID_ARGUMENT", malformed.getJSONObject("error").getString("code"))
            assertEquals(1, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun reservationsAreBoundToClientAndSequenceAndExpire() {
        var now = 100L
        val backend = Backend()
        val controller = DeviceController(backend, { now }, { true })
        try {
            val lease = controller.call("a", "acquire_device", json("ttl_ms" to 1000)).getString("lease_id")
            fun leased(id: String) = action(id).put("lease_id", lease)
            assertEquals("DEVICE_RESERVED", controller.call("b", "home", leased("other")).getJSONObject("error").getString("code"))
            assertEquals("DEVICE_RESERVED", controller.call("a", "home", action("missing")).getJSONObject("error").getString("code"))
            assertEquals("executed", controller.call("a", "home", leased("valid")).getString("state"))
            assertFalse(controller.call("b", "get_status", JSONObject()).toString().contains(lease))
            assertEquals("DEVICE_RESERVED", controller.call("b", "release_device", json("lease_id" to lease)).getJSONObject("error").getString("code"))
            now += 500
            assertEquals(lease, controller.call("a", "acquire_device", json("ttl_ms" to 1000, "lease_id" to lease)).getString("lease_id"))
            now += 1000
            assertEquals("LEASE_EXPIRED", controller.call("a", "home", leased("expired")).getJSONObject("error").getString("code"))
            assertEquals("executed", controller.call("b", "home", action("free")).getString("state"))
            // Retained results remain queryable after a reservation expires.
            assertEquals("executed", controller.call("a", "home", leased("valid")).getString("state"))
            assertEquals(2, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun localRevocationOverridesReservations() {
        val controller = DeviceController(Backend(), { 100L }, { true })
        try {
            controller.call("a", "acquire_device", JSONObject())
            controller.revokeClient("a")
            assertEquals("executed", controller.call("b", "home", action("after-revoke")).getString("state"))
        } finally { controller.stop() }
    }
    @Test fun queuedActionsCannotRunAfterReservationExpires() {
        val now = java.util.concurrent.atomic.AtomicLong(100)
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val controller = DeviceController(backend, now::get, { true })
        val threads = Executors.newFixedThreadPool(2)
        try {
            val lease = controller.call("a", "acquire_device", json("ttl_ms" to 1000)).getString("lease_id")
            val first = threads.submit<JSONObject> { controller.call("a", "home", action("first").put("lease_id", lease)) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val second = threads.submit<JSONObject> { controller.call("a", "home", action("second").put("lease_id", lease)) }
            awaitQueued(controller, "a", "second")
            now.set(1100); backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            val rejected = second.get(2, TimeUnit.SECONDS)
            assertEquals("LEASE_EXPIRED", rejected.getJSONObject("error").getString("code"))
            assertFalse(rejected.getBoolean("action_executed"))
            assertEquals(1, backend.calls.get())
        } finally { backend.release!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
    @Test fun configuredObservationProgressExposesExecutionAndKeepsOriginalRequestPending() {
        val backend = Backend().apply { observing = CountDownLatch(1); finishObservation = CountDownLatch(1) }
        val controller = DeviceController(backend, { 100L }, { true })
        val threads = Executors.newSingleThreadExecutor()
        val options = json("mode" to "tree", "timeout_ms" to 20000, "output" to "decision",
            "wait_until" to json("package_name" to "sample.app", "element" to json("text" to "Ready", "match" to "unique")))
        try {
            val args = action("waiting").put("observe_after", true).put("observation_options", options)
            val call = threads.submit<JSONObject> { controller.call("a", "home", args) }
            assertTrue(backend.observing!!.await(2, TimeUnit.SECONDS))
            val pending = controller.call("a", "get_status", json("request_id" to "waiting")).getJSONObject("request")
            assertEquals("executing", pending.getString("state"))
            assertTrue(pending.getBoolean("action_executed"))
            assertEquals("observing", pending.getString("observation_status"))
            assertEquals("after_action", pending.getString("observation_purpose"))
            assertEquals("wait_original_request", pending.getJSONObject("recovery").getString("category"))
            assertFalse(call.isDone)
            backend.finishObservation!!.countDown()
            assertEquals("complete", call.get(2, TimeUnit.SECONDS).getString("observation_status"))
            controller.call("a", "home", JSONObject(args.toString()))
            assertEquals(1, backend.calls.get())
            assertEquals(listOf(canonical(options)), backend.observedOptions.map(::canonical))
        } finally { backend.finishObservation!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
    @Test fun conditionTimeoutRetainsExecutionAndDoesNotRestartObservationOnRetransmission() {
        val backend = Backend().apply { observationResult = json("observation_id" to "last", "captured_at_ms" to 88,
            "observation_status" to "condition_timeout", "condition" to json("status" to "ambiguous", "timed_out" to true)) }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action("once").put("observe_after", true)
            repeat(2) {
                val result = controller.call("a", "home", args)
                assertEquals("executed", result.getString("state"))
                assertTrue(result.getBoolean("action_executed"))
                assertEquals("condition_timeout", result.getString("observation_status"))
                assertEquals("refresh_observation", result.getJSONObject("recovery").getString("category"))
                assertEquals(88, result.getJSONObject("observation").getInt("captured_at_ms"))
            }
            assertEquals(1, backend.calls.get())
            assertEquals(1, backend.observedOptions.size)
        } finally { controller.stop() }
    }
    @Test fun terminalTargetRejectionAttachesFreshClientObservationWithoutReplacingTheError() {
        val backend = Backend().apply { failure = DeviceError("STALE_ELEMENT", "Old target", json("reason" to "target_changed", "action_executed" to false)) }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val options = json("mode" to "tree", "output" to "decision")
            val args = action("rejected").put("observe_on_rejection", true).put("observation_options", options)
            repeat(2) {
                val result = controller.call("owner", "home", args)
                assertEquals("failed", result.getString("state"))
                assertFalse(result.getBoolean("action_executed"))
                assertEquals("target_changed", result.getJSONObject("error").getString("reason"))
                assertEquals("test", result.getJSONObject("recovery_observation").getString("observation_id"))
                assertEquals("refresh_observation", result.getJSONObject("recovery").getString("category"))
            }
            assertEquals(1, backend.calls.get())
            assertEquals(listOf("owner"), backend.observedClients)
            assertEquals(canonical(options), canonical(backend.observedOptions.single()))
        } finally { controller.stop() }
    }
    @Test fun failedRecoveryObservationAndUnknownActionsPreserveOriginalExecutionFacts() {
        val backend = Backend().apply {
            failure = DeviceError("STALE_OBSERVATION", "Expired", json("action_executed" to false))
            observationFailure = DeviceError("OBSERVATION_INCONSISTENT", "Still changing")
        }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val rejected = controller.call("a", "home", action("rejected").put("observe_on_rejection", true))
            assertEquals("STALE_OBSERVATION", rejected.getJSONObject("error").getString("code"))
            assertFalse(rejected.getBoolean("action_executed"))
            assertEquals("OBSERVATION_INCONSISTENT", rejected.getJSONObject("recovery_observation").getJSONObject("error").getString("code"))
            backend.failure = DeviceError("RESULT_UNKNOWN", "Lost callback")
            val unknown = controller.call("a", "home", action("unknown").put("observe_on_rejection", true))
            assertEquals("unknown", unknown.getString("state"))
            assertTrue(unknown.isNull("action_executed"))
            assertFalse(unknown.has("recovery_observation"))
            assertEquals("reconcile_result", unknown.getJSONObject("recovery").getString("category"))
            assertEquals(1, backend.observedClients.size)
        } finally { controller.stop() }
    }
    @Test fun cancellationWhileObservingDoesNotEraseTheExecutedInput() {
        val backend = Backend().apply { observing = CountDownLatch(1); finishObservation = CountDownLatch(1) }
        val controller = DeviceController(backend, { 100L }, { true })
        val threads = Executors.newSingleThreadExecutor()
        try {
            val call = threads.submit<JSONObject> { controller.call("a", "home", action("cancel-observe").put("observe_after", true)
                .put("observation_options", json("wait_until" to json("package_name" to "sample.app")))) }
            assertTrue(backend.observing!!.await(2, TimeUnit.SECONDS))
            controller.call("a", "cancel", json("request_id" to "cancel-observe"))
            backend.finishObservation!!.countDown()
            val result = call.get(2, TimeUnit.SECONDS)
            assertTrue(result.getBoolean("action_executed"))
            assertEquals("executed", result.getString("state"))
            assertEquals("failed", result.getString("observation_status"))
            assertEquals("unknown", result.getJSONObject("observation").getJSONObject("condition").getString("status"))
            assertEquals(1, backend.calls.get())
        } finally { backend.finishObservation!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
    @Test fun queuedFalseExecutionDoesNotSuggestAReplacementAction() {
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val controller = DeviceController(backend, { 100L }, { true })
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit<JSONObject> { controller.call("a", "home", action("first")) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val second = threads.submit<JSONObject> { controller.call("a", "home", action("queued")) }
            awaitQueued(controller, "a", "queued")
            val pending = controller.call("a", "get_status", json("request_id" to "queued")).getJSONObject("request")
            assertEquals("accepted", pending.getString("state"))
            assertFalse(pending.getBoolean("action_executed"))
            assertEquals("wait_original_request", pending.getJSONObject("recovery").getString("category"))
            backend.release!!.countDown()
            first.get(2, TimeUnit.SECONDS); second.get(2, TimeUnit.SECONDS)
        } finally { backend.release!!.countDown(); controller.stop(); threads.shutdownNow() }
    }
    @Test fun observationCacheBudgetPreservesOriginalActionAndDeduplicatesTheOmittedSnapshot() {
        val backend = Backend().apply { observationResult = json("observation_id" to "large",
            "screenshot" to json("available" to true, "data_base64" to "x".repeat(4_100_000))) }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val first = controller.call("a", "home", action("large-one").put("observe_after", true))
            assertEquals("complete", first.getString("observation_status"))
            val args = action("large-two").put("observe_after", true)
            val second = controller.call("a", "home", args)
            assertEquals("executed", second.getString("state"))
            assertTrue(second.getBoolean("action_executed"))
            assertEquals("failed", second.getString("observation_status"))
            assertEquals("CACHE_BUDGET_EXCEEDED", second.getJSONObject("observation").getJSONObject("error").getString("code"))
            assertTrue(first.toString().length + second.toString().length < 8_000_000)
            assertEquals(second.toString(), controller.call("a", "home", args).toString())
            assertEquals(2, backend.calls.get())
            assertEquals(2, backend.observedClients.size)
        } finally { controller.stop() }
    }
    @Test fun backendObservationFailureKeepsLastSnapshotAndOriginalConditionVerdict() {
        val backend = Backend().apply { observationResult = json("state" to "failed", "observation_status" to "failed",
            "error" to json("code" to "OBSERVATION_TIMEOUT"), "condition" to json("status" to "unknown", "timed_out" to true),
            "last_observation" to json("observation_id" to "last-valid", "captured_at_ms" to 77,
                "condition" to json("status" to "unsatisfied", "timed_out" to false))) }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val result = controller.call("a", "home", action("one").put("observe_after", true))
            assertTrue(result.getBoolean("action_executed"))
            val observation = result.getJSONObject("observation")
            assertEquals("unknown", observation.getJSONObject("condition").getString("status"))
            assertEquals(77, observation.getJSONObject("last_observation").getInt("captured_at_ms"))
            assertEquals("unsatisfied", observation.getJSONObject("last_observation").getJSONObject("condition").getString("status"))
        } finally { controller.stop() }
    }
    @Test fun unavailableScreenshotKeepsConditionAndCollectionStatusButSuggestsFreshObservation() {
        val backend = Backend().apply { observationResult = json("observation_status" to "complete",
            "condition" to json("status" to "satisfied", "timed_out" to false),
            "tree" to json("available" to true), "screenshot" to json("available" to false)) }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val result = controller.call("a", "home", action("one").put("observe_after", true))
            assertEquals("complete", result.getString("observation_status"))
            assertEquals("satisfied", result.getJSONObject("observation").getJSONObject("condition").getString("status"))
            assertEquals("refresh_observation", result.getJSONObject("recovery").getString("category"))
            val read = controller.call("a", "observe", json())
            assertEquals("complete", read.getString("observation_status"))
            assertEquals("refresh_observation", read.getJSONObject("recovery").getString("category"))
        } finally { controller.stop() }
    }
    @Test fun largeRejectionWithoutObservationFitsReservedSlotAndRetainsItsOriginalReason() {
        val backend = Backend()
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val observation = json("observation_id" to "large", "screenshot" to json("available" to true, "data_base64" to ""),
                "observation_status" to "complete")
            val emptyFirst = json("state" to "executed", "request_id" to "fill", "action_executed" to true,
                "execution" to json("confirmation" to "android_accepted"), "observation" to observation, "observation_status" to "complete")
            val firstSize = 8_000_000 - 4096
            observation.getJSONObject("screenshot").put("data_base64", "x".repeat(firstSize - emptyFirst.toString().length))
            backend.observationResult = observation
            val first = controller.call("a", "home", action("fill").put("observe_after", true))
            assertEquals(firstSize, first.toString().length)
            backend.failure = DeviceError("STALE_ELEMENT", "Platform detail\n".repeat(2000), json(
                "reason" to "target_occluded", "snapshot_age_ms" to 1234, "snapshot_ui_version" to 3,
                "current_ui_version" to 7, "occlusion" to json("windows" to "w".repeat(16000)), "action_executed" to false))
            val args = action("rejected")
            val rejected = controller.call("a", "home", args)
            assertEquals("failed", rejected.getString("state"))
            assertFalse(rejected.getBoolean("action_executed"))
            assertEquals("STALE_ELEMENT", rejected.getJSONObject("error").getString("code"))
            assertEquals("target_occluded", rejected.getJSONObject("error").getString("reason"))
            assertEquals(1234, rejected.getJSONObject("error").getInt("snapshot_age_ms"))
            assertEquals(3, rejected.getJSONObject("error").getInt("snapshot_ui_version"))
            assertEquals(7, rejected.getJSONObject("error").getInt("current_ui_version"))
            assertTrue(rejected.getBoolean("diagnostics_omitted_for_retention"))
            assertTrue(rejected.getJSONObject("error").getBoolean("message_truncated_for_retention"))
            assertFalse(rejected.has("observation_status"))
            assertTrue(rejected.toString().length <= 4096)
            assertTrue(first.toString().length + rejected.toString().length <= 8_000_000)
            assertEquals(rejected.toString(), controller.call("a", "home", args).toString())
            assertEquals(2, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun largeExecutionMetadataKeepsConfirmationWithoutInventingAnObservationFailure() {
        val backend = Backend().apply { executionResult = json("mechanism" to "node_action", "confirmation" to "android_accepted",
            "business_success" to null, "metadata" to "x".repeat(8_100_000)) }
        val controller = DeviceController(backend, { 100L }, { true })
        try {
            val args = action("large")
            val result = controller.call("a", "home", args)
            assertEquals("executed", result.getString("state"))
            assertTrue(result.getBoolean("action_executed"))
            assertEquals("node_action", result.getJSONObject("execution").getString("mechanism"))
            assertEquals("android_accepted", result.getJSONObject("execution").getString("confirmation"))
            assertTrue(result.getJSONObject("execution").getBoolean("details_omitted_for_retention"))
            assertFalse(result.has("observation_status"))
            assertTrue(result.toString().length < 4096)
            assertEquals(result.toString(), controller.call("a", "home", args).toString())
            assertEquals(1, backend.calls.get())
        } finally { controller.stop() }
    }
    @Test fun disabledTransportCancelsQueuedInputWithoutRevokingTheClient() {
        val backend = Backend().apply { entered = CountDownLatch(1); release = CountDownLatch(1) }
        val controller = DeviceController(backend, { 100L }, { true })
        val revoked = java.util.concurrent.atomic.AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<JSONObject> { controller.call("client", "home", action("tls-first")) }
            assertTrue(backend.entered!!.await(2, TimeUnit.SECONDS))
            val remote = pool.submit<JSONObject> {
                controller.call("client", "home", action("http-queued")) {
                    if (revoked.get()) fail("TRANSPORT_DISABLED", "HTTP disabled")
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (controller.call("client", "get_status", action("http-queued")).optJSONObject("request") == null && System.nanoTime() < deadline)
                Thread.sleep(5)
            assertNotNull(controller.call("client", "get_status", action("http-queued")).optJSONObject("request"))
            revoked.set(true)
            backend.release!!.countDown()
            assertEquals("executed", first.get(2, TimeUnit.SECONDS).getString("state"))
            val rejected = remote.get(2, TimeUnit.SECONDS)
            assertEquals("TRANSPORT_DISABLED", rejected.getJSONObject("error").getString("code"))
            assertFalse(rejected.getBoolean("action_executed"))
            assertEquals(1, backend.calls.get())
            assertEquals("executed", controller.call("client", "home", action("tls-after")).getString("state"))
        } finally { backend.release!!.countDown(); controller.stop(); pool.shutdownNow() }
    }

}
