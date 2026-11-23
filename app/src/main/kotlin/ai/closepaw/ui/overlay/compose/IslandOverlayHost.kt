package ai.closepaw.ui.overlay.compose

import ai.closepaw.R

import ai.closepaw.ui.theme.uiResources

import android.accessibilityservice.AccessibilityService
import android.graphics.PixelFormat
import android.content.Intent
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.LifecycleOwner
import androidx.savedstate.SavedStateRegistryOwner
import ai.closepaw.platform.OverlayTouchGate
import ai.closepaw.voice.GptLiveService
import ai.closepaw.voice.LiveVoiceRuntime
import ai.closepaw.voice.voiceStatus
import ai.closepaw.voice.LiveStatus
import ai.closepaw.voice.LiveTaskStatus
import ai.closepaw.voice.liveStatus
import androidx.compose.ui.res.colorResource
import ai.closepaw.ui.overlay.CapsuleStateHolder
import ai.closepaw.ui.overlay.model.CapsuleMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

class IslandOverlayHost(
    private val service: AccessibilityService,
    lifecycleOwner: LifecycleOwner,
    savedStateRegistryOwner: SavedStateRegistryOwner,
    private val onExpandCapsule: () -> Unit,
) {
    companion object {
        private const val TAG = "IslandOverlayHost"
    }

    private val windowManager = service.getSystemService(WindowManager::class.java)
    private val composeHost = OverlayComposeHost(
        context = service,
        lifecycleOwner = lifecycleOwner,
        savedStateRegistryOwner = savedStateRegistryOwner,
        windowManager = windowManager,
        tag = TAG,
    )

    private var stateHolder: CapsuleStateHolder? = null
    private val expanded = MutableStateFlow(false)
    var onMinimizeSummary: (() -> Unit)? = null
    var onOpenApp: (() -> Unit)? = null
    var onOpenControls: (() -> Unit)? = null
    private var passThroughDepth = 0

    val touchGate: OverlayTouchGate = object : OverlayTouchGate {
        override fun beginGesturePassThrough(): AutoCloseable {
            passThroughDepth++
            updateTouchability()
            var closed = false
            return AutoCloseable {
                if (!closed) {
                    closed = true
                    passThroughDepth = (passThroughDepth - 1).coerceAtLeast(0)
                    updateTouchability()
                }
            }
        }
    }

    fun setExpanded(value: Boolean) {
        if (expanded.value == value) return
        expanded.value = value
        composeHost.updateLayoutParams { params ->
            params.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            params.x = 0
            params.width = if (value) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT
        }
    }

    fun show() {
        if (composeHost.isShowing()) return
        composeHost.show(createLayoutParams()) {
            val holder = stateHolder
            if (holder == null) {
                StatusIslandCompose(
                    text = uiResources().getString(R.string.ui_worker_progress),
                    color = colorResource(LiveStatus.WORKING.background),
                    ink = colorResource(LiveStatus.WORKING.ink),
                    onClick = onExpandCapsule,
                )
            } else {
                val mode by holder.mode.collectAsState(initial = CapsuleMode.Hidden)
                val isExpanded by expanded.collectAsState()
                val voice by LiveVoiceRuntime.state.collectAsState()
                val task by LiveVoiceRuntime.task.collectAsState()
                val strings = uiResources()
                val text = if (mode is CapsuleMode.Hidden) voiceStatus(strings, voice, task) else modeText(mode, strings)
                val status = when (mode) {
                    is CapsuleMode.Hidden -> liveStatus(voice, task)
                    is CapsuleMode.Running -> LiveStatus.WORKING
                    is CapsuleMode.Done -> when (task) {
                        LiveTaskStatus.FAILED -> LiveStatus.FAILED
                        LiveTaskStatus.STOPPED -> LiveStatus.STOPPED
                        else -> LiveStatus.DONE
                    }
                    is CapsuleMode.Error -> LiveStatus.FAILED
                    else -> LiveStatus.WAITING
                }
                if (isExpanded) FloatingSummaryCompose(
                    mode = mode,
                    voice = voice,
                    task = task,
                    onMinimize = { onMinimizeSummary?.invoke() },
                    onOpenChat = { onOpenApp?.invoke() },
                    onOpenControls = { onOpenControls?.invoke() },
                    onMute = { voiceControl(GptLiveService.ACTION_MUTE) },
                    onEndVoice = { voiceControl(GptLiveService.ACTION_STOP) },
                ) else StatusIslandCompose(
                    text = if (text.isBlank()) uiResources().getString(R.string.ui_worker_progress) else text,
                    color = colorResource(status.background),
                    ink = colorResource(status.ink),
                    onClick = onExpandCapsule,
                )
            }
        }
        Log.i(TAG, "Status island shown")
    }

    fun hide() {
        composeHost.hide()
    }

    fun isShowing(): Boolean = composeHost.isShowing()

    fun startObserving(stateHolder: CapsuleStateHolder, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        this.stateHolder = stateHolder
    }

    fun dispose() {
        hide()
        composeHost.dispose()
    }

    private fun voiceControl(action: String) {
        if (!LiveVoiceRuntime.state.value.running) return
        runCatching { service.startService(Intent(service, GptLiveService::class.java).setAction(action)) }
    }

    private fun updateTouchability() {
        composeHost.updateLayoutParams { params ->
            params.flags = if (passThroughDepth > 0) params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            else params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
    }

    private fun modeText(mode: CapsuleMode, strings: android.content.res.Resources): String = when (mode) {
        is CapsuleMode.Running -> mode.thought
        is CapsuleMode.TakeoverPending -> strings.getString(R.string.ui_takeover_pending)
        is CapsuleMode.Takeover -> strings.getString(R.string.ui_paused)
        is CapsuleMode.WaitingForInput -> strings.getString(R.string.ui_awaiting_response)
        is CapsuleMode.WaitingForAction -> strings.getString(R.string.ui_action_needed)
        is CapsuleMode.WaitingForApproval -> strings.getString(R.string.ui_allow_closepaw_to_operate, mode.appLabel)
        is CapsuleMode.Done -> strings.getString(R.string.ui_task_done_summary, mode.message.take(18))
        is CapsuleMode.Error -> strings.getString(R.string.ui_task_error_summary, mode.message.take(18))
        is CapsuleMode.Hidden -> ""
    }

    private fun createLayoutParams(): WindowManager.LayoutParams {
        val statusBarHeight = getStatusBarHeight()
        return WindowManager.LayoutParams(
            if (expanded.value) WindowManager.LayoutParams.MATCH_PARENT else WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                (if (passThroughDepth > 0) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0),
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = statusBarHeight + dp(4)
        }
    }

    private fun getStatusBarHeight(): Int {
        val resId = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) service.resources.getDimensionPixelSize(resId) else dp(24)
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            service.resources.displayMetrics
        ).toInt()
    }
}
