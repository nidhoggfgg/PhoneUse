package ai.nidhoggfgg.phoneuse

import android.app.*
import android.content.Intent
import android.content.Context
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import ai.nidhoggfgg.phoneuse.core.DeviceController
import ai.nidhoggfgg.phoneuse.device.AndroidBackend
import ai.nidhoggfgg.phoneuse.device.AppLaunchGuidance
import ai.nidhoggfgg.phoneuse.device.KeepAliveSettings
import ai.nidhoggfgg.phoneuse.server.HttpServer
import ai.nidhoggfgg.phoneuse.server.IdentityStore
import ai.nidhoggfgg.phoneuse.server.ConnectionSettings
import java.util.UUID

class PhoneUseService : Service() {
    lateinit var identities: IdentityStore
        private set
    lateinit var controller: DeviceController
        private set
    private var server: HttpServer? = null
    private var starting = false
    private val connectionSettings by lazy { ConnectionSettings(this) }
    private val launchGuidance by lazy { AppLaunchGuidance(this) }
    private val keepAlive by lazy { KeepAliveSettings(this) }
    private val connectionListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == ConnectionSettings.ALLOW_HTTP) server?.setAllowInsecureHttp(connectionSettings.allowInsecureHttp)
    }
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var lastClient: String? = null
    @Volatile private var lastContact = 0L
    @Volatile private var destroyed = false
    private var notificationText = ""
    private val refresh = object : Runnable {
        override fun run() { updateNotification(); handler.postDelayed(this, 1000) }
    }
    override fun onCreate() {
        super.onCreate()
        lastError = null
        connectionSettings.preferences.registerOnSharedPreferenceChangeListener(connectionListener)
        createNotificationChannel()
        val startingText = ContextCompat.getContextForLanguage(this).getString(R.string.service_starting)
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, notification(startingText), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, notification(startingText))
    }
    private fun createNotificationChannel() {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, ContextCompat.getContextForLanguage(this).getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW))
    }
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        createNotificationChannel()
        updateNotification(force = true)
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Re-check at delivery time: the user may have disabled recovery after it was queued.
        if ((intent == null || intent.action == ACTION_RESTORE) && !keepAlive.enabled) {
            // Ignore a stale recovery command if a user-started session is already running.
            if (server == null && !starting) stopSelf()
            return START_NOT_STICKY
        }
        if (server == null && !starting) startEndpoint()
        return if (keepAlive.enabled) START_STICKY else START_NOT_STICKY
    }
    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!keepAlive.enabled) stopSelf()
        super.onTaskRemoved(rootIntent)
    }
    private fun startEndpoint() {
        starting = true
        Thread({
            try {
                val identity = IdentityStore(this)
                handler.post {
                    if (destroyed) return@post
                    identities = identity
                    val serviceInstanceId = UUID.randomUUID().toString()
                    controller = DeviceController(AndroidBackend(this, serviceInstanceId = serviceInstanceId), SystemClock::elapsedRealtime, identities::exists) {
                        handler.post { if (!destroyed) updateNotification() }
                    }
                    try {
                        server = HttpServer(identities, controller, assets, serviceInstanceId,
                            allowInsecureHttp = connectionSettings.allowInsecureHttp, allowedHosts = { connectionSettings.allowedHosts }) { client ->
                            lastClient = client; lastContact = SystemClock.elapsedRealtime()
                            handler.post { if (!destroyed) updateNotification() }
                        }.also { it.start() }
                        current = this
                        lastError = null
                        handler.post(refresh)
                    } catch (e: Exception) { lastError = ServiceError(R.string.http_start_failed, "${e.javaClass.simpleName} ${e.message.orEmpty()}"); stopSelf() }
                    starting = false
                }
            } catch (e: Exception) {
                lastError = ServiceError(R.string.client_storage_failed, e.javaClass.simpleName)
                handler.post { stopSelf() }
            }
        }, "phone-use-start").start()
    }
    fun revokeClient(id: String) {
        identities.revoke(id)
        identities.pairing.revoked(id)
        controller.revokeClient(id)
        if (lastClient == id) lastClient = null
        updateNotification()
    }
    fun stateMessage(): Int {
        if (!::controller.isInitialized) return R.string.service_starting
        return when {
            identities.pairing.pending().isNotEmpty() -> R.string.service_pairing_pending
            launchGuidance.unconfirmedPackage != null -> R.string.launch_unconfirmed_notification
            lastClient != null && SystemClock.elapsedRealtime() - lastContact < 60_000 -> R.string.service_recent_request
            else -> R.string.service_available
        }
    }
    private fun updateNotification(force: Boolean = false) {
        // AppCompat applies pre-Android 13 locales to activities only. Resolve notification
        // resources explicitly so a running or independently restored service uses the same language.
        val localized = ContextCompat.getContextForLanguage(this)
        val text = localized.getString(stateMessage())
        val key = "${localized.resources.configuration.locales.toLanguageTags()}:$text"
        if (!force && notificationText == key) return
        notificationText = key
        createNotificationChannel()
        getSystemService(NotificationManager::class.java).notify(1, notification(text))
    }
    private fun notification(text: String): Notification {
        val localized = ContextCompat.getContextForLanguage(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_control)
            .setContentTitle(localized.getString(R.string.app_name)).setContentText(text).setContentIntent(open).setOngoing(true)
            .setOnlyAlertOnce(true).setSilent(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        destroyed = true
        connectionSettings.preferences.unregisterOnSharedPreferenceChangeListener(connectionListener)
        if (current === this) current = null
        if (::controller.isInitialized) controller.stop()
        if (::identities.isInitialized) identities.pairing.clear()
        server?.stop(); server = null
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    data class ServiceError(val messageRes: Int, val detail: String)
    companion object {
        const val CHANNEL = "phone_use_control"
        private const val ACTION_RESTORE = "ai.nidhoggfgg.phoneuse.RESTORE"

        /** Start only at OS-granted opportunities; never use alarms or retry loops to evade a stop. */
        internal fun restoreIfEnabled(context: Context) {
            if (!KeepAliveSettings(context).enabled) return
            try {
                context.startForegroundService(Intent(context, PhoneUseService::class.java).setAction(ACTION_RESTORE))
            } catch (e: IllegalStateException) {
                // Includes ForegroundServiceStartNotAllowedException on restricted firmware.
                lastError = ServiceError(R.string.http_start_failed, e.javaClass.simpleName)
            } catch (e: SecurityException) {
                lastError = ServiceError(R.string.http_start_failed, e.javaClass.simpleName)
            }
        }
        @Volatile var current: PhoneUseService? = null
            private set
        @Volatile var lastError: ServiceError? = null
            private set
    }
}
