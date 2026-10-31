package ai.closepaw.ui.settings

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
    if (entry == null) return
    val supported = ModelReasoning.options(entry)
    if (supported.isEmpty()) return
    val selected = ModelReasoning.effectiveEffort(entry, selectedEffort)
    SettingsDropdown(
        label = "Reasoning effort",
        value = effortLabel(selected),
        leadingIcon = Icons.Outlined.Psychology,
        options = listOf<String?>(null) + supported,
        isSelected = { it == selected },
        onOptionSelected = onSelect,
        optionText = { Text(effortLabel(it)) },
        optionLeadingIcon = { _, isSelected ->
            if (isSelected) { { DropdownSelectedIndicator() } } else null
        },
    )
    Spacer(Modifier.height(20.dp))
}

private fun effortLabel(effort: String?): String = when (effort) {
    null -> "Default"
    "xhigh" -> "Extra high"
    else -> effort.replaceFirstChar { it.uppercase() }
}
