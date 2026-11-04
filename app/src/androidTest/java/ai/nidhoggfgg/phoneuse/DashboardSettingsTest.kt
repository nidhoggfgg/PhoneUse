package ai.nidhoggfgg.phoneuse

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import ai.nidhoggfgg.phoneuse.server.DeviceIdentity
import ai.nidhoggfgg.phoneuse.device.AppLaunchGuidance
import ai.nidhoggfgg.phoneuse.device.AppLaunchNotifications
import ai.nidhoggfgg.phoneuse.device.BackgroundLaunchPermission
import ai.nidhoggfgg.phoneuse.device.BackgroundLaunchPermissionChecker
import ai.nidhoggfgg.phoneuse.device.KeepAliveSettings
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.app.LocaleManagerCompat
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class DashboardSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val identityPreferences = context.getSharedPreferences("device_identity", Context.MODE_PRIVATE)
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var originalLocales: LocaleListCompat
    private var originalName: String? = null
    private val connections = ai.nidhoggfgg.phoneuse.server.ConnectionSettings(context)
    private var originalHttp = false
    private var originalAllowedHosts = emptyList<String>()
    private var originalUnconfirmedPackage: String? = null

    @Before fun launch() {
        originalLocales = LocaleManagerCompat.getApplicationLocales(context)
        originalName = identityPreferences.getString("name", null)
        originalHttp = connections.allowInsecureHttp
        originalAllowedHosts = connections.allowedHosts
        originalUnconfirmedPackage = AppLaunchGuidance(context).unconfirmedPackage
        AppLaunchGuidance(context).clearWarning()
        connections.allowInsecureHttp = false
        connections.allowedHosts = emptyList()
        context.stopService(Intent(context, PhoneUseService::class.java))
        compose.waitUntil(10_000) { PhoneUseService.current == null }
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        permissions.forEach {
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $it").use { descriptor ->
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).readBytes()
            }
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
        waitForText("This device")
        compose.waitUntil(10_000) { PhoneUseService.current != null }
        waitForText("Connection and pairing")
    }

    @After fun restore() {
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(originalLocales) }
        instrumentation.waitForIdleSync()
        scenario.close()
        context.stopService(Intent(context, PhoneUseService::class.java))
        compose.waitUntil(10_000) { PhoneUseService.current == null }
        identityPreferences.edit().apply {
            if (originalName == null) remove("name") else putString("name", originalName)
        }.commit()
        connections.allowInsecureHttp = originalHttp
        connections.allowedHosts = originalAllowedHosts
        originalUnconfirmedPackage?.let { AppLaunchGuidance(context).recordUnconfirmed(it) }
    }

    @Test fun dashboardShowsRunningServiceWithoutStartPauseOrStopButtons() {
        for (label in listOf("Start service", "Pause control", "Resume control", "Stop service")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        compose.onNodeWithText("Connection and pairing").performScrollTo().assertIsDisplayed()
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh")) }
        waitForText("本机")
        for (label in listOf("启动服务", "暂停控制", "恢复控制", "停止服务")) {
            compose.onNodeWithText(label).assertDoesNotExist()
        }
        assertNotNull(PhoneUseService.current)
    }

    @Test fun aiSetupPromptUsesSelectedAddressAndCopiesTheDisplayedInstructions() {
        connections.allowInsecureHttp = true
        connections.allowedHosts = listOf("phone.example", "2001:db8::1")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("https://phone.example:8443", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("ai-address").performScrollTo().performClick()
        compose.onNode(hasText("2001:db8::1") and hasClickAction()).performClick()
        val hash = context.assets.open("phone-use.zip").use {
            ai.nidhoggfgg.phoneuse.server.PairingCrypto.hash(it.readBytes())
        }
        fun assertPromptAndCopy(origin: String) {
            val expected = "Read this link, install the Phone Use Skill and connect to my phone. " +
                "Show me the pairing code and wait for my confirmation before continuing.\n\n$origin/connect#sha256=$hash"
            compose.onNodeWithTag("ai-prompt").assertTextEquals(expected)
            compose.onNodeWithContentDescription("Copy prompt").performScrollTo().performClick()
            compose.onNodeWithContentDescription("Copied").assertIsDisplayed()
            instrumentation.runOnMainSync {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                assertEquals(expected, clipboard.primaryClip!!.getItemAt(0).text.toString())
            }
        }
        assertPromptAndCopy("https://[2001:db8::1]:8443")
        scenario.recreate()
        waitForText("Connect your AI")
        compose.onNodeWithTag("ai-address").assertTextContains("2001:db8::1")
        // A removed address must not remain in a prompt the user is about to copy.
        connections.allowedHosts = listOf("192.168.1.20")
        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("192.168.1.20").fetchSemanticsNodes().isNotEmpty()
        }
        assertPromptAndCopy("https://192.168.1.20:8443")
        compose.onNodeWithTag("ai-prompt").performScrollTo()
        screenshot("ai-setup-en")
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh")) }
        waitForText("连接 AI")
        compose.onNodeWithContentDescription("复制提示词").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("ai-prompt").performScrollTo()
        screenshot("ai-setup-zh")
    }

    @Test fun deviceNameEditsAreExplicitValidatedAndPersistWithoutChangingIdentity() {
        val identity = DeviceIdentity(context)
        val originalId = identity.id
        val name = identity.name
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithText("Device ID: $originalId").assertDoesNotExist()
        screenshot("dashboard-en")
        compose.onNodeWithText("Edit").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement(" ")
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText("Device name must contain 1–80 printable characters.").assertIsDisplayed()
        assertEquals(name, identity.name)
        compose.onNode(hasSetTextAction()).performTextReplacement("x".repeat(81))
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText("Device name must contain 1–80 printable characters.").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText(name).assertIsDisplayed()
        compose.onNodeWithText("Edit").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("  工作手机  ")
        scenario.recreate()
        compose.onNode(hasSetTextAction()).assertTextContains("  工作手机  ")
        compose.onNodeWithText("Save name").performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        scenario.recreate()
        compose.onNodeWithText("工作手机").assertIsDisplayed()
        assertEquals(originalId, DeviceIdentity(context).id)
        assertEquals("工作手机", DeviceIdentity(context).name)
        compose.onNodeWithText("Details").performClick()
        compose.onNodeWithText("Device ID: $originalId").assertIsDisplayed()
    }

    @Test fun unconfirmedLaunchReminderSurvivesRecreationAndCanBeDismissedLocally() {
        val guidance = AppLaunchGuidance(context)
        val original = guidance.unconfirmedPackage
        try {
            compose.waitUntil(10_000) { PhoneUseService.current != null }
            guidance.recordUnconfirmed("com.example.target")
            compose.waitUntil(5_000) {
                compose.onAllNodesWithText("com.example.target", substring = true).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("Last launch was not confirmed:", substring = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Open settings").performScrollTo().assertIsDisplayed()
            screenshot("launch-reminder-en")
            compose.waitUntil(5_000) { PhoneUseService.current?.stateMessage() == R.string.launch_unconfirmed_notification }
            compose.waitUntil(5_000) {
                context.getSystemService(NotificationManager::class.java).activeNotifications.any {
                    it.notification.extras.getString(android.app.Notification.EXTRA_TEXT) ==
                        context.getString(R.string.launch_unconfirmed_notification)
                }
            }
            scenario.recreate()
            compose.onNodeWithText("Last launch was not confirmed:", substring = true).performScrollTo().assertIsDisplayed()
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh")) }
            waitForText("后台启动应用")
            compose.onNodeWithText("上次未确认打开：", substring = true).performScrollTo().assertIsDisplayed()
            screenshot("launch-reminder-zh")
            compose.onNodeWithText("关闭提醒").performScrollTo().performClick()
            compose.waitUntil(5_000) { guidance.unconfirmedPackage == null }
            compose.waitUntil(5_000) {
                context.getSystemService(NotificationManager::class.java).activeNotifications.none { it.id == AppLaunchNotifications.ID }
            }
            compose.onNodeWithText("上次未确认打开：", substring = true).assertDoesNotExist()
            compose.waitUntil(5_000) { PhoneUseService.current?.stateMessage() != R.string.launch_unconfirmed_notification }
        } finally {
            if (original == null) guidance.clearWarning() else guidance.recordUnconfirmed(original)
        }
    }

    @Test fun backgroundLaunchFailurePostsAnIndependentNotificationAndCoalescesRetries() {
        val guidance = AppLaunchGuidance(context)
        val original = guidance.unconfirmedPackage
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            guidance.clearWarning()
            // No control service or visible activity is needed to deliver the reminder.
            scenario.moveToState(Lifecycle.State.CREATED)
            context.stopService(Intent(context, PhoneUseService::class.java))
            compose.waitUntil(10_000) { PhoneUseService.current == null }
            guidance.recordUnconfirmed("com.example.first")
            compose.waitUntil(5_000) { manager.activeNotifications.any { it.id == AppLaunchNotifications.ID } }
            val first = manager.activeNotifications.single { it.id == AppLaunchNotifications.ID }.notification
            assertEquals(AppLaunchNotifications.CHANNEL, first.channelId)
            assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(first.channelId).importance)
            assertNotNull(first.contentIntent)
            assertTrue(first.flags and android.app.Notification.FLAG_AUTO_CANCEL != 0)
            assertTrue(first.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0)
            assertEquals(0, first.flags and android.app.Notification.FLAG_ONGOING_EVENT)
            assertEquals(context.getString(R.string.launch_notification_detail, "com.example.first"),
                first.extras.getString(android.app.Notification.EXTRA_TEXT))

            guidance.recordUnconfirmed("com.example.second")
            compose.waitUntil(5_000) {
                manager.activeNotifications.singleOrNull { it.id == AppLaunchNotifications.ID }?.notification
                    ?.extras?.getString(android.app.Notification.EXTRA_TEXT)
                    ?.contains("com.example.second") == true
            }
            assertEquals(1, manager.activeNotifications.count { it.notification.channelId == AppLaunchNotifications.CHANNEL })
            // A confirmed launch uses this same clear path; the service notification is unaffected.
            guidance.clearWarning()
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == AppLaunchNotifications.ID } }
        } finally {
            scenario.moveToState(Lifecycle.State.RESUMED)
            if (original == null) guidance.clearWarning() else guidance.recordUnconfirmed(original)
        }
    }

    @Test fun launchNotificationUsesChineseAlongsideRunningService() {
        val guidance = AppLaunchGuidance(context)
        val original = guidance.unconfirmedPackage
        val manager = context.getSystemService(NotificationManager::class.java)
        try {
            compose.waitUntil(10_000) { PhoneUseService.current != null }
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh")) }
            waitForText("本机")
            guidance.recordUnconfirmed("com.example.target")
            compose.waitUntil(5_000) {
                manager.activeNotifications.any {
                    it.id == AppLaunchNotifications.ID && it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) ==
                        "Phone Use：请检查应用启动"
                }
            }
            assertEquals(R.string.launch_unconfirmed_notification, PhoneUseService.current!!.stateMessage())
            guidance.clearWarning()
            compose.waitUntil(5_000) { manager.activeNotifications.none { it.id == AppLaunchNotifications.ID } }
            compose.waitUntil(15_000) {
                manager.activeNotifications.any { it.notification.channelId == PhoneUseService.CHANNEL }
            }
        } finally {
            if (original == null) guidance.clearWarning() else guidance.recordUnconfirmed(original)
        }
    }

    @Test fun backgroundLaunchSettingsRemainAvailableWithoutADashboardReminder() {
        val guidance = AppLaunchGuidance(context)
        val original = guidance.unconfirmedPackage
        try {
            guidance.clearWarning()
            scenario.recreate()
            if (BackgroundLaunchPermissionChecker(context).read() != BackgroundLaunchPermission.DENIED) {
                compose.onNodeWithText("Background app launches").assertDoesNotExist()
            }
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Background app launches").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Open settings").performScrollTo().assertIsDisplayed()
            screenshot("background-launch-settings-en")
        } finally {
            if (original == null) guidance.clearWarning() else guidance.recordUnconfirmed(original)
        }
    }

    @Test fun languageSwitchKeepsSettingsOpenAndPreservesRunningServiceAndPairing() {
        compose.waitUntil(10_000) { PhoneUseService.current != null }
        val service = PhoneUseService.current!!
        val pairing = service.identities.pairing.create("Settings test client", "localhost")
        var clientId: String? = null
        try {
            waitForText("Settings test client")
            compose.onNodeWithText("Approve").performScrollTo().performClick()
            val credential = service.identities.pairing.poll(pairing.getString("pairing_id"), pairing.getString("pairing_secret"))
            clientId = credential.getString("client_id")
            compose.onNodeWithContentDescription("Settings").performClick()
            screenshot("settings-en")
            compose.onNodeWithText("中文").performScrollTo().performClick()
            waitForText("语言")
            compose.onNodeWithText("中文").assertIsSelected()
            screenshot("settings-zh")
            assertSame(service, PhoneUseService.current)
            assertFalse(service.controller.status(clientId!!).getBoolean("paused"))
            assertEquals(clientId, service.identities.authenticate(credential.getString("token")))
            compose.waitUntil(10_000) {
                context.getSystemService(NotificationManager::class.java).activeNotifications.any { notification ->
                    notification.id == 1 && notification.notification.extras.getString(android.app.Notification.EXTRA_TEXT) ==
                        "服务可用，等待连接"
                }
            }
            scenario.recreate()
            waitForText("语言")
            compose.onNodeWithText("中文").assertIsSelected()
            compose.onNodeWithContentDescription("返回").performClick()
            waitForText("本机")
            screenshot("dashboard-zh-running")
            scenario.close()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            waitForText("本机")
            assertSame(service, PhoneUseService.current)
            compose.onNodeWithContentDescription("设置").performClick()
            compose.onNodeWithText("English").performScrollTo().performClick()
            waitForText("Language")
            compose.onNodeWithText("English").assertIsSelected()
            // The theme and language groups both contain this label; choose the language row.
            compose.onAllNodesWithText("Follow system")[1].performScrollTo().performClick()
            compose.waitUntil(5_000) { AppCompatDelegate.getApplicationLocales().isEmpty }
            // Both the toolbar and system Back return from the settings page to the dashboard.
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onAllNodes(isSelectable()).assertCountEquals(0)
        } finally {
            clientId?.let { service.revokeClient(it) }
            service.identities.pairing.clear()
        }
    }

    @Test fun keepAliveDefaultsOnPersistsAndStopsOnlyWhenTaskIsRemoved() {
        val preferences = context.getSharedPreferences("keep_alive", Context.MODE_PRIVATE)
        val original = preferences.all["enabled"] as? Boolean
        try {
            preferences.edit().remove("enabled").commit()
            assertTrue(KeepAliveSettings(context).enabled)
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithText("Background keep-alive").assertIsOn()
            compose.onNodeWithText("Battery optimization").assertIsDisplayed()
            compose.onNodeWithText("Autostart settings").assertIsDisplayed()
            screenshot("keep-alive-en")
            val running = PhoneUseService.current!!
            compose.onNodeWithText("Background keep-alive").performClick().assertIsOff()
            assertFalse(KeepAliveSettings(context).enabled)
            compose.onNodeWithText("Autostart settings").assertDoesNotExist()
            scenario.recreate()
            compose.onNodeWithText("Background keep-alive").assertIsOff()
            scenario.moveToState(Lifecycle.State.CREATED)
            assertSame(running, PhoneUseService.current)
            instrumentation.runOnMainSync { running.onTaskRemoved(Intent(context, MainActivity::class.java)) }
            compose.waitUntil(10_000) { PhoneUseService.current == null }
            // Reopening explicitly starts a session even when background recovery is disabled.
            scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(10_000) { PhoneUseService.current != null }
            compose.onNodeWithText("Background keep-alive").assertIsOff().performClick().assertIsOn()
            val restored = PhoneUseService.current!!
            scenario.moveToState(Lifecycle.State.CREATED)
            instrumentation.runOnMainSync { restored.onTaskRemoved(Intent(context, MainActivity::class.java)) }
            assertSame(restored, PhoneUseService.current)
            scenario.moveToState(Lifecycle.State.RESUMED)
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh")) }
            waitForText("后台保活")
            compose.onNodeWithText("后台保活").performScrollTo().assertIsOn()
            screenshot("keep-alive-zh")
        } finally {
            preferences.edit().apply {
                if (original == null) remove("enabled") else putBoolean("enabled", original)
            }.commit()
        }
    }


    @Test fun insecureHttpAndAllowlistPersistAndUpdateTheRunningService() {
        val remote = java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }.filterIsInstance<java.net.Inet4Address>()
            .first { !it.isLoopbackAddress }.hostAddress!!
        fun status(address: String, host: String = address): Int = java.net.Socket(address, 8443).use { socket ->
            socket.soTimeout = 5000
            socket.outputStream.write("GET /identity HTTP/1.1\r\nHost: $host:8443\r\n\r\n".toByteArray())
            socket.inputStream.bufferedReader().readLine().split(' ')[1].toInt()
        }
        compose.waitUntil(10_000) { PhoneUseService.current != null }
        val running = PhoneUseService.current
        assertEquals(200, status("127.0.0.1"))
        assertEquals(403, status(remote))
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Allow insecure HTTP connections").performScrollTo().assertIsOff().performClick()
        assertTrue(connections.allowInsecureHttp)
        assertEquals(200, status(remote))
        assertSame(running, PhoneUseService.current)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement(" Phone.Example \n$remote\n127.0.0.1\nphone.example")
        compose.onNodeWithText("Save allowlist").performScrollTo().performClick()
        assertEquals(listOf("phone.example", remote, "127.0.0.1"), connections.allowedHosts)
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("phone.example\nhttps://invalid.example")
        compose.onNodeWithText("Save allowlist").performScrollTo().performClick()
        waitForText(context.getString(R.string.access_allowlist_invalid))
        compose.onNodeWithText(context.getString(R.string.access_allowlist_invalid)).performScrollTo().assertIsDisplayed()
        screenshot("allowlist-invalid")
        assertEquals(listOf("phone.example", remote, "127.0.0.1"), connections.allowedHosts)
        compose.onNode(hasSetTextAction()).performTextReplacement("phone.example\n$remote\n127.0.0.1")
        compose.onNodeWithText("Save allowlist").performScrollTo().performClick()
        assertEquals(200, status(remote, "phone.example"))
        screenshot("http-settings-en")
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("zh")) }
        waitForText("允许不安全的 HTTP 连接")
        compose.onNodeWithText("保存白名单").performScrollTo()
        screenshot("http-settings-zh")
        instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
        waitForText("Allow insecure HTTP connections")
        scenario.recreate()
        compose.onNodeWithText("Allow insecure HTTP connections").performScrollTo().assertIsOn()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("connection-addresses").performScrollTo()
            .assertTextContains("https://$remote:8443", substring = true)
            .assertTextContains("http://$remote:8443", substring = true)
            .assertTextContains("https://phone.example:8443", substring = true)
            .assertTextContains("http://phone.example:8443", substring = true)
            .assertTextContains("https://127.0.0.1:8443", substring = true)
        screenshot("http-addresses-en")
        context.stopService(Intent(context, PhoneUseService::class.java))
        compose.waitUntil(10_000) { PhoneUseService.current == null }
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(10_000) { PhoneUseService.current != null }
        assertEquals(200, status(remote, "phone.example"))
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Allow insecure HTTP connections").performScrollTo().assertIsOn().performClick()
        assertEquals(403, status(remote))
        assertEquals(200, status("127.0.0.1"))
        scenario.recreate()
        compose.onNodeWithText("Allow insecure HTTP connections").performScrollTo().assertIsOff()
        compose.onNodeWithContentDescription("Back").performClick()
        val addresses = compose.onNodeWithTag("connection-addresses").performScrollTo()
        assertFalse(addresses.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Text].joinToString().contains("http://"))

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("phone.example")
        compose.onNodeWithText("Save allowlist").performScrollTo().performClick()
        assertEquals(403, status("127.0.0.1"))
        assertEquals(403, status("127.0.0.1", "phone.example"))
        compose.onNodeWithText("Allow insecure HTTP connections").performScrollTo().performClick()
        assertEquals(200, status("127.0.0.1", "phone.example"))
        assertEquals(403, status("127.0.0.1"))
        compose.onNodeWithText("Allow insecure HTTP connections").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Open web console").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("https://$remote:8443", substring = true).assertDoesNotExist()
        compose.onNodeWithText(context.getString(R.string.local_console_blocked)).assertIsDisplayed()
        screenshot("allowlist-console-blocked")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("")
        compose.onNodeWithText("Save allowlist").performScrollTo().performClick()
        assertTrue(connections.allowedHosts.isEmpty())
        assertEquals(200, status("127.0.0.1"))
        assertEquals(403, status("127.0.0.1", "phone.example"))
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Open web console").performScrollTo().assertIsEnabled()
    }

    @Test fun legacyPublicAddressMigratesOnceAndClearingRestoresTheDefault() {
        connections.preferences.edit().remove(ai.nidhoggfgg.phoneuse.server.ConnectionSettings.ALLOWED_HOSTS)
            .putString("public_host", "phone.example").commit()
        val migrated = ai.nidhoggfgg.phoneuse.server.ConnectionSettings(context)
        assertEquals(listOf("phone.example"), migrated.allowedHosts)
        assertFalse(migrated.preferences.contains("public_host"))
        migrated.allowedHosts = emptyList()
        assertTrue(ai.nidhoggfgg.phoneuse.server.ConnectionSettings(context).allowedHosts.isEmpty())
        assertThrows(Exception::class.java) { migrated.allowedHosts = listOf("phone.example", "https://invalid.example") }
        assertTrue(migrated.allowedHosts.isEmpty())
    }

    private fun waitForText(text: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun screenshot(name: String) {
        // Only the instrumentation window temporarily permits screenshots; production stays secure.
        scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        try {
            compose.waitForIdle()
            val image = compose.onRoot().captureToImage().asAndroidBitmap()
            val directory = File(context.getExternalFilesDir(null), "ui-review").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        }
    }
}
