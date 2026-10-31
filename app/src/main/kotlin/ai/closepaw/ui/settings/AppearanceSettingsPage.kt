package ai.closepaw.ui.settings

import ai.closepaw.ui.theme.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
internal fun AppearanceSettingsPage(onBack: () -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val store = remember(context) { AppearanceStore(context) }
    val appearance = rememberAppearance()
    Column(Modifier.fillMaxWidth()) {
        PageMastheadDrillDown("Appearance", onBack = onBack, onClose = onClose)
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
            .padding(horizontal = MaterialTheme.closePaw.spacing.lg), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            SettingsDropdown(label = "Theme", value = appearance.theme.label, leadingIcon = Icons.Rounded.DarkMode,
                options = ThemeMode.entries, isSelected = { it == appearance.theme },
                onOptionSelected = { store.save(appearance.copy(theme = it)) }, optionText = { Text(it.label) })
            SettingsDropdown(label = "Font size", value = appearance.fontSize.label, leadingIcon = Icons.Rounded.FormatSize,
                options = AppFontSize.entries, isSelected = { it == appearance.fontSize },
                onOptionSelected = { store.save(appearance.copy(fontSize = it)) }, optionText = { Text(it.label) })
            Text("Larger sizes build on your Android text size.", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("ClosePaw", style = MaterialTheme.typography.headlineMedium)
            Text("Your phone, with a helping hand.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(16.dp))
        }
    }
}
