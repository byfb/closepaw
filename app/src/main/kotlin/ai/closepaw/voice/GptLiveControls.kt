package ai.closepaw.voice

import ai.closepaw.ui.capsule.voice.VoicePermissionDisposition
import ai.closepaw.ui.capsule.voice.rememberVoicePermissionGate
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CallEnd
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

/** Binding is Activity-scoped; the started microphone service survives app switches. */
@Composable
fun GptLiveControls(onRunningChanged: (Boolean) -> Unit, modifier: Modifier = Modifier, onOpenSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val activity = context as? Activity ?: return
    var service by remember { mutableStateOf<GptLiveService?>(null) }
    val idle = remember { MutableStateFlow(LiveVoiceState()) }
    val state by (service?.state ?: idle).collectAsStateWithLifecycle()
    val task by LiveVoiceRuntime.task.collectAsStateWithLifecycle()
    var permissionError by remember { mutableStateOf<String?>(null) }
    var notificationRequested by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val start: () -> Unit = {
        permissionError = null
        try {
            ContextCompat.startForegroundService(context,
                Intent(context, GptLiveService::class.java).setAction(GptLiveService.ACTION_START))
        } catch (_: Exception) {
            permissionError = "Open ClosePaw again to start voice."
        }
    }
    val gate = rememberVoicePermissionGate(activity) { granted ->
        if (granted) start() else permissionError = "Microphone permission is required."
    }
    DisposableEffect(context) {
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                service = (binder as? GptLiveService.LocalBinder)?.service
            }
            override fun onServiceDisconnected(name: ComponentName?) { service = null }
        }
        val bound = context.bindService(Intent(context, GptLiveService::class.java), connection, Context.BIND_AUTO_CREATE)
        onDispose { if (bound) context.unbindService(connection) }
    }
    LaunchedEffect(state.running) { onRunningChanged(state.running) }
    LaunchedEffect(state.phase) {
        if (state.phase == LiveVoicePhase.Active && !notificationRequested && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationRequested = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Column(modifier.fillMaxWidth()) {
        (permissionError ?: state.error)?.let {
            Text(it, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End) {
            if (state.running) {
                Text(voiceStatus(state, task),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.weight(1f).padding(start = 12.dp).testTag("qa-live-status"))
                if (state.phase == LiveVoicePhase.Active && !state.preview) {
                    IconButton(onClick = { service?.toggleMute() }, modifier = Modifier.size(48.dp).testTag("qa-live-mute")) {
                        Icon(if (state.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic,
                            contentDescription = if (state.muted) "Unmute GPT-Live" else "Mute GPT-Live")
                    }
                } else if (state.phase == LiveVoicePhase.Connecting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                IconButton(onClick = { service?.stopCall() }, modifier = Modifier.size(48.dp).testTag("qa-live-end")) {
                    Icon(Icons.Rounded.CallEnd, contentDescription = "End GPT-Live", tint = MaterialTheme.colorScheme.error)
                }
            } else {
                TextButton(onClick = {
                    when (gate.disposition()) {
                        VoicePermissionDisposition.Granted -> start()
                        VoicePermissionDisposition.Request -> gate.requestPermission()
                        VoicePermissionDisposition.OpenAppSettings -> {
                            permissionError = "Enable microphone access in Android Settings."
                            gate.openAppSettings()
                        }
                    }
                }, modifier = Modifier.heightIn(min = 48.dp).testTag("qa-live-start")) {
                    Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("GPT-Live")
                }
            }
            onOpenSettings?.let { open ->
                IconButton(onClick = open, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Rounded.Settings, contentDescription = "GPT-Live settings")
                }
            }
        }
        if (state.running && state.transcript.isNotBlank()) {
            Text(state.transcript, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp))
        }
    }
}
