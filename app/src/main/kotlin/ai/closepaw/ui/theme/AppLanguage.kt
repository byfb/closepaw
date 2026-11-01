package ai.closepaw.ui.theme

import android.app.LocaleManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.util.Locale

enum class AppLanguage(val tag: String, val nativeName: String) {
    SYSTEM("", ""), ENGLISH("en", "English"), TRADITIONAL("zh-Hant", "繁體中文"), SIMPLIFIED("zh-Hans", "简体中文");

    companion object {
        fun selected(context: Context): AppLanguage {
            val tag = if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(LocaleManager::class.java).applicationLocales[0]?.toLanguageTag()
            } else preferences(context).getString(KEY, null)
            if (tag.isNullOrEmpty()) return SYSTEM
            val locale = Locale.forLanguageTag(tag)
            if (locale.language == "zh") {
                val script = android.icu.util.ULocale.addLikelySubtags(android.icu.util.ULocale.forLocale(locale)).script
                return if (script == "Hant") TRADITIONAL else SIMPLIFIED
            }
            return ENGLISH
        }

        internal const val KEY = "app_language"
        internal fun preferences(context: Context): SharedPreferences =
            context.applicationContext.getSharedPreferences("agent_prefs", Context.MODE_PRIVATE)
    }

    fun select(context: Context) {
        if (Build.VERSION.SDK_INT >= 33) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else preferences(context).edit().putString(KEY, tag).apply()
        ai.closepaw.voice.GptLiveWidget.requestUpdate(context.applicationContext)
    }
}

/** API 31–32 have no framework app locale. Use the same resource configuration for
 * Compose windows, services and home-screen widgets without replacing their host Context. */
fun appResources(context: Context, configuration: Configuration = context.resources.configuration): Resources {
    val config = Configuration(configuration)
    if (Build.VERSION.SDK_INT < 33) {
        AppLanguage.selected(context).takeUnless { it == AppLanguage.SYSTEM }?.let {
            config.setLocale(Locale.forLanguageTag(it.tag))
        }
    }
    return context.createConfigurationContext(config).resources
}

private val LocalAppResources = compositionLocalOf<Resources?> { null }

@Composable
fun uiResources(): Resources = LocalAppResources.current ?: LocalContext.current.resources

@Composable
internal fun AppLanguageResources(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    var legacyTag by remember(context) { mutableStateOf("") }
    if (Build.VERSION.SDK_INT < 33) {
        DisposableEffect(context) {
            val prefs = AppLanguage.preferences(context)
            val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == AppLanguage.KEY) legacyTag = prefs.getString(key, "").orEmpty()
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            legacyTag = prefs.getString(AppLanguage.KEY, "").orEmpty()
            onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        }
    }
    val resources = remember(context, config, legacyTag) { appResources(context, config) }
    CompositionLocalProvider(LocalAppResources provides resources, content = content)
}
