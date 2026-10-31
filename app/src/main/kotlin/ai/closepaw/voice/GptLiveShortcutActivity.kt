package ai.closepaw.voice

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

    private var requested = false
    private var message by mutableStateOf("Starting GPT-Live…")
    private var failed by mutableStateOf(false)
    private val microphone = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice() else showSetup("Microphone access is required.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ClosePawTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Text(message, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(24.dp))
                        if (failed) Button(onClick = {
                            startActivity(GptLiveWidget.settingsIntent(this@GptLiveShortcutActivity)); finish()
                        }) { Text("Open voice settings") }
                        else CircularProgressIndicator()
                        TextButton(onClick = { finish() }) { Text("Close") }
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
                if (!signedIn) { showSetup("Sign in to ChatGPT to use GPT-Live."); return@launch }
                val accessibilityReady = withTimeoutOrNull(3000) {
                    while (ai.closepaw.app.AgentService.instance == null) delay(100)
                    true
                } == true
                if (!accessibilityReady) { showSetup("Enable ClosePaw Accessibility first."); return@launch }
                ContextCompat.startForegroundService(this@GptLiveShortcutActivity,
                    Intent(this@GptLiveShortcutActivity, GptLiveService::class.java).setAction(GptLiveService.ACTION_START))
                val result = withTimeoutOrNull(5000) {
                    LiveVoiceRuntime.state.first { it.running || it.phase == LiveVoicePhase.Error }
                }
                if (result?.running == true) finish()
                else showSetup(result?.error ?: "Could not start GPT-Live.")
            } catch (_: Exception) { showSetup("Could not start GPT-Live. Open voice settings to retry.") }
        }
    }

    private fun showSetup(text: String) { message = text; failed = true }
}
