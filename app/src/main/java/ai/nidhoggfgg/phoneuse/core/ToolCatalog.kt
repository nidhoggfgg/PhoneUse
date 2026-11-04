package ai.nidhoggfgg.phoneuse.core

import org.json.JSONArray
import org.json.JSONObject

object ToolCatalog {
    fun validate(name: String, args: JSONObject) {
        val tools = all()
        val tool = (0 until tools.length()).map { tools.getJSONObject(it) }.find { it.getString("name") == name }
            ?: fail("UNKNOWN_TOOL", "Unknown tool: $name")
        validateObject(args, tool.getJSONObject("inputSchema"), "arguments")
        if (name in setOf("tap", "long_press", "send_voice")) {
            val node = args.has("element_id")
            if (node && (args.has("x") || args.has("y"))) fail("INVALID_ARGUMENT", "Choose element_id OR coordinates")
            if (!node && (!args.has("x") || !args.has("y"))) fail("INVALID_ARGUMENT", "Supply element_id OR x and y")
        }
        if (name == "scroll" && args.has("element_id") == args.has("region")) fail("INVALID_ARGUMENT", "Choose element_id OR region")
        if (args.has("element_id") && !args.has("observation_id")) fail("INVALID_ARGUMENT", "element_id requires observation_id")
        if (name in setOf("tap", "long_press", "swipe", "scroll") && !args.has("observation_id"))
            fail("INVALID_ARGUMENT", "Coordinate and node actions require observation_id")
        if (name == "observe") validateObservation(args)
        args.optJSONObject("observation_options")?.let {
            if (!args.optBoolean("observe_after") && !args.optBoolean("observe_on_rejection"))
                fail("INVALID_ARGUMENT", "observation_options requires observe_after or observe_on_rejection")
            validateObservation(it)
        }
    }
    private fun validateObservation(args: JSONObject) {
        args.optJSONObject("wait_until")?.let { condition ->
            if (!condition.has("package_name") && !condition.has("element"))
                fail("INVALID_ARGUMENT", "wait_until must contain package_name or element")
            condition.optJSONObject("element")?.let { selector ->
                if (listOf("view_id", "text", "description").none(selector::has))
                    fail("INVALID_ARGUMENT", "wait_until.element requires view_id, text, or description")
            }
        }
        args.optJSONObject("region")?.let {
            if (it.getInt("left") >= it.getInt("right") || it.getInt("top") >= it.getInt("bottom"))
                fail("INVALID_ARGUMENT", "Observation region must have positive width and height")
        }
    }
    private fun validateObject(value: JSONObject, schema: JSONObject, path: String) {
        val required = schema.optJSONArray("required") ?: JSONArray()
        for (i in 0 until required.length()) if (!value.has(required.getString(i))) fail("INVALID_ARGUMENT", "$path.${required.getString(i)} is required")
        val properties = schema.getJSONObject("properties")
        value.keys().forEach { key ->
            val field = properties.optJSONObject(key) ?: fail("INVALID_ARGUMENT", "Unknown field $path.$key")
            val item = value.get(key)
            val valid = when (field.getString("type")) {
                "string" -> item is String
                "boolean" -> item is Boolean
                "number" -> item is Number && item.toDouble().isFinite()
                "integer" -> item is Number && item.toDouble().isFinite() && item.toDouble() % 1.0 == 0.0
                "object" -> item is JSONObject
                "array" -> item is JSONArray
                else -> false
            }
            if (!valid) fail("INVALID_ARGUMENT", "Invalid type for $path.$key")
            field.optJSONArray("enum")?.let { allowed ->
                if ((0 until allowed.length()).none { allowed.get(it) == item })
                    fail("INVALID_ARGUMENT", "Unsupported value for $path.$key")
            }
            if (item is Number && ((field.has("minimum") && item.toDouble() < field.getDouble("minimum")) ||
                        (field.has("maximum") && item.toDouble() > field.getDouble("maximum"))))
                fail("INVALID_ARGUMENT", "Out of range value for $path.$key")
            if (item is String && (item.length < field.optInt("minLength", 0) || item.length > field.optInt("maxLength", Int.MAX_VALUE)))
                fail("INVALID_ARGUMENT", "Invalid length for $path.$key")
            if (item is JSONObject) validateObject(item, field, "$path.$key")
            if (item is JSONArray) {
                if (item.length() < field.optInt("minItems", 0) || item.length() > field.optInt("maxItems", 64)) fail("INVALID_ARGUMENT", "Invalid array length")
                for (i in 0 until item.length()) validateObject(item.optJSONObject(i) ?: fail("INVALID_ARGUMENT", "Expected point object"), field.getJSONObject("items"), "$path.$key[$i]")
            }
        }
    }
    private fun string(description: String) = json("type" to "string", "description" to description)
    private fun number(description: String) = json("type" to "number", "description" to description)
    private fun integer(description: String) = json("type" to "integer", "description" to description)
    private fun choice(description: String, vararg values: String) = string(description).put("enum", JSONArray(values.toList()))
    private fun bounded(description: String, min: Int, max: Int) = integer(description).put("minimum", min).put("maximum", max)
    private fun nonempty(description: String) = string(description).put("minLength", 1).put("maxLength", 256)
    private fun objectSchema(fields: Map<String, JSONObject>, required: List<String> = emptyList()) = json(
        "type" to "object", "properties" to JSONObject(fields), "required" to JSONArray(required), "additionalProperties" to false)
    private fun observationFields(): Map<String, JSONObject> = mapOf(
        "mode" to choice("tree, screenshot, or both (default)", "tree", "screenshot", "both"),
        "max_dimension" to bounded("Screenshot longest side, default 1280", 320, 2048),
        "compact" to json("type" to "boolean", "description" to "Compact visible tree by default; full bounded tree requires output=legacy and compact=false"),
        "stable_wait_ms" to bounded("Optional per-attempt quiet wait; default 0 (immediate capture); not a readiness condition", 0, 2000).put("default", 0),
        "max_attempts" to bounded("Consistency attempts in each collection, default 3", 1, 3),
        "timeout_ms" to bounded("Total collection and condition budget; default 10000 with wait_until, otherwise 20000", 1, 60000),
        "wait_until" to objectSchema(mapOf(
            "package_name" to nonempty("Required active application; all supplied conditions must match"),
            "element" to objectSchema(mapOf(
                "view_id" to nonempty("Exact Android resource ID"), "text" to string("Exact node text"),
                "description" to string("Exact content description"),
                "visible" to json("type" to "boolean"), "enabled" to json("type" to "boolean"),
                "editable" to json("type" to "boolean"),
                "action" to choice("Required supported node action", "tap", "long_press", "set_text", "scroll_forward", "scroll_backward"),
                "match" to choice("unique (default) rejects ambiguous matches; exists only waits for any match", "unique", "exists"))))),
        "output" to choice("decision (default) groups static labels under actual action targets; label_sources are evidence, not exported action IDs; legacy exposes full fields", "decision", "legacy").put("default", "decision"),
        "diagnostics" to json("type" to "boolean", "description" to "Include bounded collection timing and consistency diagnostics"),
        "include_details" to json("type" to "boolean", "description" to "Include detailed node fields and unfold merged label nodes in decision output"),
        "region" to objectSchema(listOf("left", "top", "right", "bottom").associateWith { bounded("Display pixel boundary", Int.MIN_VALUE, Int.MAX_VALUE) },
            listOf("left", "top", "right", "bottom")),
        "subtree" to objectSchema(mapOf("observation_id" to nonempty("Original client-owned observation to revalidate"),
            "element_id" to nonempty("Original node path to revalidate before projecting")), listOf("observation_id", "element_id"))
    )
    private val common = mapOf(
        "request_id" to string("Unique action ID, retained for 10 minutes per client; reuse exactly on retransmission"),
        "observation_id" to string("Observation used to select the target; required for node and coordinate actions"),
        "expected_window_id" to integer("Reject a different active window"),
        "observe_after" to json("type" to "boolean", "description" to "Default false. Explicitly attach observation after execution; no page wait unless observation_options requests it; failure never replays the action", "default" to false),
        "observe_on_rejection" to json("type" to "boolean", "description" to "Attach a fresh observation only after a terminal target-validation rejection confirmed not executed"),
        "observation_options" to objectSchema(observationFields()),
        "lease_id" to string("Optional developer reservation; required while this device is reserved")
    )
    private val point = json("type" to "object", "properties" to json("x" to number("Display pixel x"), "y" to number("Display pixel y")),
        "required" to JSONArray(listOf("x", "y")), "additionalProperties" to false)
    private fun tool(name: String, description: String, fields: Map<String, JSONObject> = emptyMap(), required: List<String> = emptyList(), action: Boolean = false): JSONObject {
        val properties = json(
            "device_id" to string("Optional direct-HTTP identity guard; required by the multi-device bridge"),
            "service_instance_id" to string("Optional service instance guard from the last observation; checked before dispatch")
        )
        if (action) common.forEach { (key, value) -> properties.put(key, value) }
        fields.forEach { (key, value) -> properties.put(key, value) }
        return json("name" to name, "description" to if (action) "$description. Executes once and returns execution only by default; AI chooses subsequent waits and observations. Android acceptance is not business success." else description, "inputSchema" to json("type" to "object",
            "properties" to properties, "required" to JSONArray((if (action) listOf("request_id") else emptyList()) + required),
            "additionalProperties" to false))
    }
    fun all(): JSONArray = JSONArray(listOf(
        tool("get_capabilities", "Read Android capability availability and limitations"),
        tool("get_status", "Read control state or the retained result of an action", mapOf("request_id" to string("Optional action to query"))),
        tool("cancel", "Cancel queued actions; an in-flight action may still complete", mapOf("request_id" to string("Omit to cancel all pending actions for this client"))),
        tool("acquire_device", "Optionally reserve this device for a bounded test sequence; local pause and stop always override", mapOf("ttl_ms" to integer("1000..300000, default 60000"), "lease_id" to string("Current reservation ID to renew"))),
        tool("release_device", "Release this client's developer reservation", mapOf("lease_id" to string("Reservation to release")), listOf("lease_id")),
        tool("observe", "Capture current state immediately; nodes default to decision output. Optional explicit bounded waits may match package and exact elements. Channels report availability separately; tree and screenshot are not atomic", observationFields()),
        tool("list_apps", "List discoverable launchable applications"),
        tool("wait_for_change", "Wait for an accessibility UI version change; no semantic condition evaluation", mapOf("ui_version" to integer("Version from observe"), "timeout_ms" to integer("1..10000, default 5000")), listOf("ui_version")),
        tool("tap", "Tap a snapshot element OR full-display coordinates, including keyboard and system UI", mapOf("element_id" to string("Snapshot element"), "x" to number("Display x"), "y" to number("Display y")), listOf("observation_id"), true),
        tool("long_press", "Long press an element OR physical display coordinates", mapOf("element_id" to string("Snapshot element"), "x" to number("Display x"), "y" to number("Display y"), "duration_ms" to integer("500..5000 for coordinate gesture")), listOf("observation_id"), true),
        tool("send_voice", "Hold a voice button and inject prepared audio only after the target records from the attached ADB helper",
            mapOf("element_id" to string("Observed button"), "x" to number("Display x"), "y" to number("Display y"),
                "pcm_base64" to string("Mono signed little-endian PCM16, at most 30 seconds").put("maxLength", 3840000),
                "sample_rate" to bounded("Sample rate in Hz", 8000, 48000),
                "sent_marker" to nonempty("Optional exact visible text or description unique to successful sending of this message, absent before release")),
            listOf("observation_id", "pcm_base64", "sample_rate"), true),
        tool("swipe", "Swipe along 2..64 physical display points", mapOf("points" to json("type" to "array", "items" to point, "minItems" to 2, "maxItems" to 64), "duration_ms" to integer("1..5000, default 600")), listOf("observation_id", "points"), true),
        tool("scroll", "Scroll a node forward/backward or a rectangular screen region up/down/left/right", mapOf("element_id" to string("Snapshot container"), "direction" to string("forward, backward, up, down, left, right"),
            "region" to json("type" to "object", "properties" to json("left" to integer("Left pixel"), "top" to integer("Top pixel"), "right" to integer("Right pixel"), "bottom" to integer("Bottom pixel")), "required" to JSONArray(listOf("left", "top", "right", "bottom")), "additionalProperties" to false)), listOf("observation_id", "direction"), true),
        tool("input_text", "Replace all text or insert at the reported selection. Revalidates the specified editor without implicit focus or readback; returns text_verified=false, so explicitly observe to check the written text. Insert requires readable text and replaces any selected range", mapOf("element_id" to string("Editable element"), "text" to string("Text, may be empty; max 10000 characters"), "mode" to string("replace or insert")), listOf("observation_id", "element_id", "text", "mode"), true),
        tool("back", "Android Back", action = true), tool("home", "Android Home", action = true), tool("recents", "Android Recents", action = true),
        tool("launch_app", "Launch a discoverable package with an Android Intent", mapOf("package_name" to string("Android package")), listOf("package_name"), true)
    ))
}
