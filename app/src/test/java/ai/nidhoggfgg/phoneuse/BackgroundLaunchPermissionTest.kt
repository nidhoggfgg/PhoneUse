package ai.nidhoggfgg.phoneuse

import android.app.AppOpsManager
import ai.nidhoggfgg.phoneuse.device.BackgroundLaunchPermission
import ai.nidhoggfgg.phoneuse.device.BackgroundLaunchPermissionChecker
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.reflect.InvocationTargetException

class BackgroundLaunchPermissionTest {
    @Test fun otherManufacturersNeverQueryTheXiaomiOperation() {
        val checker = BackgroundLaunchPermissionChecker(false) { error("Must not query an OEM operation") }
        assertEquals(BackgroundLaunchPermission.UNKNOWN, checker.read())
    }

    @Test fun onlyAnExplicitAllowEstablishesPermission() {
        assertEquals(BackgroundLaunchPermission.ALLOWED,
            BackgroundLaunchPermissionChecker(true) { AppOpsManager.MODE_ALLOWED }.read())
        for (mode in listOf(AppOpsManager.MODE_DEFAULT, 5, -1, null)) {
            assertEquals("mode=$mode", BackgroundLaunchPermission.UNKNOWN,
                BackgroundLaunchPermissionChecker(true) { mode }.read())
        }
    }

    @Test fun foregroundOnlyAndExplicitRejectionsNeedBackgroundPermission() {
        for (mode in listOf(AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED, AppOpsManager.MODE_FOREGROUND)) {
            assertEquals("mode=$mode", BackgroundLaunchPermission.DENIED,
                BackgroundLaunchPermissionChecker(true) { mode }.read())
        }
    }

    @Test fun unsupportedFirmwareAndRestrictedApisRemainUnknown() {
        for (failure in listOf(NoSuchMethodException(), SecurityException(),
            InvocationTargetException(IllegalArgumentException("Unknown operation")))) {
            assertEquals(BackgroundLaunchPermission.UNKNOWN,
                BackgroundLaunchPermissionChecker(true) { throw failure }.read())
        }
    }

    @Test fun grantsAndRevocationsAreReadAgainInsteadOfPersisted() {
        var mode = AppOpsManager.MODE_IGNORED
        val checker = BackgroundLaunchPermissionChecker(true) { mode }
        assertEquals(BackgroundLaunchPermission.DENIED, checker.read())
        mode = AppOpsManager.MODE_ALLOWED
        assertEquals(BackgroundLaunchPermission.ALLOWED, checker.read())
        mode = AppOpsManager.MODE_IGNORED
        assertEquals(BackgroundLaunchPermission.DENIED, checker.read())
    }
}
