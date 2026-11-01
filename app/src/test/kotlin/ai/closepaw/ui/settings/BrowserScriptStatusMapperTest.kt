package ai.closepaw.ui.settings

import ai.closepaw.R

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Covers every row of the browser_script mapping table. The mapper resolves four
 * observable inputs (enable pref, gate pending, gate error, CDP probe) into a single
 * [ToolStatusUi] + optional [RowAction]; mis-ordering the precedence (e.g. showing the
 * probe result while a gate error is live) is exactly the regression these tests guard.
 *
 * These tests are the source of truth for label wording.
 */
class BrowserScriptStatusMapperTest {

    // Row 1: Off, no error → R.string.ui_disabled
    @Test
    fun `pref off with no error and no pending maps to Disabled`() {
        val result = browserScriptStatusUi(
            enabledPref = false,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_disabled)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_toggle_to_enable)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Row 2: Pending (gate running) → R.string.ui_setting_up
    @Test
    fun `gate pending wins over pref off and probe state — Setting up with spinner`() {
        val result = browserScriptStatusUi(
            enabledPref = false,
            gatePending = true,
            gateError = null,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_setting_up)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_checking_shizuku)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 3: Gate error (any variant) → R.string.ui_needs_setup + ClearErrorAndRetry row action
    @Test
    fun `gate error ShizukuUnavailable maps to Needs Setup with verbatim message subtitle`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = BrowserScriptToggleError.ShizukuUnavailable,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_needs_setup)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_browser_script_needs_shizuku
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }

    @Test
    fun `gate error ShizukuPermissionDenied maps to Needs Setup with retry action`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = BrowserScriptToggleError.ShizukuPermissionDenied,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_needs_setup)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_permission_denied_tap_to_retry_or_re_grant_in_shizuku_manager
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }

    @Test
    fun `gate error WriteFailed maps to Needs Setup with retry action`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = BrowserScriptToggleError.WriteFailed,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_needs_setup)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_chrome_command_file_error
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }

    // Row 4: On, probe Probing → R.string.ui_status_checking
    @Test
    fun `pref on with probe Probing maps to Checking with spinner`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Probing,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_status_checking)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_checking_chrome_devtools_socket)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Neutral)
        assertThat(result.status.showSpinner).isTrue()
        assertThat(result.rowAction).isNull()
    }

    // Row 5: On, probe Bound → R.string.ui_ready
    @Test
    fun `pref on with probe Bound maps to Ready Positive`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_ready)
        assertThat(result.status.subtitle).isEqualTo(R.string.ui_browser_script_active)
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Positive)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Row 6: On, probe NotBound → R.string.ui_needs_setup (expanded slot owns the CTA, NOT row tap)
    @Test
    fun `pref on with probe NotBound maps to Needs Setup with no row action`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.NotBound,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_needs_setup)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_chrome_enable_flag
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        // NotBound's CTA lives in the expanded slot, not on row tap — see design doc decision 3.
        assertThat(result.rowAction).isNull()
    }

    // Row 7: On, probe Unknown → R.string.ui_not_available
    @Test
    fun `pref on with probe Unknown maps to Not available Warning`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = false,
            gateError = null,
            probeResult = BrowserScriptProbeState.Unknown,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_not_available)
        assertThat(result.status.subtitle).isEqualTo(
            R.string.ui_chrome_probe_unavailable
        )
        assertThat(result.status.tone).isEqualTo(ToolStatusTone.Warning)
        assertThat(result.status.showSpinner).isFalse()
        assertThat(result.rowAction).isNull()
    }

    // Precedence — the mapper's branch order is `pending > error > !enabledPref > probe`.
    // The design doc's table doesn't enumerate combinations; pinning current behavior so a
    // re-order shows up here rather than only in production.

    @Test
    fun `gate pending wins over gate error`() {
        val result = browserScriptStatusUi(
            enabledPref = true,
            gatePending = true,
            gateError = BrowserScriptToggleError.WriteFailed,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_setting_up)
        assertThat(result.rowAction).isNull()
    }

    @Test
    fun `gate error wins over pref off`() {
        val result = browserScriptStatusUi(
            enabledPref = false,
            gatePending = false,
            gateError = BrowserScriptToggleError.ShizukuUnavailable,
            probeResult = BrowserScriptProbeState.Bound,
        )
        assertThat(result.status.label).isEqualTo(R.string.ui_needs_setup)
        assertThat(result.rowAction).isEqualTo(RowAction.ClearErrorAndRetry)
    }
}
