package ai.closepaw.qa

import ai.closepaw.R
import ai.closepaw.ui.theme.appResources
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Dialog body copy is unique in this page and stable across redraws — use it as
 * the dialog-visible marker.
 */
private const val CLEAR_TRACES_DIALOG_BODY =
    "All recorded execution traces will be permanently deleted. This cannot be undone."

@RunWith(AndroidJUnit4::class)
class SettingsPermissionsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    // S10: trace toggle ON shows the sensitive-data warning banner; OFF hides it.
    @Test fun session_traces_toggle_shows_warning_banner() {
        compose.setContent {
            var enabled by remember { mutableStateOf(false) }
            TestPermissionsPage(
                traceEnabled = enabled,
                onTraceEnabledChange = { enabled = it },
            )
        }

        val strings = appResources(compose.activity)
        val warning = compose.onNodeWithText(
            strings.getString(R.string.ui_traces_may_include_screenshots_and_inputs_turn_off_when_done)
        )
        val toggle = compose.onNodeWithTag("qa-session-traces-switch")

        toggle.assertIsOff()
        warning.assertDoesNotExist()
        toggle.performScrollTo().performClick().assertIsOn()
        warning.performScrollTo().assertIsDisplayed()
        toggle.performScrollTo().performClick().assertIsOff()
        warning.assertDoesNotExist()
    }

    // S11: clicking Clear Traces opens the confirmation dialog with the
    // expected confirm button present and enabled.
    @Test fun clear_traces_shows_confirm_dialog() {
        compose.setContent { TestPermissionsPage(traceEnabled = false) }

        // No dialog rendered initially.
        compose.onAllNodesWithText(CLEAR_TRACES_DIALOG_BODY).assertCountEquals(0)

        compose.onNodeWithText("Clear Traces").performClick()

        // Dialog semantics anchor: onNode(isDialog()) — proves it's actually a dialog node.
        compose.onNode(isDialog()).assertExists()

        // The Confirm button lives inside the dialog and is enabled — guards against
        // the body text appearing via some non-dialog surface.
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("Clear"))
            .assertExists()
            .assertIsEnabled()
    }

    // S12: confirming the dialog closes it and disables the Clear Traces button.
    @Test fun clear_traces_confirm_disables_button() {
        compose.setContent { TestPermissionsPage(traceEnabled = false) }

        compose.onNodeWithText("Clear Traces").performClick()
        // Click the dialog's confirm button specifically (anchored to dialog ancestor).
        compose.onNode(hasAnyAncestor(isDialog()) and hasText("Clear"))
            .performClick()

        // Dialog dismissed, main button relabeled and disabled.
        compose.waitUntil(timeoutMillis = 3000) {
            compose.onAllNodesWithText("Traces Cleared").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Traces Cleared").assertIsNotEnabled()
        compose.onAllNodesWithText(CLEAR_TRACES_DIALOG_BODY).assertCountEquals(0)
    }
}
