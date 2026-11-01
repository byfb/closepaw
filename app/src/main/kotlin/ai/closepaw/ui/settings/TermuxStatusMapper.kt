package ai.closepaw.ui.settings

import ai.closepaw.R

import ai.closepaw.termux.NeedsSetupReason
import ai.closepaw.termux.TermuxBridgeStatus

/**
 * Pure mapper: collapse the live Termux bridge state, the runtime permission disposition,
 * and the user's enable pref into the display-only [ToolStatusUi] the unified tool card
 * renders. No Compose dependency — covered by JVM unit tests.
 *
 * Label words MUST stay exactly `R.string.ui_ready / R.string.ui_needs_setup / R.string.ui_setting_up / R.string.ui_not_installed
 * / R.string.ui_disabled` — `SettingsTermuxRowTest` matches on them.
 */
internal fun termuxStatusUi(
    state: TermuxBridgeStatus,
    permissionDisposition: RunCommandPermissionDisposition?,
    enabledPref: Boolean,
): ToolStatusUi {
    val displayed = if (enabledPref) state else TermuxBridgeStatus.Disabled
    return ToolStatusUi(
        label = displayed.label(),
        subtitle = displayed.subtitleFor(permissionDisposition),
        tone = displayed.tone(),
        showSpinner = displayed is TermuxBridgeStatus.SetupInProgress,
    )
}

private fun TermuxBridgeStatus.label(): Int = when (this) {
    TermuxBridgeStatus.NotInstalled -> R.string.ui_not_installed
    is TermuxBridgeStatus.NeedsSetup -> R.string.ui_needs_setup
    TermuxBridgeStatus.SetupInProgress -> R.string.ui_setting_up
    TermuxBridgeStatus.Ready -> R.string.ui_ready
    TermuxBridgeStatus.Disabled -> R.string.ui_disabled
}

private fun TermuxBridgeStatus.tone(): ToolStatusTone = when (this) {
    TermuxBridgeStatus.Ready -> ToolStatusTone.Positive
    TermuxBridgeStatus.NotInstalled,
    is TermuxBridgeStatus.NeedsSetup -> ToolStatusTone.Warning
    TermuxBridgeStatus.SetupInProgress,
    TermuxBridgeStatus.Disabled -> ToolStatusTone.Neutral
}

private fun TermuxBridgeStatus.subtitle(): Int = when (this) {
    TermuxBridgeStatus.NotInstalled -> R.string.ui_install_termux_from_f_droid
    is TermuxBridgeStatus.NeedsSetup -> reason.toDisplayText()
    TermuxBridgeStatus.SetupInProgress -> R.string.ui_this_may_take_a_minute
    TermuxBridgeStatus.Ready -> R.string.ui_termux_bridge_running_tap_to_restart
    TermuxBridgeStatus.Disabled -> R.string.ui_toggle_to_enable
}

/**
 * PERMISSION_MISSING is the only reason whose subtitle depends on the runtime disposition.
 * `OpenAppSettings` means the user picked "Don't ask again", so the system dialog no longer
 * surfaces — point them to App Settings instead of "tap to grant".
 */
private fun TermuxBridgeStatus.subtitleFor(
    permissionDisposition: RunCommandPermissionDisposition?,
): Int {
    if (this is TermuxBridgeStatus.NeedsSetup &&
        reason == NeedsSetupReason.PERMISSION_MISSING
    ) {
        return when (permissionDisposition) {
            RunCommandPermissionDisposition.OpenAppSettings ->
                R.string.ui_termux_permission_denied
            RunCommandPermissionDisposition.Request,
            RunCommandPermissionDisposition.Granted,
            null -> R.string.ui_tap_to_grant_run_command_permission_to_termux
        }
    }
    return subtitle()
}

private fun NeedsSetupReason.toDisplayText(): Int = when (this) {
    NeedsSetupReason.PERMISSION_MISSING -> R.string.ui_tap_to_grant_run_command_permission_to_termux
    NeedsSetupReason.ALLOW_EXTERNAL_APPS_MISSING ->
        R.string.ui_termux_external_apps_disabled
    NeedsSetupReason.TERMUX_NOT_RUNNING ->
        R.string.ui_termux_is_not_running_tap_to_open_termux_then_return_here
    NeedsSetupReason.TERMUX_RUN_COMMAND_UNAVAILABLE ->
        R.string.ui_termux_incompatible_build
    NeedsSetupReason.PACKAGES_MISSING -> R.string.ui_missing_packages_tap_to_install_python_git_ripgrep
    NeedsSetupReason.BRIDGE_OUTDATED -> R.string.ui_bridge_daemon_out_of_date_tap_to_update
    NeedsSetupReason.HEALTH_TIMEOUT -> R.string.ui_bridge_unreachable_tap_to_retry_setup
    NeedsSetupReason.TERMUX_TIMEOUT -> R.string.ui_termux_command_timed_out_open_termux_once_and_retry
    NeedsSetupReason.PORT_IN_USE -> R.string.ui_port_18422_in_use_by_another_process
    NeedsSetupReason.UNKNOWN -> R.string.ui_setup_error_tap_to_retry
}
