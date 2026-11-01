package ai.closepaw.ui.settings

import ai.closepaw.R

import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers every row of the Termux mapping table.
 *
 * The label words are load-bearing — `SettingsTermuxRowTest` (instrumentation) matches on
 * them. Tone / spinner / subtitle assertions guard against
 * silent UX regressions when someone edits the mapper.
 */
class TermuxStatusMapperTest {

    @Test
    fun `Disabled state maps to Disabled label, Neutral tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.Disabled,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo(R.string.ui_disabled)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitle).isEqualTo(R.string.ui_toggle_to_enable)
    }

    @Test
    fun `enabledPref false forces Disabled regardless of underlying state`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.Ready,
            permissionDisposition = null,
            enabledPref = false,
        )
        assertThat(ui.label).isEqualTo(R.string.ui_disabled)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitle).isEqualTo(R.string.ui_toggle_to_enable)
    }

    @Test
    fun `NotInstalled maps to Not Installed label, Warning tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NotInstalled,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo(R.string.ui_not_installed)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitle).isEqualTo(R.string.ui_install_termux_from_f_droid)
    }

    @Test
    fun `NeedsSetup maps to Needs Setup label, Warning tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PACKAGES_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo(R.string.ui_needs_setup)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(ui.showSpinner).isFalse()
    }

    @Test
    fun `SetupInProgress maps to Setting up label, Neutral tone, spinner shown`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.SetupInProgress,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo(R.string.ui_setting_up)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(ui.showSpinner).isTrue()
        assertThat(ui.subtitle).isEqualTo(R.string.ui_this_may_take_a_minute)
    }

    @Test
    fun `Ready maps to Ready label, Positive tone, no spinner`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.Ready,
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.label).isEqualTo(R.string.ui_ready)
        assertThat(ui.tone).isEqualTo(ToolStatusTone.Positive)
        assertThat(ui.showSpinner).isFalse()
        assertThat(ui.subtitle).isEqualTo(R.string.ui_termux_bridge_running_tap_to_restart)
    }

    // Below: NeedsSetupReason subtitle matrix. The design table only requires the umbrella
    // label R.string.ui_needs_setup / Warning tone for every NeedsSetup variant, but the subtitle is
    // what tells the user what to actually do. Pin each reason so a future copy edit shows
    // up in CI rather than only in the user's hands.
    //
    // Design doc is the source of truth for label wording — these tests pin current subtitle
    // wording of the mapper.

    @Test
    fun `NeedsSetup PERMISSION_MISSING with null disposition shows tap-to-grant subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(R.string.ui_tap_to_grant_run_command_permission_to_termux)
    }

    @Test
    fun `NeedsSetup PERMISSION_MISSING with Request disposition shows tap-to-grant subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = RunCommandPermissionDisposition.Request,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(R.string.ui_tap_to_grant_run_command_permission_to_termux)
    }

    @Test
    fun `NeedsSetup PERMISSION_MISSING with Granted disposition shows tap-to-grant subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = RunCommandPermissionDisposition.Granted,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(R.string.ui_tap_to_grant_run_command_permission_to_termux)
    }

    @Test
    fun `NeedsSetup PERMISSION_MISSING with OpenAppSettings disposition redirects to App Settings`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PERMISSION_MISSING),
            permissionDisposition = RunCommandPermissionDisposition.OpenAppSettings,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(
            R.string.ui_termux_permission_denied
        )
    }

    @Test
    fun `NeedsSetup ALLOW_EXTERNAL_APPS_MISSING subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(
            R.string.ui_termux_external_apps_disabled
        )
    }

    @Test
    fun `NeedsSetup TERMUX_NOT_RUNNING subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_NOT_RUNNING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(
            R.string.ui_termux_is_not_running_tap_to_open_termux_then_return_here
        )
    }

    @Test
    fun `NeedsSetup TERMUX_RUN_COMMAND_UNAVAILABLE subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(
            R.string.ui_termux_incompatible_build
        )
    }

    @Test
    fun `NeedsSetup PACKAGES_MISSING subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PACKAGES_MISSING),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(R.string.ui_missing_packages_tap_to_install_python_git_ripgrep)
    }

    @Test
    fun `NeedsSetup BRIDGE_OUTDATED subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.BRIDGE_OUTDATED),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(R.string.ui_bridge_daemon_out_of_date_tap_to_update)
    }

    @Test
    fun `NeedsSetup HEALTH_TIMEOUT subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.HEALTH_TIMEOUT),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(R.string.ui_bridge_unreachable_tap_to_retry_setup)
    }

    @Test
    fun `NeedsSetup TERMUX_TIMEOUT subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.TERMUX_TIMEOUT),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(R.string.ui_termux_command_timed_out_open_termux_once_and_retry)
    }

    @Test
    fun `NeedsSetup PORT_IN_USE subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.PORT_IN_USE),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(R.string.ui_port_18422_in_use_by_another_process)
    }

    @Test
    fun `NeedsSetup UNKNOWN subtitle`() {
        val ui = termuxStatusUi(
            state = TermuxBridgeStatus.NeedsSetup(NeedsSetupReason.UNKNOWN),
            permissionDisposition = null,
            enabledPref = true,
        )
        assertThat(ui.subtitle).isEqualTo(R.string.ui_setup_error_tap_to_retry)
    }
}
