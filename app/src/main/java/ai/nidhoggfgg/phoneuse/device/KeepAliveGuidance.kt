package ai.nidhoggfgg.phoneuse.device

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import ai.nidhoggfgg.phoneuse.PhoneUseService
import ai.nidhoggfgg.phoneuse.R
import java.util.Locale

internal class KeepAliveGuidance(private val context: Context) {
    val batteryExempt: Boolean
        get() = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    val notificationsEnabled: Boolean
        get() {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() &&
                manager.getNotificationChannel(PhoneUseService.CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }

    fun openBatterySettings(): Boolean = openFirst(buildList {
        // Local task automation requires a persistent incoming connection; FCM cannot replace it.
        // Only show Android's approval dialog in response to the user's button press.
        if (!batteryExempt) add(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri))
        add(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        add(appDetails())
    })

    fun openNotificationSettings(): Boolean = openFirst(listOf(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, PhoneUseService.CHANNEL),
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        appDetails()
    ))

    fun openAutoStartSettings(): Boolean = openFirst(autoStartComponents.map {
        Intent().setComponent(ComponentName.unflattenFromString(it))
            .putExtra("packageName", context.packageName).putExtra("extra_pkgname", context.packageName)
    } + appDetails())

    private val packageUri get() = Uri.parse("package:${context.packageName}")
    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri)

    private fun openFirst(intents: List<Intent>): Boolean {
        for (intent in intents) {
            try {
                // Try the route directly: package visibility queries cannot reliably resolve OEM pages.
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
                // Firmware renamed or removed this page.
            } catch (_: SecurityException) {
                // Firmware does not export this page.
            }
        }
        return false
    }

    private val vendor: String get() {
        val names = listOf(Build.MANUFACTURER, Build.BRAND).map { it.lowercase(Locale.ROOT) }
        return when {
            names.any { it in setOf("xiaomi", "redmi", "poco") } -> "xiaomi"
            names.any { it in setOf("huawei", "honor") } -> "huawei"
            names.any { it in setOf("oppo", "realme", "oneplus") } -> "oppo"
            names.any { it in setOf("vivo", "iqoo") } -> "vivo"
            "samsung" in names -> "samsung"
            else -> "other"
        }
    }

    val vendorHelp: Int get() = when (vendor) {
        "xiaomi" -> R.string.keep_alive_xiaomi_help
        "huawei" -> R.string.keep_alive_huawei_help
        "oppo", "vivo" -> R.string.keep_alive_autostart_help
        "samsung" -> R.string.keep_alive_samsung_help
        else -> R.string.keep_alive_generic_help
    }

    // Best-effort OEM entry points, also used by AutoStarter:
    // https://github.com/judemanutd/AutoStarter
    // Opening a page never establishes that the user granted autostart permission.
    private val autoStartComponents: List<String> get() = when (vendor) {
        "xiaomi" -> listOf("com.miui.securitycenter/com.miui.permcenter.autostart.AutoStartManagementActivity")
        "huawei" -> listOf(
            "com.huawei.systemmanager/com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
            "com.huawei.systemmanager/com.huawei.systemmanager.optimize.process.ProtectActivity")
        "oppo" -> listOf(
            "com.coloros.safecenter/com.coloros.safecenter.startupapp.StartupAppListActivity",
            "com.coloros.safecenter/com.coloros.safecenter.permission.startup.StartupAppListActivity",
            "com.oppo.safe/com.oppo.safe.permission.startup.StartupAppListActivity",
            "com.oneplus.security/com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
        "vivo" -> listOf(
            "com.vivo.permissionmanager/com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
            "com.iqoo.secure/com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
        else -> emptyList()
    }
}
