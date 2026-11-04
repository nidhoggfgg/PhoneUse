package ai.nidhoggfgg.phoneuse.device

import ai.nidhoggfgg.phoneuse.core.*
import ai.nidhoggfgg.phoneuse.voice.VoiceProvider
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import androidx.core.graphics.scale
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Base64
import android.view.Display
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Suppress("DEPRECATION")
class AndroidBackend(private val context: Context, private val serviceInstanceId: String = UUID.randomUUID().toString()) : DeviceBackend {
    private data class Tree(val elements: JSONArray, val nodes: Map<String, ElementReference>, val truncated: Boolean)
    private data class Snapshot(val id: String, val version: Long,
        val accessibilityInstance: String, val window: Int, val app: String,
        val width: Int, val height: Int, val rotation: Int, val created: Long,
        val nodes: Map<String, ElementReference>, val exported: Set<String>)
    private val snapshots = SnapshotStore<Snapshot>({ SystemClock.elapsedRealtime() })
    private val launchGuidance by lazy { AppLaunchGuidance(context) }
    private fun service() = PhoneAccessibilityService.current ?: fail("ACCESSIBILITY_UNAVAILABLE", "Enable Phone Use accessibility service")
    private fun ready(): PhoneAccessibilityService {
        val service = service()
        if (!context.getSystemService(PowerManager::class.java).isInteractive) fail("SCREEN_OFF", "Turn on the phone screen")
        if (context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) fail("DEVICE_LOCKED", "Unlock the phone")
        return service
    }
    private fun geometry(): Triple<Int, Int, Int> {
        val manager = context.getSystemService(WindowManager::class.java)
        val rect = manager.maximumWindowMetrics.bounds
        return Triple(rect.width(), rect.height(), manager.defaultDisplay.rotation)
    }
    override fun capabilities(): JSONObject = json(
        "android_api" to android.os.Build.VERSION.SDK_INT,
        "accessibility" to (PhoneAccessibilityService.current != null),
        "screen_on" to context.getSystemService(PowerManager::class.java).isInteractive,
        "locked" to context.getSystemService(KeyguardManager::class.java).isKeyguardLocked,
        "unavailable_reason" to if (PhoneAccessibilityService.current == null) "ACCESSIBILITY_UNAVAILABLE" else null,
        "screenshot" to "accessibility_on_demand; protected windows may fail",
        "text_modes" to JSONArray(listOf("replace", "insert")),
        "send_voice" to VoiceProvider.attachment.let { attachment ->
            val attached = attachment?.helper?.asBinder()?.isBinderAlive == true
            json("helper_attached" to attached, "routing_api_available" to (attached && attachment?.routingError == null),
                "unavailable_reason" to if (attached) attachment?.routingError else "Start the adb voice helper",
                "max_seconds" to 30, "format" to "mono PCM16, 8000..48000 Hz", "requires" to "ADB helper and UID-scoped recorder injection")
        },
        "insert_mechanism" to "read selection + ACTION_SET_TEXT; requires readable non-password text",
        "coordinate_space" to "default display physical pixels, origin top-left, system bars and IME included",
        "max_nodes" to 1500, "snapshot_ttl_ms" to 30_000, "compact_tree_default" to true,
        "snapshot_limit_per_client" to 8, "snapshot_budget_bytes" to 4 * 1024 * 1024,
        "observation_max_attempts" to 3, "observation_quiet_ms" to 120, "observation_stable_wait_default_ms" to 0,
        "observation_timeout_max_ms" to 60_000, "condition_matching" to "exact fields; AND; unique by default, explicit exists supported",
        "tree_output_modes" to JSONArray(listOf("decision", "legacy")), "tree_output_default" to "decision",
        "observe_after_default" to false,
        "launch_confirmation_timeout_ms" to AppLaunchConfirmation.TIMEOUT_MS,
        "observation_scope" to "output projection only; subtree references revalidated",
        "coordinate_observation_required" to true, "service_instance_id" to serviceInstanceId,
        "deduplication_ttl_ms" to 600_000, "deduplication_scope" to "client + request_id, current server process",
        "mcp_protocol_versions" to JSONArray(listOf("2025-03-26", "2025-06-18", "2025-11-25"))
    ).apply {
        val accessible = getBoolean("accessibility")
        val voice = getJSONObject("send_voice")
        put("mode", if (voice.getBoolean("helper_attached")) "app+adb" else "app")
        put("features", json("selection_replace" to accessible, "screenshot" to accessible,
            "send_voice" to (accessible && voice.getBoolean("routing_api_available"))))
        if (!accessible) put("text_modes", JSONArray())
    }
    override fun observe(args: JSONObject): JSONObject = observe(args, "legacy") {}

