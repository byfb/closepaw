package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.uiResources

import ai.closepaw.R

import ai.closepaw.ui.theme.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun AppearanceSettingsPage(onBack: () -> Unit, onClose: () -> Unit) {
    val strings = uiResources()
    val context = LocalContext.current
    val store = remember(context) { AppearanceStore(context) }
    val appearance = rememberAppearance()
    Column(Modifier.fillMaxWidth()) {
        PageMastheadDrillDown(strings.getString(R.string.ui_appearance), onBack = onBack, onClose = onClose)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.closePaw.spacing.lg), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val language = AppLanguage.selected(context)
            val systemLabel = strings.getString(R.string.ui_follow_system)
            SettingsDropdown(label = strings.getString(R.string.ui_app_language), value = language.nativeName.ifEmpty { systemLabel },
                leadingIcon = androidx.compose.material.icons.Icons.Rounded.Language,
                options = AppLanguage.entries, isSelected = { it == language },
                onOptionSelected = { it.select(context) }, optionText = { Text(it.nativeName.ifEmpty { systemLabel }) })
            SettingsDropdown(label = strings.getString(R.string.ui_theme), value = strings.getString(appearance.theme.label), leadingIcon = Icons.Rounded.DarkMode,
                options = ThemeMode.entries, isSelected = { it == appearance.theme },
                onOptionSelected = { store.save(appearance.copy(theme = it)) }, optionText = { Text(strings.getString(it.label)) })
            SettingsDropdown(label = strings.getString(R.string.ui_font_size), value = strings.getString(appearance.fontSize.label), leadingIcon = Icons.Rounded.FormatSize,
                options = AppFontSize.entries, isSelected = { it == appearance.fontSize },
                onOptionSelected = { store.save(appearance.copy(fontSize = it)) }, optionText = { Text(strings.getString(it.label)) })
            Text(strings.getString(R.string.ui_larger_sizes_build_on_your_android_text_size), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("ClosePaw", style = MaterialTheme.typography.headlineMedium)
            Text(strings.getString(R.string.ui_your_phone_with_a_helping_hand), style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
        }
    }
}
