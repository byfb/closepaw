package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import androidx.compose.foundation.layout.Column
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable

@Composable
internal fun CloudModelDropdown(
    selectedModel: String,
    modelOptions: List<Pair<String, String>>,
    onModelChange: (String) -> Unit
) {
    val strings = uiResources()
    val matched = modelOptions.find { it.first == selectedModel }
    val selectedDisplayName = matched?.second ?: strings.getString(R.string.ui_select_a_model)

    SettingsDropdown(
        label = strings.getString(R.string.ui_model),
        value = selectedDisplayName,
        leadingIcon = Icons.Outlined.Psychology,
        options = modelOptions,
        isSelected = { (modelId, _) -> modelId == selectedModel },
        onOptionSelected = { (modelId, _) -> onModelChange(modelId) },
        optionText = { (_, displayName) -> Text(displayName) },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}

@Composable
internal fun LocalModelDropdown(
    selectedModelId: String,
    onModelChange: (LocalModelOption) -> Unit
) {
    val strings = uiResources()
    val selectedModel = AVAILABLE_LOCAL_MODELS.find { it.id == selectedModelId }
        ?: AVAILABLE_LOCAL_MODELS.first()

    SettingsDropdown(
        label = strings.getString(R.string.ui_local_model),
        value = selectedModel.displayName,
        leadingIcon = Icons.Outlined.Memory,
        options = AVAILABLE_LOCAL_MODELS,
        isSelected = { model -> model.id == selectedModelId },
        onOptionSelected = onModelChange,
        optionText = { model ->
            Column {
                Text(model.displayName)
                Text(
                    text = model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        optionLeadingIcon = { _, selected ->
            if (selected) { { DropdownSelectedIndicator() } } else null
        }
    )
}
