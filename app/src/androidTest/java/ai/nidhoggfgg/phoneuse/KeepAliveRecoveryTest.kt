package ai.nidhoggfgg.phoneuse

import android.app.Service
import android.app.ActivityManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import ai.nidhoggfgg.phoneuse.device.KeepAliveGuidance
import ai.nidhoggfgg.phoneuse.device.KeepAliveReceiver
import ai.nidhoggfgg.phoneuse.device.KeepAliveSettings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeepAliveRecoveryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun recoveryEventsRespectOptOutAndRestrictedStartsDoNotCrash() = withSavedPreference {
        val starts = mutableListOf<Intent>()
        val recording = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName {
                starts.add(service)
                return service.component!!
            }
        }
        val settings = KeepAliveSettings(context)
        val receiver = KeepAliveReceiver()
        val events = listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
        settings.enabled = false
        events.forEach { receiver.onReceive(recording, Intent(it)) }
        PhoneUseService.restoreIfEnabled(recording) // accessibility reconnection uses the same gate
        assertTrue(starts.isEmpty())
        settings.enabled = true
        receiver.onReceive(recording, Intent(Intent.ACTION_LOCKED_BOOT_COMPLETED))
        receiver.onReceive(recording, Intent("unknown"))
        assertTrue(starts.isEmpty())
        events.forEach { receiver.onReceive(recording, Intent(it)) }
        assertEquals(2, starts.size)
        assertTrue(starts.all { it.component == ComponentName(context, PhoneUseService::class.java) })

        val restricted = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName? {
                throw IllegalStateException("Background start denied")
            }
        }
        receiver.onReceive(restricted, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals(R.string.http_start_failed, PhoneUseService.lastError?.messageRes)
    }

    @Test fun disabledSettingRejectsQueuedRecoveryWithoutInterruptingCurrentSession() = withSavedPreference {
        val starts = mutableListOf<Intent>()
        val recording = object : ContextWrapper(context) {
            override fun startForegroundService(service: Intent): ComponentName {
                starts.add(service)
                return service.component!!
            }
        }
        val settings = KeepAliveSettings(context)
        settings.enabled = true
        PhoneUseService.restoreIfEnabled(recording)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            awaitService { it != null }
            val service = PhoneUseService.current!!
            scenario.onActivity {
                assertEquals(Service.START_STICKY, service.onStartCommand(Intent(), 0, 1))
                settings.enabled = false
                assertEquals(Service.START_NOT_STICKY, service.onStartCommand(Intent(), 0, 2))
                assertEquals(Service.START_NOT_STICKY, service.onStartCommand(starts.single(), 0, 3))
            }
            assertSame(service, PhoneUseService.current)
            context.stopService(Intent(context, PhoneUseService::class.java))
            awaitService { it == null }
            // Simulate delivery of a recovery queued before opt-out to a fresh service instance.
            scenario.onActivity { it.startForegroundService(starts.single()) }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            @Suppress("DEPRECATION")
            fun registered() = context.getSystemService(ActivityManager::class.java).getRunningServices(100)
                .any { it.service == ComponentName(context, PhoneUseService::class.java) }
            while (registered() && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(25)
            }
            assertFalse("Disabled recovery left a service running", registered())
            assertNull(PhoneUseService.current)
        }
    }

    @Test fun settingsIntentsFallBackAndNeverClaimUnavailableRoutesSucceeded() {
        val attempts = mutableListOf<Intent>()
        val recording = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) {
                attempts.add(intent)
                if (intent.action != Settings.ACTION_APPLICATION_DETAILS_SETTINGS) throw SecurityException("Not exported")
            }
        }
        val guidance = KeepAliveGuidance(recording)
        assertTrue(guidance.openAutoStartSettings())
        assertEquals("package:${context.packageName}", attempts.last().dataString)
        attempts.clear()
        assertTrue(guidance.openBatterySettings())
        assertTrue(attempts.any { it.action == Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS })
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempts.last().action)
        attempts.clear()
        assertTrue(guidance.openNotificationSettings())
        assertEquals(context.packageName, attempts.first().getStringExtra(Settings.EXTRA_APP_PACKAGE))
        val missing = KeepAliveGuidance(object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        })
        assertFalse(missing.openAutoStartSettings())
        assertFalse(missing.openBatterySettings())
        assertFalse(missing.openNotificationSettings())
    }

    private fun awaitService(check: (PhoneUseService?) -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (!check(PhoneUseService.current) && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(25)
        assertTrue("Service did not reach expected state", check(PhoneUseService.current))
    }

    private fun withSavedPreference(test: () -> Unit) {
        val preferences = context.getSharedPreferences("keep_alive", Context.MODE_PRIVATE)
        val original = preferences.all["enabled"] as? Boolean
        try { test() } finally {
            context.stopService(Intent(context, PhoneUseService::class.java))
            awaitService { it == null }
            preferences.edit().apply {
                if (original == null) remove("enabled") else putBoolean("enabled", original)
            }.commit()
        }
    }
}
