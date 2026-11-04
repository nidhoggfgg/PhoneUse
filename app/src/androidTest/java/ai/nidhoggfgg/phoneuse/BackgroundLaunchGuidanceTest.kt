package ai.nidhoggfgg.phoneuse

import android.app.AppOpsManager
import ai.nidhoggfgg.phoneuse.device.BackgroundLaunchPermissionChecker
import ai.nidhoggfgg.phoneuse.ui.AppLaunchCard
import ai.nidhoggfgg.phoneuse.ui.theme.PhoneUseTheme
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackgroundLaunchGuidanceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun returningFromSettingsHidesGrantedPermissionAndShowsLaterRevocation() {
        var mode = AppOpsManager.MODE_IGNORED
        val checker = BackgroundLaunchPermissionChecker(true) { mode }
        compose.setContent { PhoneUseTheme { AppLaunchCard(permissionChecker = checker) } }
        compose.onNodeWithText(label(R.string.background_launch_denied)).assertIsDisplayed()

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        mode = AppOpsManager.MODE_ALLOWED
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(label(R.string.background_launch_title)).assertDoesNotExist()

        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        mode = AppOpsManager.MODE_IGNORED
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithText(label(R.string.background_launch_denied)).assertIsDisplayed()
    }

    @Test fun unreadablePermissionOnlyShowsAfterAnUnconfirmedLaunchAndCanBeDismissed() {
        val checker = BackgroundLaunchPermissionChecker(true) { throw SecurityException("Unavailable") }
        val failedPackage = mutableStateOf<String?>(null)
        compose.setContent { PhoneUseTheme {
            AppLaunchCard(failedPackage.value, onDismiss = { failedPackage.value = null }, permissionChecker = checker)
        } }
        compose.onNodeWithText(label(R.string.background_launch_title)).assertDoesNotExist()
        compose.runOnIdle { failedPackage.value = "com.example.target" }
        compose.onNodeWithText(label(R.string.background_launch_unknown)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.open_launch_settings)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.dismiss_launch_warning)).performClick()
        compose.onNodeWithText(label(R.string.background_launch_title)).assertDoesNotExist()
    }

    @Test fun grantedPermissionDoesNotConcealAnUnconfirmedLaunch() {
        val checker = BackgroundLaunchPermissionChecker(true) { AppOpsManager.MODE_ALLOWED }
        val failedPackage = mutableStateOf<String?>("com.example.target")
        compose.setContent { PhoneUseTheme {
            AppLaunchCard(failedPackage.value, onDismiss = { failedPackage.value = null }, permissionChecker = checker)
        } }
        compose.onNodeWithText(label(R.string.background_launch_allowed)).assertIsDisplayed()
        compose.onNodeWithText("com.example.target", substring = true).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.dismiss_launch_warning)).performClick()
        compose.onNodeWithText(label(R.string.background_launch_title)).assertDoesNotExist()
    }

    @Test fun settingsKeepsAManualEntryWhenPermissionCannotBeRead() {
        val checker = BackgroundLaunchPermissionChecker(false) { error("Unsupported OEM") }
        compose.setContent { PhoneUseTheme { AppLaunchCard(alwaysShow = true, permissionChecker = checker) } }
        compose.onNodeWithText(label(R.string.background_launch_unknown)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.open_launch_settings)).assertIsDisplayed()
        compose.onNodeWithText(label(R.string.dismiss_launch_warning)).assertDoesNotExist()
    }

    private fun label(id: Int) = compose.activity.getString(id)
}