    override fun observe(args: JSONObject, client: String, check: () -> Unit): JSONObject {
        val mode = args.optString("mode", "both")
        if (mode !in setOf("tree", "screenshot", "both")) fail("INVALID_ARGUMENT", "mode must be tree, screenshot or both")
        val compact = args.optBoolean("compact", true)
        val decision = args.optString("output", "decision") == "decision"
        val maxDimension = args.integer("max_dimension", 1280, 320, 2048)
        val service = ready()
        val started = System.currentTimeMillis()
        val captures = JSONArray()
        val candidates = LinkedHashMap<String, Snapshot>()
        val checked = { check(); if (ready().instanceId != service.instanceId) fail("ACCESSIBILITY_UNAVAILABLE", "Accessibility service changed during observation") }
        var result = ConditionalObserver({ SystemClock.elapsedRealtime() }, { Thread.sleep(it) }).run(args, checked, {
            val root = service.rootInActiveWindow
            try { service.relevantVersion(root?.windowId ?: -1) } finally { root?.recycle() }
        }) { budget, captureCheck ->
            val captureStart = System.currentTimeMillis()
            val captureDiagnostic = json("started_at_ms" to captureStart)
            if (args.optBoolean("diagnostics") && captures.length() < 64) captures.put(captureDiagnostic)
            try {
                val before = PhoneAccessibilityService.version.get()
                val (width, height, rotation) = geometry()
                val scope = args.optJSONObject("subtree")
                val source = scope?.let { validateContext(service, it, client) }
                val root = service.rootInActiveWindow
                val window = root?.windowId ?: -1
                val app = root?.packageName?.toString() ?: ""
                captureDiagnostic.put("before_window_id", window).put("before_package_name", app).put("before_ui_version", before)
                var readableTree = root != null && app != context.packageName
                var treeReason: String? = if (app == context.packageName) "LOCAL_CONTROL_UI" else if (root == null) "NO_ACTIVE_WINDOW" else null
                val treeStarted = System.currentTimeMillis()
                var tree = Tree(JSONArray(), emptyMap(), false)
                try {
                    // A screenshot-only condition may explicitly require nodes for matching.
                    if (root != null && readableTree && (mode != "screenshot" || args.optJSONObject("wait_until")?.has("element") == true || scope != null)) {
                        val rootWindow = root.window
                        try {
                            if (rootWindow != null && rootWindow.displayId != Display.DEFAULT_DISPLAY)
                                fail("DISPLAY_UNSUPPORTED", "Only nodes on the default display are supported")
                        } finally { rootWindow?.recycle() }
                        val treeDeadline = SystemClock.elapsedRealtime() + minOf(5000L, if (mode == "both") budget.remaining() / 2 else budget.remaining())
                        tree = collect(root, width, height) {
                            captureCheck()
                            if (SystemClock.elapsedRealtime() >= treeDeadline) fail("TREE_TIMEOUT", "Tree collection budget exhausted")
                        }
                    }
                } catch (error: Exception) {
                    if ((error as? DeviceError)?.code in setOf("CANCELLED", "STOPPED", "UNAUTHORIZED", "ACCESSIBILITY_UNAVAILABLE", "OBSERVATION_TIMEOUT")) throw error
                    readableTree = false
                    treeReason = (error as? DeviceError)?.code ?: "TREE_UNAVAILABLE"
                } finally { root?.recycle() }
                val treeFinished = System.currentTimeMillis()
                captureCheck()
                if (scope != null) {
                    val path = scope.getString("element_id")
                    if (source == null || path !in source.exported) stale(service, source, "element_not_in_observation", "STALE_ELEMENT")
                    if (readableTree) targetChangeReason(source.nodes, tree.nodes, path, false)?.let { stale(service, source, it, "STALE_ELEMENT") }
                }
                val screenshotStarted = System.currentTimeMillis()
                val screenshot = if (mode != "tree") {
                    try { screenshot(service, maxDimension, budget, captureCheck) }
                    catch (error: Exception) {
                        if ((error as? DeviceError)?.code in setOf("CANCELLED", "STOPPED", "UNAUTHORIZED", "ACCESSIBILITY_UNAVAILABLE", "OBSERVATION_TIMEOUT")) throw error
                        json("available" to false, "reason" to ((error as? DeviceError)?.code ?: "SCREENSHOT_UNAVAILABLE"))
                    }.put("started_at_ms", screenshotStarted).put("finished_at_ms", System.currentTimeMillis())
                } else null
                captureCheck()
                val afterRoot = service.rootInActiveWindow
                val stableWindow = afterRoot?.windowId ?: -1
                val stableApp = afterRoot?.packageName?.toString() ?: ""
                afterRoot?.recycle()
                // Ordinary content events and IME/overlay changes do not prove cross-page mixing.
                val reason = when {
                    ready().instanceId != service.instanceId -> "service_instance_changed"
                    window != stableWindow || app != stableApp -> "window_changed"
                    geometry() != Triple(width, height, rotation) -> "display_changed"
                    else -> ""
                }
                captureDiagnostic.put("finished_at_ms", System.currentTimeMillis()).put("after_window_id", stableWindow)
                    .put("after_package_name", stableApp).put("after_ui_version", PhoneAccessibilityService.version.get())
                    .put("inconsistency_reason", reason.takeIf { it.isNotEmpty() } ?: JSONObject.NULL)
                if (reason.isNotEmpty()) fail("OBSERVATION_INCONSISTENT", "UI changed during observation collection", json(
                    "reason" to reason, "snapshot_ui_version" to before, "current_ui_version" to PhoneAccessibilityService.version.get(),
                    "events" to service.eventsSince(before, window)))
                val projected = projectTree(tree.elements, decision, compact, args.optBoolean("include_details"),
                    args.optJSONObject("region"), scope?.getString("element_id"))
                val outputElements = projected.getJSONArray("elements")
                val exported = if (mode == "screenshot") emptySet() else (0 until outputElements.length()).map {
                    outputElements.getJSONObject(it).getString("element_id") }.toSet()
                val id = "$serviceInstanceId:${UUID.randomUUID()}"
                val snapshot = Snapshot(id, before, service.instanceId, window, app, width, height,
                    rotation, SystemClock.elapsedRealtime(), tree.nodes, exported)
                candidates[id] = snapshot
                while (candidates.size > 2) candidates.remove(candidates.keys.first())
                json("observation_id" to id, "service_instance_id" to serviceInstanceId,
                    "ui_version" to before, "captured_at_ms" to System.currentTimeMillis(),
                    "window_id" to window, "package_name" to app,
                    "display" to json("id" to 0, "width" to width, "height" to height, "rotation" to rotation * 90,
                        "coordinates" to "physical_pixels", "origin" to "top_left", "includes_system_bars_and_ime" to true),
                    "tree" to if (mode == "screenshot") null else projected.put("available", readableTree).put("compact", compact)
                        .put("reason", treeReason ?: JSONObject.NULL).put("started_at_ms", treeStarted).put("finished_at_ms", treeFinished)
                        .put("truncated", tree.truncated).put("collection_truncated", tree.truncated)
                        .put("collection_complete", tree.nodes["0"]?.complete == true),
                    "screenshot" to screenshot).apply {
                    if (args.optBoolean("diagnostics")) put("windows", windowDiagnostics(service))
                    args.optJSONObject("wait_until")?.let { put("condition", evaluateCondition(it, app, tree.elements, readableTree,
                        tree.truncated, tree.nodes["0"]?.complete == true)) }
                }
            } catch (error: Exception) {
                captureDiagnostic.put("capture_error", (error as? DeviceError)?.code ?: "INTERNAL_ERROR")
                (error as? DeviceError)?.details?.optString("reason")?.takeIf { it.isNotEmpty() }?.let {
                    captureDiagnostic.put("inconsistency_reason", it)
                }
                throw error
            } finally {
                if (!captureDiagnostic.has("finished_at_ms")) captureDiagnostic.put("finished_at_ms", System.currentTimeMillis()).put("aborted", true)
            }
        }
        val retained = if (result.has("observation_id")) result else result.optJSONObject("last_observation")
        retained?.optString("observation_id")?.let { id -> candidates[id]?.let { snapshot ->
            // Include map overhead, all fingerprints and provider IDs in the retained budget.
            val bytes = 1024L + snapshot.nodes.entries.sumOf { (path, node) ->
                256L + 2L * (path.length + node.reference.content.length + node.identity.length)
            } + snapshot.exported.sumOf { 64L + it.length * 2L }
            if (!snapshots.put(client, id, bytes, snapshot, snapshot.version, createdAt = snapshot.created)) {
                val previous = result
                result = errorResult(DeviceError("BUSY", "Device snapshot memory budget is full", json("reason" to "snapshot_budget_exceeded")))
                    .put("observation_status", "failed").put("failure_stage", "snapshot_retention")
                    .put("collection_attempts", previous.optInt("collection_attempts"))
                    .put("inconsistency_detected", previous.optBoolean("inconsistency_detected"))
                if (args.has("wait_until")) result.put("condition", json("status" to "unknown", "timed_out" to false))
                previous.optJSONObject("error")?.let { result.put("previous_observation_error", it) }
            }
        } }
        if (args.optBoolean("diagnostics")) {
            for (i in 0 until captures.length()) captures.getJSONObject(i).let { capture ->
                if (!capture.has("finished_at_ms")) capture.put("finished_at_ms", System.currentTimeMillis())
                    .put("aborted", true).put("failure_stage", result.optString("failure_stage"))
            }
            result.put("diagnostics", json("started_at_ms" to started,
            "finished_at_ms" to System.currentTimeMillis(), "captures" to captures,
            "capture_diagnostics_truncated" to (result.optInt("collection_attempts") > captures.length()),
                "consistency_scope" to "active window, package and display; content events are diagnostic, not atomic tree/screenshot frames"))
        }
        return result
    }

