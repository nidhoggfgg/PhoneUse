package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.DeviceError
import ai.nidhoggfgg.phoneuse.core.DeviceController
import ai.nidhoggfgg.phoneuse.core.json
import ai.nidhoggfgg.phoneuse.device.AndroidBackend
import ai.nidhoggfgg.phoneuse.device.AppLaunchGuidance
import ai.nidhoggfgg.phoneuse.device.PhoneAccessibilityService
import android.app.UiAutomation
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** Install :fixture:installDebug first. These exercise Android's real tree/events and receiver counts. */
@RunWith(AndroidJUnit4::class)
class ImprovementFixtureTest {
    private lateinit var backend: AndroidBackend

    companion object {
        private const val FIXTURE = "ai.nidhoggfgg.phoneuse.fixture"
        private lateinit var automation: UiAutomation
        private var originalServices = ""
        private var originalEnabled = "0"
        private var originalHardKeyboard = "0"
        private fun shell(command: String): String = automation.executeShellCommand(command).use {
            ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
        }
        @BeforeClass @JvmStatic fun enableRealAccessibilityService() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            originalServices = shell("settings get secure enabled_accessibility_services").takeUnless { it == "null" } ?: ""
            originalHardKeyboard = shell("settings get secure show_ime_with_hard_keyboard")
            shell("settings put secure show_ime_with_hard_keyboard 1")
            originalEnabled = shell("settings get secure accessibility_enabled").takeUnless { it == "null" } ?: "0"
            val component = "ai.nidhoggfgg.phoneuse/ai.nidhoggfgg.phoneuse.device.PhoneAccessibilityService"
            val others = originalServices.split(':').filter { it.isNotBlank() && it != component && it != "ai.nidhoggfgg.phoneuse/.device.PhoneAccessibilityService" }
            // Starting instrumentation restarts the target process. Toggle the setting to rebind
            // a service that Android may otherwise retain in its crashed-service bookkeeping.
            if (others.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services ${others.joinToString(":")}")
            SystemClock.sleep(300)
            val enabled = (others + component).distinct().joinToString(":")
            shell("settings put secure enabled_accessibility_services $enabled")
            shell("settings put secure accessibility_enabled 1")
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (PhoneAccessibilityService.current == null && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertNotNull("Real Phone Use accessibility service must bind: ${shell("dumpsys accessibility").take(6000)}", PhoneAccessibilityService.current)
            assertTrue("Install the separate debug fixture APK before running these tests", shell("pm path $FIXTURE").contains("package:"))
        }
        @AfterClass @JvmStatic fun restoreAccessibilitySetting() {
            shell("am force-stop $FIXTURE")
            if (originalServices.isEmpty()) shell("settings delete secure enabled_accessibility_services")
            else shell("settings put secure enabled_accessibility_services $originalServices")
            shell("settings put secure accessibility_enabled $originalEnabled")
            if (originalHardKeyboard == "null") shell("settings delete secure show_ime_with_hard_keyboard")
            else shell("settings put secure show_ime_with_hard_keyboard $originalHardKeyboard")
        }
    }

    @Before fun backendForTest() { backend = AndroidBackend(InstrumentationRegistry.getInstrumentation().targetContext) }

    @Test fun launchAppConfirmsTheRealForegroundAndClearsPermissionReminder() {
        val guidance = AppLaunchGuidance(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = guidance.unconfirmedPackage
        try {
            shell("am force-stop $FIXTURE")
            // Wait for Home to finish; an asynchronous Home action can overtake the launch.
            shell("am start -W -a android.intent.action.MAIN -c android.intent.category.HOME")
            guidance.recordUnconfirmed(FIXTURE)
            val result = backend.act("launch_app", json("package_name" to FIXTURE)) {}
            assertEquals("foreground_observed", result.getString("confirmation"))
            assertEquals(FIXTURE, result.getString("package_name"))
            val root = PhoneAccessibilityService.current!!.rootInActiveWindow
            try { assertEquals(FIXTURE, root?.packageName?.toString()) } finally { root?.recycle() }
            assertNull(guidance.unconfirmedPackage)
        } finally {
            if (original == null) guidance.clearWarning() else guidance.recordUnconfirmed(original)
        }
    }

    private fun launch(scenario: String, delayMs: Int = 2200) {
        shell("am force-stop $FIXTURE")
        val output = shell("am start -W -n $FIXTURE/.ScenarioActivity --es scenario $scenario --ei delay_ms $delayMs")
        assertFalse(output, output.contains("Error:"))
        if (scenario != "pip") awaitWindowQuiescence(includeContent = scenario != "carousel")
    }

    /** Fixture setup waits for real window publication/IME transitions before taking the tested snapshot. */
    private fun awaitWindowQuiescence(includeContent: Boolean = true) {
        val deadline = SystemClock.elapsedRealtime() + 6000
        var signature = ""
        var since = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() < deadline) {
            val service = PhoneAccessibilityService.current!!
            val root = service.rootInActiveWindow
            val windows = service.windows
            val next = try {
                if (root?.packageName?.toString() != FIXTURE || windows.none { it.id == root.windowId }) "" else
                    "${if (includeContent) service.relevantVersion(root.windowId) else 0}:" + windows.sortedBy { it.id }.joinToString {
                        val region = android.graphics.Region(); it.getRegionInScreen(region)
                        "${it.id}:${it.layer}:${it.isFocused}:${it.isActive}:$region"
                    }
            } finally { root?.recycle(); windows.forEach { it.recycle() } }
            if (next.isEmpty() || next != signature) { signature = next; since = SystemClock.elapsedRealtime() }
            if (next.isNotEmpty() && SystemClock.elapsedRealtime() - since >= 500) return
            SystemClock.sleep(75)
        }
        fail("Fixture windows did not settle: ${accessibilityState()}")
    }

    private fun accessibilityState(): String {
        val service = PhoneAccessibilityService.current ?: return "service=null"
        fun state(): JSONObject {
            val root = service.rootInActiveWindow
            val windows = service.windows
            return try { json("root_window" to root?.windowId, "root_package" to root?.packageName?.toString(),
                "service_instance" to service.instanceId, "flags" to service.serviceInfo.flags,
                "ui_version" to PhoneAccessibilityService.version.get(), "windows" to JSONArray(windows.map { window ->
                    val bounds = android.graphics.Rect(); window.getBoundsInScreen(bounds)
                    json("id" to window.id, "display" to window.displayId, "type" to window.type, "layer" to window.layer,
                        "active" to window.isActive, "focused" to window.isFocused, "bounds" to bounds.toShortString())
                }))
            } finally { root?.recycle(); windows.forEach { it.recycle() } }
        }
        val before = state()
        // Diagnostic only, after failure: compare cache state without approving or retrying an action.
        val cleared = if (android.os.Build.VERSION.SDK_INT >= 33) service.clearCache() else false
        return json("before_cache_clear" to before, "cache_cleared" to cleared, "after_cache_clear" to state()).toString() +
            "\n" + shell("dumpsys accessibility").take(8000)
    }

    private fun counters(): JSONObject {
        val output = shell("content query --uri content://$FIXTURE.counters/state")
        return JSONObject(output.substringAfter("json="))
    }

    private fun awaitCounter(name: String, value: Int) {
        val deadline = SystemClock.elapsedRealtime() + 5000
        while (counters().optInt(name) != value && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertEquals(counters().toString(), value, counters().getInt(name))
    }

    private fun observe(viewId: String? = null, timeout: Int = 6000, mode: String = "tree"): JSONObject {
        val wait = json("package_name" to FIXTURE)
        if (viewId != null) wait.put("element", json("view_id" to "$FIXTURE:id/$viewId", "visible" to true))
        return backend.observe(json("mode" to mode, "include_details" to true, "timeout_ms" to timeout, "diagnostics" to true, "wait_until" to wait), "fixture") {}
    }

    private fun element(observation: JSONObject, viewId: String): String {
        if (observation.optString("observation_status") != "complete") fail(observation.toString() + "\n" + accessibilityState())
        val nodes = observation.getJSONObject("tree").getJSONArray("elements")
        return (0 until nodes.length()).map { nodes.getJSONObject(it) }.single { it.optString("view_id") == "$FIXTURE:id/$viewId" }.getString("element_id")
    }

    private fun action(observation: JSONObject, name: String, viewId: String, extra: JSONObject = JSONObject()): JSONObject {
        val args = json("observation_id" to observation.getString("observation_id"), "element_id" to element(observation, viewId))
        extra.keys().forEach { args.put(it, extra.get(it)) }
        return try { backend.act(name, args, "fixture") {} }
        catch (error: DeviceError) { throw DeviceError(error.code, "${error.message}: ${error.details}", error.details) }
    }

    @Test fun delayedTargetWaitsThenReceivesExactlyOneClick() {
        launch("delayed", 2200)
        val observed = observe("target")
        assertEquals("satisfied", observed.getJSONObject("condition").getString("status"))
        assertTrue(observed.getInt("collection_attempts") > 1)
        val withScreenshot = observe("target", mode = "both")
        assertTrue(withScreenshot.getJSONObject("screenshot").getBoolean("available"))
        assertEquals(0, counters().getInt("clicks"))
        action(withScreenshot, "tap", "target")
        awaitCounter("clicks", 1)
    }

    @Test fun skeletonAndDuplicateTargetsNeverBecomeUniqueReady() {
        for (scenario in listOf("skeleton", "duplicate")) {
            launch(scenario, 100)
            val result = observe("target", 1600)
            assertNotEquals(result.toString(), "satisfied", result.getJSONObject("condition").getString("status"))
            assertTrue(result.getJSONObject("condition").getBoolean("timed_out"))
            assertEquals(0, counters().getInt("clicks"))
        }
    }

    @Test fun staticSemanticScrollReachesOnlyTheListOnce() {
        launch("static")
        val observed = observe("list")
        action(observed, "scroll", "list", json("direction" to "forward"))
        awaitCounter("scroll_requests", 1)
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (counters().getInt("scroll_y") == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue(counters().toString(), counters().getInt("scroll_y") > 0)
        assertEquals("list", counters().getJSONArray("events").getJSONObject(0).getString("target"))
    }

    @Test fun stableAnchoredRowTextRefreshAllowsOneSemanticScroll() {
        launch("text_refresh", 2500)
        val observed = observe("list")
        awaitCounter("mutations", 1)
        SystemClock.sleep(250)
        val result = action(observed, "scroll", "list", json("direction" to "forward"))
        assertEquals("android_accepted", result.getString("confirmation"))
        awaitCounter("scroll_requests", 1)
    }

    @Test fun platformCornerPipAllowsSemanticScrollOnceWithActualMovement() {
        launch("pip")
        // Android may temporarily show its PiP menu after entry; test the stationary corner state.
        SystemClock.sleep(3500)
        awaitWindowQuiescence()
        val observed = observe("list")
        val windows = PhoneAccessibilityService.current!!.windows
        try { assertTrue("Fixture must create a genuine platform PiP window", windows.any { it.isInPictureInPictureMode }) }
        finally { windows.forEach { it.recycle() } }
        action(observed, "scroll", "list", json("direction" to "forward"))
        awaitCounter("scroll_requests", 1)
        val deadline = SystemClock.elapsedRealtime() + 3000
        while (counters().getInt("scroll_y") == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Accepted PiP-covered list must actually move: ${counters()}", counters().getInt("scroll_y") > 0)
    }

    @Test fun targetMovementAndWindowReplacementAreRejected() {
        for (scenario in listOf("move", "modal")) {
            launch(scenario, 2200)
            val observed = observe("list")
            if (scenario == "modal") SystemClock.sleep(2400) else awaitCounter("mutations", 1)
            SystemClock.sleep(250)
            try {
                action(observed, "scroll", "list", json("direction" to "forward"))
                fail("$scenario must reject the old list reference")
            } catch (error: DeviceError) { assertFalse(error.details.toString(), error.details.optBoolean("action_executed", true)) }
            assertEquals("$scenario must not dispatch a semantic action", 0, counters().getInt("scroll_requests"))
        }
    }

    @Test fun changingDescendantsAndGeometricOverlapsDoNotRejectSemanticScroll() {
        for (scenario in listOf("rebind", "reorder", "replace", "unidentified_refresh", "same_window", "descendant_overlay", "popup", "full_popup")) {
            launch(scenario, 2000)
            val observed = observe("list")
            if (scenario in setOf("rebind", "reorder", "replace", "unidentified_refresh")) awaitCounter("mutations", 1)
            action(observed, "scroll", "list", json("direction" to "forward"))
            awaitCounter("scroll_requests", 1)
            val deadline = SystemClock.elapsedRealtime() + 3000
            while (counters().getInt("scroll_y") == 0 && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
            assertTrue("$scenario must actually scroll: ${counters()}", counters().getInt("scroll_y") > 0)
        }
    }

    @Test fun explicitFocusThenFreshEditableObservationReceivesOneTextAction() {
        launch("focus")
        val before = observe("focus_control")
        action(before, "tap", "focus_control")
        awaitCounter("clicks", 1)
        awaitWindowQuiescence()
        val after = backend.observe(json("mode" to "tree", "include_details" to true, "timeout_ms" to 6000, "wait_until" to json("package_name" to FIXTURE,
            "element" to json("view_id" to "$FIXTURE:id/search", "editable" to true, "action" to "set_text"))), "fixture") {}
        try { action(after, "input_text", "search", json("mode" to "replace", "text" to "Verified input")) }
        catch (error: DeviceError) { throw AssertionError("Fresh editor input rejected: ${error.details}", error) }
        awaitCounter("text_requests", 1)
        assertEquals("Verified input", observedText(observe("search")))
        assertEquals("Verified input", counters().getString("text"))
    }

    @Test fun gestureCrossingPopupCanExecuteOnFullDisplay() {
        launch("popup")
        val observed = observe("list")
        val windows = PhoneAccessibilityService.current!!.windows
        val targetWindow = observed.getInt("window_id")
        val area = try {
            val layer = windows.single { it.id == targetWindow }.layer
            android.graphics.Rect().also { rect -> windows.first { it.id != targetWindow && it.layer > layer && it.type == 1 }.getBoundsInScreen(rect) }
        } finally { windows.forEach { it.recycle() } }
        val args = json("observation_id" to observed.getString("observation_id"), "points" to JSONArray(listOf(
            json("x" to (area.left - 20).coerceAtLeast(1), "y" to area.centerY()),
            json("x" to area.centerX(), "y" to area.centerY()))))
        assertEquals("gesture_completed", backend.act("swipe", args, "fixture") {}.getString("confirmation"))
        assertEquals(0, counters().getInt("scroll_requests"))
    }

    private fun observedText(observation: JSONObject): String {
        val nodes = observation.getJSONObject("tree").getJSONArray("elements")
        return (0 until nodes.length()).map { nodes.getJSONObject(it) }
            .single { it.optString("view_id") == "$FIXTURE:id/search" }.optString("text")
    }

    private fun awaitText(text: String): JSONObject {
        val result = backend.observe(json("mode" to "tree", "include_details" to true, "timeout_ms" to 5000,
            "wait_until" to json("package_name" to FIXTURE, "element" to json("view_id" to "$FIXTURE:id/search", "text" to text))), "fixture") {}
        assertEquals(result.toString(), "complete", result.getString("observation_status"))
        assertEquals(text, observedText(result))
        assertEquals(text, counters().getString("text"))
        return result
    }

    @Test fun chineseReplaceInsertAndExplicitAttachedObservationCheckActualText() {
        launch("keyboard")
        val empty = observe("search")
        val first = action(empty, "input_text", "search", json("mode" to "replace", "text" to "中文搜索"))
        assertFalse(first.getBoolean("text_verified"))
        val written = awaitText("中文搜索")
        action(written, "input_text", "search", json("mode" to "insert", "text" to "手机"))
        val inserted = awaitText("中文搜索手机")
        val controller = DeviceController(backend, { SystemClock.elapsedRealtime() }, { true })
        try {
            val args = json("request_id" to "replace-with-observation", "observation_id" to inserted.getString("observation_id"),
                "element_id" to element(inserted, "search"), "mode" to "replace", "text" to "二手手机",
                "observe_after" to true, "observation_options" to json("mode" to "tree", "include_details" to true,
                    "wait_until" to json("element" to json("view_id" to "$FIXTURE:id/search", "text" to "二手手机"))))
            val result = controller.call("fixture", "input_text", args)
            assertTrue(result.toString(), result.getBoolean("action_executed"))
            assertEquals("二手手机", observedText(result.getJSONObject("observation")))
            assertFalse(result.getJSONObject("execution").getBoolean("text_verified"))
            assertEquals(result.toString(), controller.call("fixture", "input_text", args).toString())
            assertEquals("二手手机", counters().getString("text"))
            awaitCounter("text_requests", 3)
        } finally { controller.stop() }
    }

    @Test fun changedSelectionRejectsInsertButAllowsReplaceUsingOriginalTextReference() {
        launch("selection", 3500)
        awaitWindowQuiescence()
        val original = awaitText("原始文本")
        awaitCounter("mutations", 1)
        try {
            action(original, "input_text", "search", json("mode" to "insert", "text" to "错误位置"))
            fail("Insertion must reject an outdated selection")
        } catch (error: DeviceError) { assertEquals("text_selection_changed", error.details.getString("reason")) }
        assertEquals(0, counters().getInt("text_requests"))
        action(original, "input_text", "search", json("mode" to "replace", "text" to "替换成功"))
        awaitText("替换成功")
        awaitCounter("text_requests", 1)
    }

    @Test fun continuousRecommendationsAllowImmediateObservationAndConsecutiveActions() {
        launch("carousel")
        val observation = observe("target", mode = "both")
        assertEquals(observation.toString(), 0L, observation.getLong("wait_ms"))
        assertTrue(observation.getJSONObject("screenshot").getBoolean("available"))
        action(observation, "tap", "target")
        action(observation, "tap", "target")
        val nodes = observation.getJSONObject("tree").getJSONArray("elements")
        val button = (0 until nodes.length()).map { nodes.getJSONObject(it) }.single { it.optString("view_id") == "$FIXTURE:id/target" }
        val bounds = button.getJSONArray("bounds")
        val args = json("observation_id" to observation.getString("observation_id"),
            "x" to (bounds.getInt(0) + bounds.getInt(2)) / 2, "y" to (bounds.getInt(1) + bounds.getInt(3)) / 2)
        backend.act("tap", args, "fixture") {}
        backend.act("tap", args, "fixture") {}
        awaitCounter("clicks", 4)
        assertTrue(counters().getInt("mutations") > 0)
    }

    @Test fun keyboardBackspaceFromFullScreenshotDeletesExactlyOneCharacter() {
        launch("keyboard")
        action(observe("search"), "tap", "search")
        awaitWindowQuiescence()
        action(observe("search"), "input_text", "search", json("mode" to "replace", "text" to "中文测试"))
        awaitText("中文测试")
        val screenshot = backend.observe(json("mode" to "screenshot"), "fixture") {}
        val shot = screenshot.getJSONObject("screenshot")
        assertTrue(screenshot.toString(), shot.getBoolean("available"))
        assertTrue(screenshot.isNull("tree"))
        val windows = PhoneAccessibilityService.current!!.windows
        var key: android.graphics.Rect? = null
        fun find(node: android.view.accessibility.AccessibilityNodeInfo) {
            val label = "${node.text?.toString().orEmpty()} ${node.contentDescription?.toString().orEmpty()}".lowercase()
            if (listOf("delete", "backspace", "删除", "退格").any { label.trim() == it }) {
                key = android.graphics.Rect().also(node::getBoundsInScreen)
            }
            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                try { find(child) } finally { child.recycle() }
            }
        }
        try {
            val ime = windows.singleOrNull { it.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD }
                ?: error("IME absent: ${accessibilityState()}")
            val root = ime.root ?: error("IME root missing")
            try { find(root) } finally { root.recycle() }
        } finally { windows.forEach { it.recycle() } }
        assertNotNull("Test IME must expose the backspace key for deterministic coordinate selection", key)
        val imageX = key!!.centerX() / shot.getDouble("scale_x")
        val imageY = key!!.centerY() / shot.getDouble("scale_y")
        val args = json("observation_id" to screenshot.getString("observation_id"),
            "x" to imageX * shot.getDouble("scale_x") + shot.getInt("offset_x"),
            "y" to imageY * shot.getDouble("scale_y") + shot.getInt("offset_y"))
        assertEquals("gesture_completed", backend.act("tap", args, "fixture") {}.getString("confirmation"))
        awaitText("中文测")
        assertEquals(1, counters().getInt("text_requests"))
        backend.act("back", json(), "fixture") {}
        val hidden = backend.observe(json("mode" to "screenshot"), "fixture") {}
        assertEquals(screenshot.getJSONObject("display").toString(), hidden.getJSONObject("display").toString())
    }

    @Test fun localAuthorizationScreenCannotBeOperatedThroughFullDisplayCoordinates() {
        shell("am start -W -n ai.nidhoggfgg.phoneuse/.MainActivity")
        val observed = backend.observe(json("mode" to "both", "wait_until" to json("package_name" to "ai.nidhoggfgg.phoneuse")), "fixture") {}
        assertFalse(observed.toString(), observed.getJSONObject("tree").getBoolean("available"))
        try {
            backend.act("tap", json("observation_id" to observed.getString("observation_id"), "x" to 100, "y" to 100), "fixture") {}
            fail("Local controls must remain protected")
        } catch (error: DeviceError) { assertEquals("LOCAL_CONTROL_UI", error.code) }
    }

    @Test fun requestedChannelsStayIndependentIncludingProtectedWindows() {
        launch("secure")
        val both = observe("list", mode = "both")
        assertTrue(both.toString(), both.getJSONObject("tree").getBoolean("available"))
        // Default-display capture may succeed with protected pixels redacted, or report a failure.
        assertTrue(both.getJSONObject("screenshot").has("available"))
        val screenshot = backend.observe(json("mode" to "screenshot"), "fixture") {}
        assertTrue(screenshot.isNull("tree"))
        assertTrue(screenshot.getJSONObject("screenshot").has("available"))
        val tree = observe("list")
        assertTrue(tree.isNull("screenshot"))
        assertEquals("decision", tree.getJSONObject("tree").getString("output"))
    }

    @Test fun emptyEditorSupportsInsertAndClearWithIndependentReadback() {
        launch("keyboard")
        action(observe("search"), "input_text", "search", json("mode" to "insert", "text" to "空框插入"))
        val first = awaitText("空框插入")
        action(first, "input_text", "search", json("mode" to "replace", "text" to ""))
        val cleared = observe("search")
        assertEquals("", observedText(cleared))
        assertEquals("", counters().getString("text"))
        action(cleared, "input_text", "search", json("mode" to "insert", "text" to "再次插入"))
        awaitText("再次插入")
        awaitCounter("text_requests", 3)
    }

    @Test fun rotatedDisplayRejectsOldCoordinateReferenceBeforeDispatch() {
        launch("static")
        val originalRotation = shell("settings get system user_rotation")
        val originalAuto = shell("settings get system accelerometer_rotation")
        try {
            val before = backend.observe(json("mode" to "screenshot"), "fixture") {}
            val rotation = before.getJSONObject("display").getInt("rotation")
            shell("settings put system accelerometer_rotation 0")
            shell("settings put system user_rotation ${(rotation / 90 + 1) % 4}")
            val deadline = SystemClock.elapsedRealtime() + 5000
            var after = before
            while (after.getJSONObject("display").getInt("rotation") == rotation && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100)
                after = backend.observe(json("mode" to "tree"), "fixture") {}
            }
            assertNotEquals(rotation, after.getJSONObject("display").getInt("rotation"))
            try {
                backend.act("tap", json("observation_id" to before.getString("observation_id"), "x" to 100, "y" to 100), "fixture") {}
                fail("Rotated coordinates must be rejected")
            } catch (error: DeviceError) { assertEquals("display_changed", error.details.getString("reason")) }
            assertEquals(0, counters().getInt("clicks"))
        } finally {
            if (originalRotation == "null") shell("settings delete system user_rotation") else shell("settings put system user_rotation $originalRotation")
            if (originalAuto == "null") shell("settings delete system accelerometer_rotation") else shell("settings put system accelerometer_rotation $originalAuto")
        }
    }

    @Test fun controllerRetainsExecutionOnTimeoutAndCachesFreshRejectionRecovery() {
        val controller = DeviceController(backend, { SystemClock.elapsedRealtime() }, { true })
        try {
            launch("delayed", 100)
            awaitWindowQuiescence()
            val before = observe("target")
            val once = json("request_id" to "tap-once-with-timeout", "observation_id" to before.getString("observation_id"),
                "element_id" to element(before, "target"), "observe_after" to true,
                "observation_options" to json("mode" to "tree", "timeout_ms" to 1000,
                    "wait_until" to json("package_name" to FIXTURE, "element" to json("text" to "Never appears"))))
            val executed = controller.call("fixture", "tap", once)
            assertEquals(executed.toString(), "executed", executed.getString("state"))
            assertTrue(executed.getBoolean("action_executed"))
            assertNotEquals("complete", executed.getString("observation_status"))
            assertTrue(executed.getJSONObject("observation").getJSONObject("condition").getBoolean("timed_out"))
            val replay = controller.call("fixture", "tap", JSONObject(once.toString()))
            assertEquals(executed.toString(), replay.toString())
            awaitCounter("clicks", 1)

            launch("target_replace", 2200)
            val oldList = observe("target")
            awaitCounter("mutations", 1)
            SystemClock.sleep(250)
            val reject = json("request_id" to "stale-list-with-recovery", "observation_id" to oldList.getString("observation_id"),
                "element_id" to element(oldList, "target"), "observe_on_rejection" to true,
                "observation_options" to json("mode" to "tree", "timeout_ms" to 5000, "wait_until" to json("package_name" to FIXTURE)))
            val rejected = controller.call("fixture", "tap", reject)
            assertEquals(rejected.toString(), "failed", rejected.getString("state"))
            assertFalse(rejected.getBoolean("action_executed"))
            assertTrue(rejected.has("error"))
            val fresh = rejected.getJSONObject("recovery_observation")
            assertEquals(fresh.toString(), "complete", fresh.getString("observation_status"))
            assertNotEquals(oldList.getString("observation_id"), fresh.getString("observation_id"))
            assertEquals(rejected.toString(), controller.call("fixture", "tap", JSONObject(reject.toString())).toString())
            assertEquals(0, counters().getInt("scroll_requests"))
        } finally { controller.stop() }
    }
}
