package ai.closepaw.voice

import ai.closepaw.R

import ai.closepaw.ui.theme.ClosePawTheme
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** A visible entry point keeps microphone starts compatible with Android's foreground rules. */
class GptLiveShortcutActivity : ComponentActivity() {
    companion object {
        fun startIntent(context: Context) = Intent(context, GptLiveShortcutActivity::class.java)
            .setAction(GptLiveService.ACTION_START).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    private val strings get() = ai.closepaw.ui.theme.appResources(this)
    private var requested = false
    private var message by mutableStateOf<String?>(null)
    private var failed by mutableStateOf(false)
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else showSetup(strings.getString(R.string.ui_microphone_access_is_required))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClosePawTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(message ?: strings.getString(R.string.ui_starting_gpt_live), style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(24.dp))
                        if (failed) Button(onClick = {
                            startActivity(GptLiveWidget.settingsIntent(this@GptLiveShortcutActivity)); finish()
                        }) { Text(strings.getString(R.string.ui_open_voice_settings)) }
                        else CircularProgressIndicator()
                        TextButton(onClick = { finish() }) { Text(strings.getString(R.string.ui_close)) }
                    }
                }
            }
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        if (requested) return
        requested = true
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphone.launch(Manifest.permission.RECORD_AUDIO)
        } else startVoice()
    }

    private fun startVoice() {
        if (LiveVoiceRuntime.state.value.running) { finish(); return }
        lifecycleScope.launch {
            try {
                val signedIn = withContext(Dispatchers.IO) {
                    ai.closepaw.app.AuthStoreHolder.get(applicationContext).has(ai.closepaw.llm.LLMProvider.OPENAI_CODEX)
                }
                if (!signedIn) { showSetup(strings.getString(R.string.ui_sign_in_to_chatgpt_to_use_gpt_live)); return@launch }
                val accessibilityReady = withTimeoutOrNull(3000) {
                    while (ai.closepaw.app.AgentService.instance == null) delay(100)
                    true
                } == true
                if (!accessibilityReady) { showSetup(strings.getString(R.string.ui_enable_closepaw_accessibility_first)); return@launch }
                ContextCompat.startForegroundService(this@GptLiveShortcutActivity,
                    Intent(this@GptLiveShortcutActivity, GptLiveService::class.java).setAction(GptLiveService.ACTION_START))
                val result = withTimeoutOrNull(5000) {
                    LiveVoiceRuntime.state.first { it.running || it.phase == LiveVoicePhase.Error }
                }
                if (result?.running == true) finish()
                else showSetup(result?.error ?: strings.getString(R.string.ui_could_not_start_gpt_live))
            } catch (_: Exception) { showSetup(strings.getString(R.string.ui_could_not_start_gpt_live_open_voice_settings_to_retry)) }
        }
    }

    private fun showSetup(text: String) { message = text; failed = true }
}
