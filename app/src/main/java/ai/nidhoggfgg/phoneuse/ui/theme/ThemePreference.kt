package ai.nidhoggfgg.phoneuse.ui.theme

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

internal object ThemePreference {
    private const val PREFERENCES = "appearance"
    private const val KEY_NIGHT_MODE = "night_mode"

    fun read(context: Context): Int {
        val mode = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getInt(KEY_NIGHT_MODE, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        return when (mode) {
            AppCompatDelegate.MODE_NIGHT_NO, AppCompatDelegate.MODE_NIGHT_YES -> mode
            else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
    }

    fun save(context: Context, mode: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit().putInt(KEY_NIGHT_MODE, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }
}
