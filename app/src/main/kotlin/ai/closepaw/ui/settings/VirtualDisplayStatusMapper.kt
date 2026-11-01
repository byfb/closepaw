package ai.closepaw.ui.settings

import ai.closepaw.R

import ai.closepaw.protocol.PlatformMode

/**
 * Unified card state for the Display Mode toggle. Carries everything the composable needs
 * to render — status pill, switch checked + enabled flags, and an optional row action — so
 * the Compose layer never re-derives state from raw inputs.
 */
internal data class VirtualDisplayCardState(
    val status: ToolStatusUi,
    val switchChecked: Boolean,
    val switchEnabled: Boolean,
    val rowAction: VirtualDisplayRowAction?,
)

/**
 * What a tap on the card row should do, decided by the mapper. Composables translate these
 * into concrete calls (gate methods, direct Shizuku request). Null means the row is
 * non-interactive; the Switch remains independently tappable.
 */
internal sealed interface VirtualDisplayRowAction {
    /** Re-attempt OFF→ON gate. Composable calls `gate.clearError(); gate.setEnabled(true)`. */
    data object RetryEnable : VirtualDisplayRowAction
    /**
     * Request Shizuku permission directly; bypasses the gate. Composable calls
     * `ShizukuRuntimeGateway().requestPermissionAndAwait()` on a coroutine scope.
     */
    data object RequestPermission : VirtualDisplayRowAction
}

/**
 * Pure mapper: collapse the five observable inputs (persisted mode, effective mode, Shizuku
 * status, gate pending, gate error) into the unified card's [VirtualDisplayCardState].
 * First-match-wins ordering is the source of truth.
 *
 * Invariants:
 * - `switchChecked` strictly mirrors `persistedMode == VIRTUAL_DISPLAY` — never the last
 *   gesture, never optimistic during pending.
 * - Gate errors only surface when `persistedMode == ACCESSIBILITY`; once persisted has
 *   already flipped to VD, the error is stale and the Shizuku-status rows take over.
 * - Row 10 says "configured for" not "running on": `effectiveMode == VIRTUAL_DISPLAY` only
 *   proves the session selected `VirtualDisplayPlatform`, not that `platform.start()`
 *   succeeded.
 */
internal fun virtualDisplayCardState(
    persistedMode: PlatformMode,
    effectiveMode: PlatformMode?,
    shizukuStatus: ShizukuStatus,
    gatePending: Boolean,
    gateError: VirtualDisplayToggleError?,
): VirtualDisplayCardState {
    val checked = persistedMode == PlatformMode.VIRTUAL_DISPLAY

    // Row 1
    if (gatePending) {
        return VirtualDisplayCardState(
            status = ToolStatusUi(
                label = R.string.ui_setting_up,
                subtitle = R.string.ui_checking_shizuku,
                tone = ToolStatusTone.Neutral,
                showSpinner = true,
            ),
            switchChecked = checked,
            switchEnabled = false,
            rowAction = null,
        )
    }

    // Rows 2–3: gate errors only when persisted is ACCESSIBILITY
    if (gateError != null && persistedMode == PlatformMode.ACCESSIBILITY) {
        return VirtualDisplayCardState(
            status = ToolStatusUi(
                label = R.string.ui_needs_setup,
                subtitle = gateError.message(),
                tone = ToolStatusTone.Warning,
                showSpinner = false,
            ),
            switchChecked = false,
            switchEnabled = true,
            rowAction = VirtualDisplayRowAction.RetryEnable,
        )
    }

    // Rows 4–5: persisted ACCESSIBILITY
    if (persistedMode == PlatformMode.ACCESSIBILITY) {
        val subtitle = if (effectiveMode == PlatformMode.VIRTUAL_DISPLAY) {
            R.string.ui_virtual_display_ending
        } else {
            R.string.ui_agent_runs_on_your_current_screen
        }
        return VirtualDisplayCardState(
            status = ToolStatusUi(
                label = R.string.ui_disabled,
                subtitle = subtitle,
                tone = ToolStatusTone.Neutral,
                showSpinner = false,
            ),
            switchChecked = false,
            switchEnabled = true,
            rowAction = null,
        )
    }

    // Rows 6–12: persisted VIRTUAL_DISPLAY
    val activeIsVd = effectiveMode == PlatformMode.VIRTUAL_DISPLAY
    return when (shizukuStatus) {
        is ShizukuStatus.Unavailable ->
            if (activeIsVd) {
                // Row 8
                VirtualDisplayCardState(
                    status = ToolStatusUi(
                        label = R.string.ui_degraded,
                        subtitle = R.string.ui_virtual_display_disconnected,
                        tone = ToolStatusTone.Warning,
                        showSpinner = false,
                    ),
                    switchChecked = true,
                    switchEnabled = true,
                    rowAction = null,
                )
            } else {
                // Row 6
                VirtualDisplayCardState(
                    status = ToolStatusUi(
                        label = R.string.ui_virtual_display_unavailable,
                        subtitle = R.string.ui_virtual_display_needs_shizuku,
                        tone = ToolStatusTone.Warning,
                        showSpinner = false,
                    ),
                    switchChecked = true,
                    switchEnabled = true,
                    rowAction = null,
                )
            }
        is ShizukuStatus.NeedsPermission ->
            if (activeIsVd) {
                // Row 9
                VirtualDisplayCardState(
                    status = ToolStatusUi(
                        label = R.string.ui_degraded,
                        subtitle = R.string.ui_virtual_display_permission_lost,
                        tone = ToolStatusTone.Warning,
                        showSpinner = false,
                    ),
                    switchChecked = true,
                    switchEnabled = true,
                    rowAction = VirtualDisplayRowAction.RequestPermission,
                )
            } else {
                // Row 7
                VirtualDisplayCardState(
                    status = ToolStatusUi(
                        label = R.string.ui_needs_setup,
                        subtitle = R.string.ui_grant_shizuku_permission_to_use_virtual_display,
                        tone = ToolStatusTone.Warning,
                        showSpinner = false,
                    ),
                    switchChecked = true,
                    switchEnabled = true,
                    rowAction = VirtualDisplayRowAction.RequestPermission,
                )
            }
        is ShizukuStatus.Ready -> when (effectiveMode) {
            PlatformMode.VIRTUAL_DISPLAY -> // Row 10
                VirtualDisplayCardState(
                    status = ToolStatusUi(
                        label = R.string.ui_ready,
                        subtitle = R.string.ui_current_session_is_configured_for_a_virtual_display,
                        tone = ToolStatusTone.Positive,
                        showSpinner = false,
                    ),
                    switchChecked = true,
                    switchEnabled = true,
                    rowAction = null,
                )
            PlatformMode.ACCESSIBILITY -> // Row 11
                VirtualDisplayCardState(
                    status = ToolStatusUi(
                        label = R.string.ui_ready,
                        subtitle = R.string.ui_will_switch_to_a_virtual_display_on_the_next_session,
                        tone = ToolStatusTone.Neutral,
                        showSpinner = false,
                    ),
                    switchChecked = true,
                    switchEnabled = true,
                    rowAction = null,
                )
            null -> // Row 12
                VirtualDisplayCardState(
                    status = ToolStatusUi(
                        label = R.string.ui_ready,
                        subtitle = R.string.ui_will_start_on_a_virtual_display,
                        tone = ToolStatusTone.Positive,
                        showSpinner = false,
                    ),
                    switchChecked = true,
                    switchEnabled = true,
                    rowAction = null,
                )
        }
    }
}
