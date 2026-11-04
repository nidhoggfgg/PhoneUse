package ai.nidhoggfgg.phoneuse

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import ai.nidhoggfgg.phoneuse.server.ConnectionSettings
import java.net.Socket
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ServiceRestorationTest {
    private fun awaitState(check: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!check() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Service did not reach expected state", check())
    }

    @Test fun openingAlwaysStartsEndpointAndIgnoresLegacyStopAndPauseSettings() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = context.getSharedPreferences("service_state", Context.MODE_PRIVATE)
        val enabled = settings.all["enabled"] as? Boolean
        val paused = settings.all["paused"] as? Boolean
        val connections = ConnectionSettings(context)
        val allowedHosts = connections.allowedHosts
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= 33) add("android.permission.POST_NOTIFICATIONS")
            if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
        }
        permissions.forEach { permission ->
            instrumentation.uiAutomation.executeShellCommand("pm grant ${context.packageName} $permission").use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
        try {
            connections.allowedHosts = emptyList()
            context.stopService(Intent(context, PhoneUseService::class.java))
            awaitState { PhoneUseService.current == null }
            assertTrue(settings.edit().remove("enabled").remove("paused").commit())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertEndpointAvailable()
                val service = PhoneUseService.current!!
                scenario.recreate()
                assertEndpointAvailable()
                assertSame(service, PhoneUseService.current)
                // Returning to an existing activity also restores a service destroyed by Android.
                scenario.moveToState(Lifecycle.State.CREATED)
                context.stopService(Intent(context, PhoneUseService::class.java))
                awaitState { PhoneUseService.current == null }
                scenario.moveToState(Lifecycle.State.RESUMED)
                assertEndpointAvailable()
                assertNotSame(service, PhoneUseService.current)
            }
            context.stopService(Intent(context, PhoneUseService::class.java))
            awaitState { PhoneUseService.current == null }
            assertTrue(settings.edit().putBoolean("enabled", false).putBoolean("paused", true).commit())
            ActivityScenario.launch(MainActivity::class.java).use {
                assertEndpointAvailable()
                // Pending notification intents from older installations no longer change service state.
                for (action in listOf("phoneuse.PAUSE", "phoneuse.RESUME", "phoneuse.STOP")) {
                    context.startService(Intent(context, PhoneUseService::class.java).setAction(action))
                    instrumentation.waitForIdleSync()
                    assertEndpointAvailable()
                }
            }
        } finally {
            context.stopService(Intent(context, PhoneUseService::class.java))
            awaitState { PhoneUseService.current == null }
            settings.edit().apply {
                if (enabled == null) remove("enabled") else putBoolean("enabled", enabled)
                if (paused == null) remove("paused") else putBoolean("paused", paused)
            }.commit()
            connections.allowedHosts = allowedHosts
        }
    }

    private fun assertEndpointAvailable() {
        awaitState { PhoneUseService.current != null }
        val service = PhoneUseService.current!!
        assertFalse(service.controller.status("test").getBoolean("paused"))
        assertFalse(service.controller.status("test").getBoolean("stopped"))
        Socket("127.0.0.1", 8443).use { socket ->
            socket.soTimeout = 5000
            socket.outputStream.write("GET /identity HTTP/1.1\r\nHost: 127.0.0.1:8443\r\n\r\n".toByteArray())
            assertEquals("200", socket.inputStream.bufferedReader().readLine().split(' ')[1])
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(NotificationManager::class.java)
        awaitState { manager.activeNotifications.any { it.id == 1 } }
        val notification = manager.activeNotifications.single { it.id == 1 }.notification
        assertTrue(notification.actions.isNullOrEmpty())
        assertNotNull(notification.contentIntent)
    }
}
