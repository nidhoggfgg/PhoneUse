package ai.nidhoggfgg.phoneuse.device

import android.app.AppOpsManager
import android.content.Context

internal enum class BackgroundLaunchPermission { ALLOWED, DENIED, UNKNOWN }

/** Best-effort OEM check, independent of whether a particular launch succeeds. */
internal class BackgroundLaunchPermissionChecker(
    private val isXiaomi: Boolean,
    private val readMode: () -> Int?
) {
    constructor(context: Context) : this(AppLaunchGuidance.isXiaomi, {
        val appContext = context.applicationContext
        val appOps = appContext.getSystemService(AppOpsManager::class.java)
        // MIUI/HyperOS extension; neither the operation nor this overload is a public Android API.
        // Keep reflection isolated so unsupported firmware and hidden-API restrictions stay UNKNOWN.
        // Operation reference: Telegram's org/telegram/messenger/XiaomiUtilities.java.
        val checkOp = AppOpsManager::class.java.getMethod(
            "checkOpNoThrow", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, String::class.java
        )
        checkOp.invoke(appOps, OP_BACKGROUND_START_ACTIVITY, appContext.applicationInfo.uid, appContext.packageName) as? Int
    })

    fun read(): BackgroundLaunchPermission {
        if (!isXiaomi) return BackgroundLaunchPermission.UNKNOWN
        return try {
            when (readMode()) {
                AppOpsManager.MODE_ALLOWED -> BackgroundLaunchPermission.ALLOWED
                AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED,
                AppOpsManager.MODE_FOREGROUND -> BackgroundLaunchPermission.DENIED
                // Default/ask/unrecognized modes do not establish the background permission.
                else -> BackgroundLaunchPermission.UNKNOWN
            }
        } catch (_: Exception) {
            BackgroundLaunchPermission.UNKNOWN
        }
    }

    private companion object { const val OP_BACKGROUND_START_ACTIVITY = 10021 }
}
