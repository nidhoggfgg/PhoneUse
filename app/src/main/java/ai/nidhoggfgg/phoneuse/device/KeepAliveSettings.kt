package ai.nidhoggfgg.phoneuse.device

import android.content.Context

/** Independent of the obsolete service_state enabled/paused controls. */
internal class KeepAliveSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("keep_alive", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = preferences.getBoolean("enabled", true)
        set(value) { preferences.edit().putBoolean("enabled", value).apply() }
}
