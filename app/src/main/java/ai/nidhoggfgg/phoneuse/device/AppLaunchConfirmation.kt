package ai.nidhoggfgg.phoneuse.device

import ai.nidhoggfgg.phoneuse.core.DeviceError
import ai.nidhoggfgg.phoneuse.core.fail
import ai.nidhoggfgg.phoneuse.core.json
import org.json.JSONObject

/** Runs only after dispatch. A silent platform rejection cannot be distinguished from a slow launch. */
internal class AppLaunchConfirmation(private val clock: () -> Long, private val sleep: (Long) -> Unit) {
    fun awaitForeground(packageName: String, check: () -> Unit, activePackage: () -> String?): JSONObject {
        val deadline = clock() + TIMEOUT_MS
        try {
            while (true) {
                check()
                val active = activePackage()
                check()
                if (active == packageName) return json("mechanism" to "intent", "confirmation" to "foreground_observed",
                    "package_name" to packageName, "business_success" to JSONObject.NULL)
                val remaining = deadline - clock()
                if (remaining <= 0) break
                sleep(minOf(100L, remaining))
            }
        } catch (error: Exception) {
            // The Intent was already sent. Cancellation/revocation/service loss cannot undo it.
            fail("RESULT_UNKNOWN", "Launch request was sent, but foreground confirmation was interrupted; observe before retrying",
                json("reason" to "launch_confirmation_interrupted", "package_name" to packageName,
                    "cause" to ((error as? DeviceError)?.code ?: "CONFIRMATION_UNAVAILABLE"), "action_executed" to JSONObject.NULL))
        }
        fail("RESULT_UNKNOWN", "Launch request was sent, but the target app was not observed in the foreground within 5 seconds; observe before retrying",
            json("reason" to "launch_not_confirmed", "package_name" to packageName,
                "timeout_ms" to TIMEOUT_MS, "action_executed" to JSONObject.NULL))
    }

    companion object { const val TIMEOUT_MS = 5_000L }
}
