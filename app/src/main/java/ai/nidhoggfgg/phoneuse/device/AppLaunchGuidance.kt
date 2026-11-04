package ai.nidhoggfgg.phoneuse.device

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings

/** OEM background-launch permissions have no portable Android runtime-permission API. */
internal class AppLaunchGuidance(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences("app_launch_guidance", Context.MODE_PRIVATE)
    val unconfirmedPackage: String? get() = preferences.getString("unconfirmed_package", null)

    fun recordUnconfirmed(packageName: String) {
        preferences.edit().putString("unconfirmed_package", packageName).apply()
        AppLaunchNotifications.show(appContext, packageName)
    }
    fun clearWarning() {
        preferences.edit().remove("unconfirmed_package").apply()
        AppLaunchNotifications.clear(appContext)
    }

    companion object {
        val isXiaomi: Boolean get() = Build.MANUFACTURER.equals("xiaomi", true) ||
            Build.BRAND.lowercase(java.util.Locale.ROOT) in setOf("xiaomi", "redmi", "poco")

        fun permissionHint(): String = if (isXiaomi)
            "On this Xiaomi/Redmi/POCO phone, open Phone Use > Background app launches > Open settings and allow background pop-up windows in Other permissions. Also check for an app-opening confirmation on the phone. A slow launch or another dialog can also prevent confirmation."
        else "Check the phone for an app-opening confirmation or background launch restrictions in Phone Use's system app settings. A slow launch or another dialog can also prevent confirmation."

        fun openSettings(context: Context): Boolean {
            val intents = buildList {
                if (isXiaomi) add(Intent("miui.intent.action.APP_PERM_EDITOR")
                    .setPackage("com.miui.securitycenter").putExtra("extra_pkgname", context.packageName))
                add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
            }
            for (intent in intents) {
                try {
                    context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    return true
                } catch (_: ActivityNotFoundException) {
                    // OEM settings routes differ; fall back to Android's app details page.
                } catch (_: SecurityException) {
                    // Some firmware versions do not export the OEM permissions page.
                }
            }
            return false
        }
    }
}
