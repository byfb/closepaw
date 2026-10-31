package ai.closepaw.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(val label: String) {
    SYSTEM("Follow system"), LIGHT("Light"), DARK("Dark")
}

enum class AppFontSize(val label: String, val scale: Float) {
    SYSTEM("System size", 1f), LARGE("Large · 115%", 1.15f), EXTRA_LARGE("Extra large · 130%", 1.3f)
}

data class AppearancePreferences(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val fontSize: AppFontSize = AppFontSize.SYSTEM,
)

class AppearanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)

    fun load() = AppearancePreferences(
        theme = ThemeMode.entries.firstOrNull { it.name == prefs.getString("theme_mode", null) } ?: ThemeMode.SYSTEM,
        fontSize = AppFontSize.entries.firstOrNull { it.name == prefs.getString("font_size", null) } ?: AppFontSize.SYSTEM,
    )

    fun save(value: AppearancePreferences) {
        prefs.edit().putString("theme_mode", value.theme.name).putString("font_size", value.fontSize.name).apply()
    }

    fun observe(onChanged: () -> Unit): () -> Unit {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "theme_mode" || key == "font_size") onChanged()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        return { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
}

@Composable
fun rememberAppearance(): AppearancePreferences {
    val context = LocalContext.current.applicationContext
    val store = remember(context) { AppearanceStore(context) }
    var value by remember(store) { mutableStateOf(store.load()) }
    DisposableEffect(store) {
        val unsubscribe = store.observe { value = store.load() }
        value = store.load()
        onDispose(unsubscribe)
    }
    return value
}
