package ai.nidhoggfgg.phoneuse.voice

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/** Rendezvous for the shell process. The capability disappears when either process exits. */
class VoiceProvider : ContentProvider() {
    data class Attachment(val helper: IVoiceHelper, val routingError: String?)
    companion object {
        @Volatile var attachment: Attachment? = null
            private set
    }
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (Binder.getCallingUid() != 2000) throw SecurityException("Only adb shell can attach")
        require(method == "attach")
        val binder = requireNotNull(extras?.getBinder("helper"))
        synchronized(VoiceProvider::class.java) {
            check(attachment?.helper?.asBinder()?.isBinderAlive != true) { "A voice helper is already attached" }
            binder.linkToDeath({
                synchronized(VoiceProvider::class.java) { if (attachment?.helper?.asBinder() === binder) attachment = null }
            }, 0)
            attachment = Attachment(IVoiceHelper.Stub.asInterface(binder), extras.getString("routing_error"))
        }
        return Bundle.EMPTY
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?) = 0
}
