package ai.nidhoggfgg.phoneuse

import ai.nidhoggfgg.phoneuse.core.DeviceError
import ai.nidhoggfgg.phoneuse.device.AppLaunchConfirmation
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AppLaunchConfirmationTest {
    private var now = 0L
    private val confirmation = AppLaunchConfirmation({ now }, { now += it })

    @Test fun waitsThroughOtherWindowsUntilTargetIsObserved() {
        val result = confirmation.awaitForeground("target", {}) {
            when {
                now < 100 -> null
                now < 500 -> "permission.dialog"
                else -> "target"
            }
        }
        assertEquals(500L, now)
        assertEquals("foreground_observed", result.getString("confirmation"))
        assertEquals("target", result.getString("package_name"))
        assertTrue(result.isNull("business_success"))
    }

    @Test fun alreadyForegroundDoesNotAddADelay() {
        confirmation.awaitForeground("target", {}) { "target" }
        assertEquals(0L, now)
    }

    @Test fun silentRejectionTimesOutWithoutClaimingExecutionOrPermissionDenial() {
        val error = assertThrows(DeviceError::class.java) {
            confirmation.awaitForeground("target", {}) { "previous.app" }
        }
        assertEquals(AppLaunchConfirmation.TIMEOUT_MS, now)
        assertEquals("RESULT_UNKNOWN", error.code)
        assertEquals("launch_not_confirmed", error.details.getString("reason"))
        assertEquals(JSONObject.NULL, error.details.get("action_executed"))
    }

    @Test fun unreadableWindowIsNotSuccess() {
        val error = assertThrows(DeviceError::class.java) { confirmation.awaitForeground("target", {}) { null } }
        assertEquals("launch_not_confirmed", error.details.getString("reason"))
    }

    @Test fun cancellationAfterDispatchPreservesUncertaintyAndStopsPolling() {
        var reads = 0
        val error = assertThrows(DeviceError::class.java) {
            confirmation.awaitForeground("target", { if (now >= 200) throw DeviceError("CANCELLED", "Paused") }) {
                reads++; "previous.app"
            }
        }
        assertEquals(2, reads)
        assertEquals(200L, now)
        assertEquals("RESULT_UNKNOWN", error.code)
        assertEquals("launch_confirmation_interrupted", error.details.getString("reason"))
        assertEquals("CANCELLED", error.details.getString("cause"))
        assertEquals(JSONObject.NULL, error.details.get("action_executed"))
    }

    @Test fun serviceLossAfterDispatchIsNotReportedAsUnexecuted() {
        val error = assertThrows(DeviceError::class.java) {
            confirmation.awaitForeground("target", {}) { throw DeviceError("ACCESSIBILITY_UNAVAILABLE", "Service lost") }
        }
        assertEquals("RESULT_UNKNOWN", error.code)
        assertEquals("ACCESSIBILITY_UNAVAILABLE", error.details.getString("cause"))
        assertTrue(error.details.isNull("action_executed"))
    }
}
