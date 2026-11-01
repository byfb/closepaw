package ai.closepaw.ui.settings

import ai.closepaw.R

import ai.closepaw.protocol.PlatformMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers every row of the 12-row Virtual Display mapping table. First-match-wins ordering is critical: mis-ordering
 * (e.g. surfacing a stale gate error once persisted mode has already flipped to VD) is
 * exactly the regression these tests guard.
 *
 * Three invariants under test:
 * - `switchChecked` strictly mirrors `persistedMode == VIRTUAL_DISPLAY` (rows 1, 6–12 checked;
 *   rows 2–5 unchecked).
 * - Gate errors only surface when persisted is ACCESSIBILITY (rows 2–3); a stale gateError
 *   with persisted=VD must be ignored in favor of the Shizuku-status rows.
 * - Row 10 says "configured for" not "running on" — `effectiveMode == VIRTUAL_DISPLAY` only
 *   proves the session selected the VD platform, not that capture started.
 */
class VirtualDisplayStatusMapperTest {

    // Row 1: gatePending wins over everything else
    @Test
    fun `row 1 - gate pending shows Setting up with spinner, switch disabled`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.ACCESSIBILITY,
            effectiveMode = null,
            shizukuStatus = ShizukuStatus.Ready,
            gatePending = true,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_setting_up)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_checking_shizuku)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isTrue()
        assertThat(result.switchChecked).isFalse()
        assertThat(result.switchEnabled).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Row 2: gateError=ShizukuUnavailable while persisted=ACCESSIBILITY
    @Test
    fun `row 2 - ShizukuUnavailable gate error with persisted Accessibility shows Needs Setup with RetryEnable`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.ACCESSIBILITY,
            effectiveMode = PlatformMode.ACCESSIBILITY,
            shizukuStatus = ShizukuStatus.Unavailable,
            gatePending = false,
            gateError = VirtualDisplayToggleError.ShizukuUnavailable,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_needs_setup)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_enable_shizuku_for_display
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isFalse()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isEqualTo(VirtualDisplayRowAction.RetryEnable)
    }

    // Row 3: gateError=ShizukuPermissionDenied while persisted=ACCESSIBILITY
    @Test
    fun `row 3 - ShizukuPermissionDenied gate error with persisted Accessibility shows Needs Setup with RetryEnable`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.ACCESSIBILITY,
            effectiveMode = null,
            shizukuStatus = ShizukuStatus.NeedsPermission,
            gatePending = false,
            gateError = VirtualDisplayToggleError.ShizukuPermissionDenied,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_needs_setup)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_permission_denied_tap_to_retry_or_grant_in_shizuku_manager
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isFalse()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isEqualTo(VirtualDisplayRowAction.RetryEnable)
    }

    // Row 4: persisted=Accessibility, no active VD session
    @Test
    fun `row 4 - persisted Accessibility with no VD session shows Disabled neutral copy`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.ACCESSIBILITY,
            effectiveMode = null,
            shizukuStatus = ShizukuStatus.Ready,
            gatePending = false,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_disabled)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_agent_runs_on_your_current_screen)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isFalse()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isNull()

        // Same row also covers (A11y, A11y)
        val activeA11y = virtualDisplayCardState(
            persistedMode = PlatformMode.ACCESSIBILITY,
            effectiveMode = PlatformMode.ACCESSIBILITY,
            shizukuStatus = ShizukuStatus.Ready,
            gatePending = false,
            gateError = null,
        )
        assertThat(activeA11y.status.subtitle).isEqualTo(R.string.ui_agent_runs_on_your_current_screen)
    }

    // Row 5: user toggled off while a VD session is still alive
    @Test
    fun `row 5 - persisted Accessibility with active VD session shows next-session-will-use-screen copy`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.ACCESSIBILITY,
            effectiveMode = PlatformMode.VIRTUAL_DISPLAY,
            shizukuStatus = ShizukuStatus.Ready,
            gatePending = false,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_disabled)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_virtual_display_ending
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isFalse()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 6: persisted=VD, Shizuku unavailable, no active VD session
    @Test
    fun `row 6 - persisted VD with Shizuku Unavailable and no active VD shows Not Available warning`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.VIRTUAL_DISPLAY,
            effectiveMode = null,
            shizukuStatus = ShizukuStatus.Unavailable,
            gatePending = false,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_virtual_display_unavailable)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_virtual_display_needs_shizuku
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isTrue()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 7: persisted=VD, needs permission, no active VD session
    @Test
    fun `row 7 - persisted VD with NeedsPermission and no active VD shows Needs Setup with RequestPermission`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.VIRTUAL_DISPLAY,
            effectiveMode = PlatformMode.ACCESSIBILITY,
            shizukuStatus = ShizukuStatus.NeedsPermission,
            gatePending = false,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_needs_setup)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_grant_shizuku_permission_to_use_virtual_display)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isTrue()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isEqualTo(VirtualDisplayRowAction.RequestPermission)
    }

    // Row 8: Shizuku died mid-VD-session
    @Test
    fun `row 8 - persisted VD with Shizuku Unavailable mid-VD-session shows Degraded warning`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.VIRTUAL_DISPLAY,
            effectiveMode = PlatformMode.VIRTUAL_DISPLAY,
            shizukuStatus = ShizukuStatus.Unavailable,
            gatePending = false,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_degraded)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_virtual_display_disconnected
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isTrue()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 9: Shizuku permission revoked mid-VD-session
    @Test
    fun `row 9 - persisted VD with NeedsPermission mid-VD-session shows Degraded with RequestPermission`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.VIRTUAL_DISPLAY,
            effectiveMode = PlatformMode.VIRTUAL_DISPLAY,
            shizukuStatus = ShizukuStatus.NeedsPermission,
            gatePending = false,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_degraded)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_virtual_display_permission_lost
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isTrue()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isEqualTo(VirtualDisplayRowAction.RequestPermission)
    }

    // Row 10: VD selected + active VD session — "configured for", not "running on"
    @Test
    fun `row 10 - persisted VD with Ready and active VD session shows Ready positive configured-for copy`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.VIRTUAL_DISPLAY,
            effectiveMode = PlatformMode.VIRTUAL_DISPLAY,
            shizukuStatus = ShizukuStatus.Ready,
            gatePending = false,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_ready)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_current_session_is_configured_for_a_virtual_display)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Positive)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isTrue()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 11: VD selected, Ready, but active session is still A11y
    @Test
    fun `row 11 - persisted VD with Ready and active A11y session shows Ready neutral next-session copy`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.VIRTUAL_DISPLAY,
            effectiveMode = PlatformMode.ACCESSIBILITY,
            shizukuStatus = ShizukuStatus.Ready,
            gatePending = false,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_ready)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_will_switch_to_a_virtual_display_on_the_next_session)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isTrue()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 12: VD selected, Ready, no active session
    @Test
    fun `row 12 - persisted VD with Ready and no active session shows Ready positive will-start copy`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.VIRTUAL_DISPLAY,
            effectiveMode = null,
            shizukuStatus = ShizukuStatus.Ready,
            gatePending = false,
            gateError = null,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_ready)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_will_start_on_a_virtual_display)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Positive)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.switchChecked).isTrue()
        assertThat(result.switchEnabled).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Invariant guard: a stale gateError with persisted=VD must NOT bleed into rows 6–12.
    @Test
    fun `stale gate error is ignored once persisted has flipped to VD`() {
        val result = virtualDisplayCardState(
            persistedMode = PlatformMode.VIRTUAL_DISPLAY,
            effectiveMode = PlatformMode.VIRTUAL_DISPLAY,
            shizukuStatus = ShizukuStatus.Ready,
            gatePending = false,
            gateError = VirtualDisplayToggleError.ShizukuPermissionDenied,
        )
        // Falls through to row 10, not a Needs Setup row.
        assertThat(result.status.label).isEqualTo(R.string.ui_ready)
        assertThat(result.switchChecked).isTrue()
        assertThat(result.rowAction).isNull()
    }
}