    private fun identity(node: AccessibilityNodeInfo): List<Any?> = listOf(node.windowId, node.hashCode(),
        if (android.os.Build.VERSION.SDK_INT >= 33) node.uniqueId else null, node.viewIdResourceName, node.className?.toString())

    private fun semantics(node: AccessibilityNodeInfo) = NodeSemantics(
        checkable = node.isCheckable, checked = node.isChecked, selected = node.isSelected,
        stateDescription = node.stateDescription?.toString(),
        range = node.rangeInfo?.let { listOf(it.type, it.min.takeIf { value -> value.isFinite() },
            it.max.takeIf { value -> value.isFinite() }, it.current.takeIf { value -> value.isFinite() }) },
        collectionItem = node.collectionItemInfo?.let { listOf(it.rowIndex, it.rowSpan, it.columnIndex, it.columnSpan, it.isHeading, it.isSelected) },
        collection = node.collectionInfo?.let { listOf(it.rowCount, it.columnCount, it.isHierarchical, it.selectionMode) },
        hint = node.hintText?.toString(), error = node.error?.toString(), contentInvalid = node.isContentInvalid)

    private fun nodeText(node: AccessibilityNodeInfo): String? =
        enteredText(node.text?.toString(), node.isEditable, node.isShowingHintText)
    private fun nodeSelection(node: AccessibilityNodeInfo): Pair<Int, Int> =
        insertionSelection(nodeText(node), node.textSelectionStart, node.textSelectionEnd)

    private fun fingerprint(node: AccessibilityNodeInfo): NodeReference {
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        val value = identity(node) + listOf(nodeText(node), node.contentDescription?.toString(),
            bounds.flattenToString(), node.isEnabled, node.isVisibleToUser, node.isEditable, node.isPassword,
            logicalItemId(node))
        // Focus/selection affordances and descendant scroll state are not target identity.
        // The requested action is checked directly immediately before dispatch.
        val (start, end) = nodeSelection(node)
        return NodeReference(nodeContentHash(value, NodeSemantics()), start, end)
    }

