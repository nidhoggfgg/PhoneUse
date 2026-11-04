package ai.nidhoggfgg.phoneuse.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ai.nidhoggfgg.phoneuse.PhoneUseService

/** Credential storage is available after BOOT_COMPLETED; do not start during direct boot. */
class KeepAliveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            PhoneUseService.restoreIfEnabled(context)
        }
    }
}
