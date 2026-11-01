package ai.closepaw.ui.onboarding

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import ai.closepaw.onboarding.PermissionStateMonitor.PermissionRepairModel
import ai.closepaw.ui.theme.closePaw

/**
 * PermissionRepairCard — floating advisory shown above the input bar when a
 * permission required for autonomous operation is revoked or never granted.
 *
 * Renders as a thin paper-toned card (44dp per row) with an inline Rust dot,
 * a brief sentence, and an outlined Fix button. Caller is responsible for
 * positioning it above the input bar (typical: as a Column entry inside
 * Scaffold's bottomBar, above SmartCapsuleSurface). One row per missing
 * permission so multiple issues stack visually.
 */
@Composable
fun PermissionRepairCard(
    model: PermissionRepairModel,
    onFixAccessibility: () -> Unit,
    onFixOverlay: () -> Unit,
    onFixBattery: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = uiResources()
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 2.dp,
        shadowElevation = 2.dp,
    ) {
        Column {
            if (model.accessibilityMissing) {
                RepairRow(
                    text = strings.getString(R.string.ui_accessibility_disabled),
                    detail = strings.getString(R.string.ui_agent_cannot_automate),
                    onFix = onFixAccessibility,
                )
            }
            if (model.overlayMissing) {
                RepairRow(
                    text = strings.getString(R.string.ui_overlay_revoked),
                    detail = strings.getString(R.string.ui_capsule_controls_hidden_during_tasks),
                    onFix = onFixOverlay,
                )
            }
            if (model.batteryMissing) {
                RepairRow(
                    text = strings.getString(R.string.ui_battery_optimization_on),
                    detail = strings.getString(R.string.ui_long_tasks_may_stop),
                    onFix = onFixBattery,
                )
            }
        }
    }
}

@Composable
private fun RepairRow(
    text: String,
    detail: String,
    onFix: () -> Unit,
) {
    val strings = uiResources()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(MaterialTheme.colorScheme.error, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.closePaw.inkFaint,
            )
        }
        OutlinedButton(
            onClick = onFix,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 12.dp,
                vertical = 0.dp,
            ),
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .height(28.dp)
                .semantics { contentDescription = strings.getString(R.string.ui_fix, text) },
        ) {
            Text(
                text = strings.getString(R.string.ui_fix_button),
                style = MaterialTheme.closePaw.monoSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}
