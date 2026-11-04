package ai.nidhoggfgg.phoneuse

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import ai.nidhoggfgg.phoneuse.ui.PhoneUseApp
import ai.nidhoggfgg.phoneuse.ui.theme.PhoneUseTheme
import ai.nidhoggfgg.phoneuse.ui.theme.ThemePreference
import ai.nidhoggfgg.phoneuse.server.ConnectionAddresses

class MainActivity : AppCompatActivity() {
    private val permission = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(ThemePreference.read(this))
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            PhoneUseTheme {
                PhoneUseApp(
                    onRetryService = ::startEndpoint,
                    onAccessibilitySettings = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                    onOpenWebConsole = { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ConnectionAddresses.LOCAL_CONSOLE))) }
                )
            }
        }
        requestConnectionPermissions()
    }

    override fun onStart() {
        super.onStart()
        startEndpoint()
    }

    private fun startEndpoint() {
        startForegroundService(Intent(this, PhoneUseService::class.java))
    }

    private fun requestConnectionPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED)
            permissions.add("android.permission.ACCESS_LOCAL_NETWORK")
        if (permissions.isNotEmpty()) permission.launch(permissions.toTypedArray())
    }
}