    /** Full-display gestures may reach the IME and system UI, but never local authorization controls. */
    private fun protectLocalControls(service: PhoneAccessibilityService, points: List<Pair<Float, Float>>?,
                                     check: () -> Unit, target: AccessibilityNodeInfo? = null) {
        var examined = 0
        fun visit(node: AccessibilityNodeInfo, depth: Int) {
            check()
            if (++examined > 1500 || depth > 40) fail("LOCAL_CONTROL_UI", "Local control protection could not finish within its budget")
            val own = node.packageName?.toString() == context.packageName
            if (own) {
                val bounds = Rect(); node.getBoundsInScreen(bounds)
                if (points == null || pathIntersectsRegion(points, listOf(ScreenRect(bounds.left, bounds.top, bounds.right, bounds.bottom))))
                    fail("LOCAL_CONTROL_UI", "Phone Use authorization controls can only be operated locally")
            }
            if (own) return
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { visit(child, depth + 1) } finally { child.recycle() }
            }
        }
        if (target != null) { visit(target, 0); return }
        val root = service.rootInActiveWindow
        try {
            if (root?.packageName?.toString() == context.packageName)
                fail("LOCAL_CONTROL_UI", "Phone Use authorization controls can only be operated locally")
            if (root?.packageName?.toString() == "com.android.systemui") visit(root, 0)
        } finally { root?.recycle() }
        val windows = service.windows
        try {
            for (window in windows.filter { it.displayId == Display.DEFAULT_DISPLAY }) {
                val windowRoot = window.root ?: continue
                try {
                    // Only our own UI and System UI can host Phone Use's local controls.
                    if (windowRoot.packageName?.toString() in setOf(context.packageName, "com.android.systemui")) {
                        examined = 0
                        visit(windowRoot, 0)
                    }
                } finally { windowRoot.recycle() }
            }
        } finally { windows.forEach { it.recycle() } }
    }

    private fun windowDiagnostics(service: PhoneAccessibilityService): JSONArray {
        val windows = service.windows
        return try { JSONArray(windows.take(64).map { window ->
            val bounds = Rect(); window.getBoundsInScreen(bounds)
            json("window_id" to window.id, "display_id" to window.displayId, "type" to window.type,
                "layer" to window.layer, "active" to window.isActive, "focused" to window.isFocused,
                "bounds" to JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
        }) } finally { windows.forEach { it.recycle() } }
    }

    private fun collect(root: AccessibilityNodeInfo, width: Int, height: Int, check: () -> Unit): Tree {
        val states = LinkedHashMap<String, ElementReference>()
        val elements = LinkedHashMap<String, JSONObject>()
        val order = mutableListOf<String>()
        val retained = HashSet<String>()
        var truncated = false
        val screen = Rect(0, 0, width, height)
        fun visit(node: AccessibilityNodeInfo, path: String, depth: Int) {
            if (order.size >= 1500 || depth > 40) { truncated = true; return }
            if (order.size % 32 == 0) check()
            order.add(path)
            val bounds = Rect(); node.getBoundsInScreen(bounds)
            val reference = fingerprint(node)
            var complete = true
            for (i in 0 until node.childCount) {
                if (order.size >= 1500) { truncated = true; complete = false; break }
                val child = node.getChild(i)
                if (child == null) { complete = false; continue }
                try {
                    val childPath = "$path.$i"
                    visit(child, childPath, depth + 1)
                    if (states[childPath]?.complete != true) complete = false
                } finally { child.recycle() }
            }
            states[path] = ElementReference(reference, accessibilityIdentityHash(node), complete)
            val meaningful = !node.text.isNullOrEmpty() || !node.contentDescription.isNullOrEmpty() ||
                !node.viewIdResourceName.isNullOrEmpty() || node.isEditable || node.isClickable || node.isLongClickable || node.isScrollable ||
                !node.hintText.isNullOrEmpty() || !node.error.isNullOrEmpty() || !node.stateDescription.isNullOrEmpty() || node.actionList.isNotEmpty() ||
                retained.any { it.substringBeforeLast('.', "") == path }
            val compactKeep = node.isVisibleToUser && !bounds.isEmpty && Rect.intersects(bounds, screen) && meaningful
            if (compactKeep) retained.add(path)
            run {
                val element = json("element_id" to path, "class" to node.className?.toString(),
                    "_compact_keep" to compactKeep,
                    "_on_screen" to Rect.intersects(bounds, screen),
                    "bounds" to JSONArray(listOf(bounds.left, bounds.top, bounds.right, bounds.bottom)))
                fun optional(key: String, value: Any?, include: Boolean) { element.put(key, value ?: JSONObject.NULL) }
                optional("text", if (node.isPassword) null else nodeText(node)?.take(1024), !node.isPassword && !nodeText(node).isNullOrEmpty())
                optional("description", node.contentDescription?.toString()?.take(1024), !node.contentDescription.isNullOrEmpty())
                optional("view_id", node.viewIdResourceName, !node.viewIdResourceName.isNullOrEmpty())
                optional("enabled", node.isEnabled, !node.isEnabled)
                optional("visible", node.isVisibleToUser, !node.isVisibleToUser)
                optional("editable", node.isEditable, node.isEditable)
                optional("password", node.isPassword, node.isPassword)
                optional("focused", node.isFocused, node.isFocused)
                optional("checkable", node.isCheckable, node.isCheckable)
                optional("checked", node.isChecked, node.isCheckable)
                optional("selected", node.isSelected, node.isSelected)
                optional("state_description", node.stateDescription?.toString()?.take(1024), !node.stateDescription.isNullOrEmpty())
                optional("hint", node.hintText?.toString()?.take(1024), !node.hintText.isNullOrEmpty())
                optional("error", node.error?.toString()?.take(1024), !node.error.isNullOrEmpty())
                optional("content_invalid", node.isContentInvalid, node.isContentInvalid)
                if (!node.isPassword && (nodeText(node)?.length ?: 0) > 1024) element.put("text_truncated", true)
                    .put("_text_hash", conditionTextHash(nodeText(node).orEmpty()))
                if ((node.contentDescription?.length ?: 0) > 1024) element.put("description_truncated", true)
                    .put("_description_hash", conditionTextHash(node.contentDescription.toString()))
                val semanticState = semantics(node)
                optional("range", semanticState.range?.let { JSONArray(it) }, semanticState.range != null)
                optional("collection_item", semanticState.collectionItem?.let { JSONArray(it) }, semanticState.collectionItem != null)
                optional("collection", semanticState.collection?.let { JSONArray(it) }, semanticState.collection != null)
                element.put("actions", JSONArray(node.actionList.map { actionName(it.id) }))
                if (node.isEditable && !node.isPassword) element.put("selection", JSONArray(listOf(nodeSelection(node).first, nodeSelection(node).second)))
                elements[path] = element
            }
        }
        visit(root, "0", 0)
        val out = JSONArray()
        order.forEach { path ->
            var parent = path.substringBeforeLast('.', "")
            while (parent.isNotEmpty() && parent !in retained) parent = parent.substringBeforeLast('.', "")
            out.put(elements.getValue(path).put("parent_id", parent.takeIf { it.isNotEmpty() } ?: JSONObject.NULL))
        }
        return Tree(out, states, truncated)
    }
    private fun actionName(id: Int): String = when (id) {
        AccessibilityNodeInfo.ACTION_CLICK -> "tap"
        AccessibilityNodeInfo.ACTION_LONG_CLICK -> "long_press"
        AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> "scroll_forward"
        AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> "scroll_backward"
        AccessibilityNodeInfo.ACTION_SET_TEXT -> "set_text"
        else -> id.toString()
    }
    private fun screenshot(service: PhoneAccessibilityService, maxDimension: Int, budget: ObservationBudget, check: () -> Unit): JSONObject {
        val pending = CompletableFuture<JSONObject>()
        service.takeScreenshot(Display.DEFAULT_DISPLAY, java.util.concurrent.Executor { it.run() }, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                // Conversion is bounded to a single frame; all buffers close even after a timeout.
                try {
                    val hardware = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                        ?: throw IllegalStateException("No bitmap")
                    try {
                        val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
                            ?: throw IllegalStateException("No software bitmap")
                        try {
                            val ratio = minOf(1.0, maxDimension.toDouble() / maxOf(software.width, software.height))
                            val scaled = software.scale((software.width * ratio).toInt().coerceAtLeast(1),
                                (software.height * ratio).toInt().coerceAtLeast(1), true)
                            try {
                                val bytes = ByteArrayOutputStream()
                                scaled.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
                                pending.complete(json("available" to true, "mime_type" to "image/jpeg",
                                    "width" to scaled.width, "height" to scaled.height,
                                    "scale_x" to software.width.toDouble() / scaled.width,
                                    "scale_y" to software.height.toDouble() / scaled.height,
                                    "offset_x" to 0, "offset_y" to 0,
                                    "data_base64" to Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP)))
                            } finally { if (scaled !== software) scaled.recycle() }
                        } finally { software.recycle() }
                    } finally { hardware.recycle() }
                } catch (_: Exception) { pending.complete(json("available" to false, "reason" to "SCREENSHOT_CONVERSION_FAILED")) }
                finally { result.hardwareBuffer.close() }
            }
            override fun onFailure(errorCode: Int) {
                pending.complete(json("available" to false, "reason" to when (errorCode) {
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> "SCREENSHOT_RATE_LIMITED"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> "SECURE_WINDOW"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> "ACCESSIBILITY_UNAVAILABLE"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_DISPLAY -> "DISPLAY_UNSUPPORTED"
                    AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> "NO_ACTIVE_WINDOW"
                    else -> "SCREENSHOT_UNAVAILABLE"
                }, "android_error_code" to errorCode))
            }
        })
        val screenshotDeadline = SystemClock.elapsedRealtime() + minOf(5000L, (budget.remaining() - 25).coerceAtLeast(0))
        while (true) {
            check()
            val remaining = screenshotDeadline - SystemClock.elapsedRealtime()
            if (remaining <= 0) return json("available" to false, "reason" to "SCREENSHOT_TIMEOUT")
            try { return pending.get(minOf(50L, remaining), TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { /* bounded cancellation polling */ }
        }
    }
    override fun listApps(): JSONObject {
        val pm = context.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }.sortedBy { it.loadLabel(pm).toString() }
        return json("apps" to JSONArray(apps.map { json("package_name" to it.activityInfo.packageName, "label" to it.loadLabel(pm).toString()) }))
    }
    override fun waitForChange(args: JSONObject, check: () -> Unit): JSONObject {
        val timeout = args.integer("timeout_ms", 5000, 1, 10_000)
        val raw = args.opt("ui_version") as? Number ?: fail("INVALID_ARGUMENT", "ui_version is required")
        val baseline = raw.toLong()
        val deadline = SystemClock.elapsedRealtime() + timeout
        do {
            check(); ready()
            val version = PhoneAccessibilityService.version.get()
            if (version != baseline) return json("changed" to true, "ui_version" to version)
            Thread.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        return json("changed" to false, "ui_version" to PhoneAccessibilityService.version.get(), "timed_out" to true)
    }
    private fun diagnostics(service: PhoneAccessibilityService, snapshot: Snapshot?, reason: String): JSONObject = json(
        "reason" to reason, "action_executed" to false,
        "snapshot_age_ms" to snapshot?.let { SystemClock.elapsedRealtime() - it.created },
        "snapshot_ui_version" to snapshot?.version, "current_ui_version" to PhoneAccessibilityService.version.get(),
        "event_history_complete" to snapshot?.let { service.eventHistoryComplete(it.version) },
        "event_evidence_scope" to "delivered_accessibility_events",
        "events" to if (snapshot != null) service.eventsSince(snapshot.version, snapshot.window) else JSONArray())

    private fun stale(service: PhoneAccessibilityService, snapshot: Snapshot?, reason: String,
                      code: String = "STALE_OBSERVATION"): Nothing =
        fail(code, "Observation cannot safely identify this target; observe again", diagnostics(service, snapshot, reason))

    private fun validateContext(service: PhoneAccessibilityService, args: JSONObject, client: String): Snapshot? {
        val snapshot = if (args.has("observation_id")) {
            val id = args.string("observation_id")
            if (id.substringBefore(':', "") != serviceInstanceId) stale(service, null, "service_instance_changed")
            val found = snapshots.get(client, id)
            found.value ?: run {
                val detail = diagnostics(service, found.rejectedValue, found.reason ?: "not_found")
                found.created?.let { detail.put("snapshot_age_ms", SystemClock.elapsedRealtime() - it) }
                found.version?.let { detail.put("snapshot_ui_version", it) }
                fail("STALE_OBSERVATION", "Snapshot is no longer available to this client; observe again", detail)
            }
        } else null
        if (ready().instanceId != service.instanceId) stale(service, snapshot, "service_instance_changed")
        val root = service.rootInActiveWindow
        try {
            if (args.has("expected_window_id") && root?.windowId != args.integer("expected_window_id", -1, -1, Int.MAX_VALUE))
                stale(service, snapshot, "expected_window_mismatch", "WINDOW_MISMATCH")
            if (snapshot != null) {
                if (snapshot.accessibilityInstance != service.instanceId) stale(service, snapshot, "service_instance_changed")
                if (SystemClock.elapsedRealtime() - snapshot.created > 30_000) stale(service, snapshot, "expired")
                if (args.has("element_id") && (root?.windowId != snapshot.window || root?.packageName?.toString() != snapshot.app))
                    stale(service, snapshot, "window_changed")
                if (geometry() != Triple(snapshot.width, snapshot.height, snapshot.rotation)) stale(service, snapshot, "display_changed")
            }
        } finally { root?.recycle() }
        return snapshot
    }
    private fun validateTarget(service: PhoneAccessibilityService, snapshot: Snapshot, node: AccessibilityNodeInfo,
                               path: String, insert: Boolean) {
        if (!node.refresh()) stale(service, snapshot, "element_missing", "STALE_ELEMENT")
        val actual = ElementReference(fingerprint(node), accessibilityIdentityHash(node))
        targetChangeReason(snapshot.nodes, mapOf(path to actual), path, insert)?.let {
            stale(service, snapshot, it, "STALE_ELEMENT")
        }
        val bounds = Rect(); node.getBoundsInScreen(bounds)
        if (!node.isEnabled || !node.isVisibleToUser || bounds.isEmpty || !Rect.intersects(bounds, Rect(0, 0, snapshot.width, snapshot.height)))
            stale(service, snapshot, "target_unavailable", "ELEMENT_UNAVAILABLE")
    }

    private fun target(service: PhoneAccessibilityService, args: JSONObject, snapshot: Snapshot?, name: String, check: () -> Unit): AccessibilityNodeInfo {
        if (snapshot == null) fail("INVALID_ARGUMENT", "element_id requires observation_id")
        val path = args.string("element_id")
        if (path !in snapshot.exported) stale(service, snapshot, "element_not_in_observation", "STALE_ELEMENT")
        var node = service.rootInActiveWindow ?: stale(service, snapshot, "window_changed")
        try {
            for (part in path.split('.').drop(1)) {
                check()
                val child = node.getChild(part.toInt()) ?: stale(service, snapshot, "element_missing", "STALE_ELEMENT")
                node.recycle(); node = child
            }
            validateTarget(service, snapshot, node, path, name == "input_text" && args.optString("mode") == "insert")
            return node
        } catch (e: Exception) { node.recycle(); throw e }
    }
    override fun act(name: String, args: JSONObject, check: () -> Unit): JSONObject = act(name, args, "legacy", check)

    override fun act(name: String, args: JSONObject, client: String, check: () -> Unit): JSONObject = try {
        actChecked(name, args, client, check)
    } catch (error: DeviceError) {
        if (error.code != "RESULT_UNKNOWN") error.details.put("action_executed", false)
        throw error
    }

    private fun actChecked(name: String, args: JSONObject, client: String, check: () -> Unit): JSONObject {
        val service = ready()
        if (name in setOf("tap", "long_press", "swipe", "scroll") && !args.has("element_id") && !args.has("observation_id"))
            fail("INVALID_ARGUMENT", "Coordinate actions require observation_id", json("reason" to "observation_required"))
        val snapshot = validateContext(service, args, client)
        val activeRoot = service.rootInActiveWindow
        try {
            if (activeRoot?.packageName?.toString() == context.packageName && name !in setOf("home", "back", "recents", "launch_app"))
                fail("LOCAL_CONTROL_UI", "Phone Use authorization controls can only be operated locally")
        } finally { activeRoot?.recycle() }
        if (name == "input_text" || (name in setOf("tap", "long_press", "scroll") && args.has("element_id"))) {
            val node = target(service, args, snapshot, name, check)
            try {
                val action = when (name) {
                    "tap" -> AccessibilityNodeInfo.ACTION_CLICK
                    "long_press" -> AccessibilityNodeInfo.ACTION_LONG_CLICK
                    "scroll" -> when (args.string("direction")) {
                        "forward" -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                        "backward" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                        else -> fail("INVALID_ARGUMENT", "Node scroll direction must be forward or backward")
                    }
                    else -> AccessibilityNodeInfo.ACTION_SET_TEXT
                }
                check()
                validateContext(service, args, client)
                validateTarget(service, snapshot!!, node, args.getString("element_id"), name == "input_text" && args.optString("mode") == "insert")
                if (node.actionList.none { it.id == action }) fail("ACTION_UNSUPPORTED", "Node does not support this action; no automatic fallback")
                if (node.packageName?.toString() == context.packageName || node.packageName?.toString() == "com.android.systemui")
                    protectLocalControls(service, null, check, node)
                val bundle = if (name == "input_text") textArguments(node, args) else null
                // Some editors expose SET_SELECTION only once they contain text. Cursor restoration
                // is optional; SET_TEXT can still perform the requested insertion exactly once.
                val cursor = if (name == "input_text" && args.optString("mode") == "insert" &&
                    node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_SELECTION })
                    nodeSelection(node).first + args.getString("text").length else null
                check()
                validateContext(service, args, client)
                if (ready().instanceId != service.instanceId) stale(service, snapshot, "service_instance_changed")
                if (geometry() != Triple(snapshot.width, snapshot.height, snapshot.rotation)) stale(service, snapshot, "display_changed")
                if (!node.performAction(action, bundle)) fail("ACTION_REJECTED", "Android rejected node action; observe before retrying")
                val restored = if (cursor != null && runCatching { check() }.isSuccess) runCatching { node.performAction(
                    AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, cursor)
                        putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, cursor)
                    }) }.getOrDefault(false) else null
                return json("mechanism" to "node_action", "confirmation" to "android_accepted", "business_success" to JSONObject.NULL,
                    "text_verified" to if (name == "input_text") false else null, "cursor_restored" to restored)
            } finally { node.recycle() }
        }
        when (name) {
            "send_voice" -> return sendVoice(service, args, client, snapshot ?: fail("STALE_OBSERVATION", "Observe the voice button first"), check)
            "tap", "long_press", "swipe", "scroll" -> return gesture(service, name, args) { points ->
                check()
                validateContext(service, args, client)
                protectLocalControls(service, points, check)
                check()
                validateContext(service, args, client)
            }
            "back", "home", "recents" -> {
                check()
                val action = when (name) { "back" -> AccessibilityService.GLOBAL_ACTION_BACK; "home" -> AccessibilityService.GLOBAL_ACTION_HOME; else -> AccessibilityService.GLOBAL_ACTION_RECENTS }
                if (!service.performGlobalAction(action)) fail("ACTION_REJECTED", "Android rejected navigation")
            }
            "launch_app" -> {
                val packageName = args.string("package_name")
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: fail("APP_UNAVAILABLE", "Application is not discoverable or launchable")
                check()
                try {
                    service.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: ActivityNotFoundException) {
                    fail("APP_UNAVAILABLE", "Application is no longer launchable")
                } catch (_: SecurityException) {
                    launchGuidance.recordUnconfirmed(packageName)
                    fail("ACTION_REJECTED", "Android denied the launch request. ${AppLaunchGuidance.permissionHint()}",
                        json("reason" to "launch_permission_denied", "package_name" to packageName))
                }
                try {
                    return AppLaunchConfirmation(SystemClock::elapsedRealtime, Thread::sleep).awaitForeground(packageName, check) {
                        if (ready().instanceId != service.instanceId) fail("ACCESSIBILITY_UNAVAILABLE", "Accessibility service changed")
                        val root = service.rootInActiveWindow
                        try { root?.packageName?.toString() } finally { root?.recycle() }
                    }.also { launchGuidance.clearWarning() }
                } catch (error: DeviceError) {
                    if (error.details.optString("reason") == "launch_not_confirmed") {
                        launchGuidance.recordUnconfirmed(packageName)
                        throw DeviceError(error.code, "${error.message}. ${AppLaunchGuidance.permissionHint()}", error.details)
                    }
                    throw error
                }
            }
        }
        return json("confirmation" to "android_accepted", "business_success" to JSONObject.NULL)
    }

    private fun sendVoice(service: PhoneAccessibilityService, args: JSONObject, client: String, snapshot: Snapshot, check: () -> Unit): JSONObject {
        val attachment = VoiceProvider.attachment ?: fail("VOICE_UNAVAILABLE", "Start the adb voice helper first")
        attachment.routingError?.let { fail("VOICE_UNAVAILABLE", it) }
        val helper = attachment.helper
        val rate = args.getInt("sample_rate")
        val encoded = args.string("pcm_base64", 3840000)
        val pcm = try { java.util.Base64.getDecoder().decode(encoded) }
            catch (_: IllegalArgumentException) { fail("INVALID_ARGUMENT", "Invalid PCM base64") }
        if (pcm.isEmpty() || pcm.size % 2 != 0 || pcm.size > rate * 60)
            fail("INVALID_ARGUMENT", "Provide up to 30 seconds of mono PCM16")
        val marker = args.optString("sent_marker")
        fun inspectWindow(lookForMarker: Boolean = false): Boolean {
            check()
            if (ready().instanceId != service.instanceId || geometry() != Triple(snapshot.width, snapshot.height, snapshot.rotation))
                fail("STALE_OBSERVATION", "Service or display changed")
            val root = service.rootInActiveWindow ?: fail("WINDOW_MISMATCH", "No active application")
            try {
                if (root.windowId != snapshot.window || root.packageName?.toString() != snapshot.app)
                    fail("WINDOW_MISMATCH", "Voice target window changed")
                val window = root.window ?: fail("WINDOW_MISMATCH", "Voice target window unavailable")
                try { if (window.displayId != Display.DEFAULT_DISPLAY) fail("DISPLAY_UNSUPPORTED", "Voice button must be on the default display") }
                finally { window.recycle() }
                if (!lookForMarker || marker.isEmpty()) return false
                val matches = root.findAccessibilityNodeInfosByText(marker)
                try { return matches.any { it.isVisibleToUser && (it.text?.toString() == marker || it.contentDescription?.toString() == marker) } }
                finally { matches.forEach { it.recycle() } }
            } finally { root.recycle() }
        }
        inspectWindow()
        if (snapshot.app in setOf("", context.packageName, "com.android.systemui"))
            fail("LOCAL_CONTROL_UI", "Select a voice button in the target app")
        val session = try { helper.prepare(snapshot.app, rate, pcm.size) }
            catch (e: Exception) { fail("VOICE_UNAVAILABLE", e.message ?: "ADB audio route unavailable") }
        var attempted = false
        var markerEligible = false
        try {
            for (offset in pcm.indices step 32768) {
                check()
                session.append(pcm.copyOfRange(offset, minOf(pcm.size, offset + 32768)))
            }
            val point = if (args.has("element_id")) {
                val node = target(service, args, snapshot, "send_voice", check)
                try {
                    val bounds = Rect().also(node::getBoundsInScreen)
                    if (!bounds.intersect(0, 0, snapshot.width, snapshot.height)) fail("ELEMENT_UNAVAILABLE", "Voice button is off screen")
                    bounds.exactCenterX() to bounds.exactCenterY()
                } finally { node.recycle() }
            } else {
                val x = args.getDouble("x").toFloat()
                val y = args.getDouble("y").toFloat()
                if (x < 0 || y < 0 || x >= snapshot.width || y >= snapshot.height) fail("INVALID_ARGUMENT", "Voice button outside display")
                x to y
            }
            protectLocalControls(service, listOf(point), check)
            validateContext(service, args, client)
            markerEligible = marker.isNotEmpty() && !inspectWindow(true)
            inspectWindow()
            attempted = true
            session.press(point.first, point.second)
            while (true) {
                val markerVisible = inspectWindow(markerEligible)
                if (session.released()) break
                if (markerVisible) markerEligible = false
                Thread.sleep(20)
            }
        } catch (e: Exception) {
            if (attempted) fail("RESULT_UNKNOWN", "Voice gesture interrupted; inspect before retrying: ${e.message}")
            if (e is DeviceError) throw e
            fail("VOICE_UNAVAILABLE", e.message ?: "Voice helper unavailable before touch")
        } finally { runCatching { session.close() } }
        var confirmed = false
        if (markerEligible) {
            val until = SystemClock.elapsedRealtime() + 1500
            try {
                while (!confirmed && SystemClock.elapsedRealtime() < until) {
                    confirmed = inspectWindow(true)
                    if (!confirmed) Thread.sleep(60)
                }
            } catch (_: Exception) { /* Release already happened; evidence is unavailable. */ }
        }
        return json("mechanism" to "adb_voice", "confirmation" to if (confirmed) "sent_confirmed" else "released",
            "business_success" to if (confirmed) true else JSONObject.NULL)
    }
    private fun textArguments(node: AccessibilityNodeInfo, args: JSONObject): Bundle {
        val text = args.opt("text") as? String ?: fail("INVALID_ARGUMENT", "text must be a string")
        if (text.length > 10_000) fail("INVALID_ARGUMENT", "text exceeds 10000 characters")
        if (!node.isEditable) fail("ACTION_UNSUPPORTED", "Target is not editable")
        val value = when (args.string("mode")) {
            "replace" -> text
            "insert" -> {
                if (node.isPassword) fail("ACTION_UNSUPPORTED", "Cannot safely insert into a password field")
                val old = nodeText(node) ?: fail("ACTION_UNSUPPORTED", "Current text is not readable")
                val (start, end) = nodeSelection(node)
                if (start < 0 || end < start || end > old.length || old.length + text.length > 20_000)
                    fail("ACTION_UNSUPPORTED", "Text selection is not available or content is too large")
                old.substring(0, start) + text + old.substring(end)
            }
            else -> fail("INVALID_ARGUMENT", "mode must be replace or insert")
        }
        return Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value) }
    }
    private fun gesture(service: PhoneAccessibilityService, name: String, args: JSONObject,
        check: (List<Pair<Float, Float>>) -> Unit): JSONObject {
        val (width, height, _) = geometry()
        fun point(obj: JSONObject): Pair<Float, Float> {
            val x = (obj.opt("x") as? Number)?.toDouble() ?: fail("INVALID_ARGUMENT", "x is required")
            val y = (obj.opt("y") as? Number)?.toDouble() ?: fail("INVALID_ARGUMENT", "y is required")
            if (!x.isFinite() || !y.isFinite() || x < 0 || y < 0 || x >= width || y >= height)
                fail("INVALID_ARGUMENT", "Coordinates must be inside the display")
            return x.toFloat() to y.toFloat()
        }
        val points = if (name == "swipe") {
            val path = args.optJSONArray("points") ?: fail("INVALID_ARGUMENT", "points array is required")
            if (path.length() !in 2..64) fail("INVALID_ARGUMENT", "Provide 2..64 points")
            (0 until path.length()).map { point(path.optJSONObject(it) ?: fail("INVALID_ARGUMENT", "Each point must be an object")) }
        } else if (name == "scroll") {
            val region = args.optJSONObject("region") ?: fail("INVALID_ARGUMENT", "Coordinate scroll requires region")
            val left = region.integer("left", -1, 0, width - 1); val right = region.integer("right", -1, 1, width)
            val top = region.integer("top", -1, 0, height - 1); val bottom = region.integer("bottom", -1, 1, height)
            if (left < 0 || top < 0 || right <= left || bottom <= top) fail("INVALID_ARGUMENT", "Invalid region bounds")
            val x = (left + right) / 2f; val y = (top + bottom) / 2f
            val dx = (right - left) * .3f; val dy = (bottom - top) * .3f
            when (args.string("direction")) {
                "forward", "down" -> listOf(x to y + dy, x to y - dy)
                "backward", "up" -> listOf(x to y - dy, x to y + dy)
                "left" -> listOf(x - dx to y, x + dx to y)
                "right" -> listOf(x + dx to y, x - dx to y)
                else -> fail("INVALID_ARGUMENT", "Invalid scroll direction")
            }
        } else listOf(point(args))
        val duration = args.integer("duration_ms", if (name == "tap") 80 else 600, if (name == "long_press") 500 else 1, 5000)
        val path = Path().apply { moveTo(points[0].first, points[0].second); points.drop(1).forEach { lineTo(it.first, it.second) } }
        val result = CompletableFuture<Boolean>()
        check(points)
        val accepted = service.dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration.toLong())).build(),
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) { result.complete(true) }
                override fun onCancelled(gestureDescription: GestureDescription?) { result.complete(false) }
            }, null)
        if (!accepted) fail("ACTION_REJECTED", "Android rejected the gesture")
        val completed = try { result.get(duration + 2000L, TimeUnit.MILLISECONDS) }
        catch (_: TimeoutException) { fail("RESULT_UNKNOWN", "Gesture callback timed out; do not automatically replay") }
        if (!completed) fail("RESULT_UNKNOWN", "Gesture was interrupted and may have partially executed")
        return json("mechanism" to "gesture", "confirmation" to "gesture_completed", "business_success" to JSONObject.NULL)
    }
}
