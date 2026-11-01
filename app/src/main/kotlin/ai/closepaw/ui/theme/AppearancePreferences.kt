package ai.closepaw.ui.theme

import ai.closepaw.R

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode(@androidx.annotation.StringRes val label: Int) {
    SYSTEM(R.string.ui_follow_system), LIGHT(R.string.ui_light), DARK(R.string.ui_dark)
}

enum class AppFontSize(@androidx.annotation.StringRes val label: Int, val scale: Float) {
    SYSTEM(R.string.ui_system_size, 1f), LARGE(R.string.ui_large_115, 1.15f), EXTRA_LARGE(R.string.ui_extra_large_130, 1.3f)
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
