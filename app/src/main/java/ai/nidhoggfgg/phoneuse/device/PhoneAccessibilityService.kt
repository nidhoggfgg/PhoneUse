package ai.nidhoggfgg.phoneuse.device

import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import ai.nidhoggfgg.phoneuse.PhoneUseService
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

class PhoneAccessibilityService : AccessibilityService() {
    val instanceId: String = UUID.randomUUID().toString()
    private data class Change(val version: Long, val type: String, val window: Int, val at: Long,
        val sourceIdentity: String?, val contentChangeTypes: Int, val textOnly: Boolean)
    private val changes = ArrayDeque<Change>()
    private val windowVersions = HashMap<Int, Long>()
    private var globalVersion = 0L
    private var discardedThroughVersion = 0L

    @Synchronized private fun record(type: String, window: Int, global: Boolean = false,
        sourceIdentity: String? = null, contentChangeTypes: Int = 0, textOnly: Boolean = false) {
        val next = version.incrementAndGet()
        if (global || window == -1) globalVersion = next else windowVersions[window] = next
        // Bound both the journal and remembered windows across long-lived service sessions.
        if (windowVersions.size > 64) {
            globalVersion = next
            windowVersions.clear()
        }
        changes.addLast(Change(next, type, window, SystemClock.elapsedRealtime(), sourceIdentity, contentChangeTypes, textOnly))
        while (changes.size > 128) discardedThroughVersion = changes.removeFirst().version
    }

    @Synchronized fun relevantVersion(window: Int): Long = maxOf(globalVersion, windowVersions[window] ?: 0)

    @Synchronized internal fun eventHistoryComplete(previous: Long): Boolean = previous >= discardedThroughVersion

    @Synchronized fun eventsSince(previous: Long, window: Int): org.json.JSONArray = org.json.JSONArray().apply {
        changes.filter { it.version > previous && (it.window == window || it.window == -1 ||
            it.type == "TYPE_WINDOW_STATE_CHANGED" || it.type == "TYPE_WINDOWS_CHANGED") }.forEach {
            put(ai.nidhoggfgg.phoneuse.core.json("ui_version" to it.version, "type" to it.type,
                "window_id" to it.window, "age_ms" to (SystemClock.elapsedRealtime() - it.at),
                "source_identity" to it.sourceIdentity, "content_change_types" to it.contentChangeTypes,
                "text_only" to it.textOnly))
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        current = this
        record("SERVICE_CONNECTED", -1)
        // System rebinding can happen without an activity (for example after process reclamation).
        PhoneUseService.restoreIfEnabled(this)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event != null && event.eventType and (
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOWS_CHANGED or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or AccessibilityEvent.TYPE_VIEW_SCROLLED or
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED or AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED or
            AccessibilityEvent.TYPE_VIEW_CLICKED or AccessibilityEvent.TYPE_VIEW_LONG_CLICKED or
            AccessibilityEvent.TYPE_VIEW_FOCUSED or AccessibilityEvent.TYPE_VIEW_SELECTED or
            AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED or AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED
        ) != 0) {
            val source = event.source
            val identity = try { source?.let { accessibilityIdentityHash(it) } } finally { source?.recycle() }
            val textOnly = event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED ||
                (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
                    event.contentChangeTypes == AccessibilityEvent.CONTENT_CHANGE_TYPE_TEXT)
            record(AccessibilityEvent.eventTypeToString(event.eventType), event.windowId,
                event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED,
                identity, event.contentChangeTypes, textOnly)
        }
    }
    override fun onInterrupt() { record("SERVICE_INTERRUPTED", -1) }
    override fun onDestroy() {
        if (current === this) current = null
        record("SERVICE_DESTROYED", -1)
        super.onDestroy()
    }
    companion object {
        @Volatile var current: PhoneAccessibilityService? = null
            private set
        val version = AtomicLong()
    }
}
