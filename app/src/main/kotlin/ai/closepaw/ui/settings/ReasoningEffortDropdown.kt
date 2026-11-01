package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import ai.closepaw.llm.ModelEntry
import ai.closepaw.llm.ModelReasoning
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun ReasoningEffortDropdown(
    entry: ModelEntry?,
    selectedEffort: String?,
    onSelect: (String?) -> Unit,
) {
    val strings = uiResources()
    if (entry == null) return
    val supported = ModelReasoning.options(entry)
    if (supported.isEmpty()) return
    val selected = ModelReasoning.effectiveEffort(entry, selectedEffort)
    SettingsDropdown(
        label = strings.getString(R.string.ui_reasoning_effort),
        value = effortLabel(selected, strings),
        leadingIcon = Icons.Outlined.Psychology,
        options = listOf<String?>(null) + supported,
        isSelected = { it == selected },
        onOptionSelected = onSelect,
        optionText = { Text(effortLabel(it, strings)) },
        optionLeadingIcon = { _, isSelected ->
            if (isSelected) { { DropdownSelectedIndicator() } } else null
        },
    )
    Spacer(Modifier.height(20.dp))
}

internal fun effortLabel(effort: String?, strings: android.content.res.Resources): String = when (effort) {
    null -> strings.getString(R.string.ui_default)
    "xhigh" -> strings.getString(R.string.ui_extra_high)
    "low" -> strings.getString(R.string.ui_low)
    "medium" -> strings.getString(R.string.ui_medium)
    "high" -> strings.getString(R.string.ui_high)
    "minimal" -> strings.getString(R.string.ui_minimal)
    else -> effort.replaceFirstChar { it.uppercase() }
}
