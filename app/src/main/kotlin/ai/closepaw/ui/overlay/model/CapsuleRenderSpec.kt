package ai.closepaw.ui.overlay.model

import ai.closepaw.R

import ai.closepaw.protocol.PlatformMode

/**
 * CapsuleRenderSpec — pure rendering specification derived from CapsuleMode.
 *
 * Maps CapsuleMode → visual properties. Both overlay and in-app Compose renderers
 * read from this spec. This is the SINGLE source of truth for "what does the
 * capsule look like in each mode."
 *
 * No business logic. No callbacks. No state management.
 * Just data that renderers mechanically apply to their UI framework.
 */
data class CapsuleRenderSpec(
    val dot: DotSpec?,
    val thought: ThoughtSpec,
    val expandedBody: String?,
    val buttons: ButtonsSpec,
    val input: InputSpec?,
) {
    /** Status dot configuration. null = dot hidden. */
    data class DotSpec(val status: GlowState, val pulsing: Boolean)

    /** Status-line text (the agent's thought). */
    data class ThoughtSpec(val text: String, val dimmed: Boolean = false)

    /** A single button in the control bar. The Compose layer chooses the icon. */
    data class ButtonSpec(val text: String, val enabled: Boolean = true)

    enum class ControlLayout {
        SharedWithNav,
        RequiredActionsOnly,
    }

    /** Control-bar button configuration. null fields = button hidden. */
    data class ButtonsSpec(
        val primary: ButtonSpec?,
        val secondary: ButtonSpec? = null,
        val stop: ButtonSpec?,
        val layout: ControlLayout = ControlLayout.SharedWithNav,
    )

    /** Input-bar specification. null = input bar hidden. */
    data class InputSpec(
        val hint: String,
        val submitLabel: String,
        val clearDraft: Boolean = false,
    )

    companion object {
        /**
         * Derive the render spec from a CapsuleMode.
         * [previousMode] is used to decide whether to clear the input-bar draft
         * on transitions into WaitingForInput.
         */
        fun from(
            strings: android.content.res.Resources,
            mode: CapsuleMode,
            previousMode: CapsuleMode? = null,
            isStopPending: Boolean = false,
        ): CapsuleRenderSpec =
            when (mode) {
                is CapsuleMode.Running -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Active, pulsing = true),
                    thought = ThoughtSpec(mode.thought.ifEmpty { strings.getString(R.string.ui_thinking_status) }),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(strings.getString(R.string.ui_takeover)),
                        stop = stopButtonSpec(strings, isStopPending),
                    ),
                    input = InputSpec(strings.getString(R.string.ui_got_ideas_add_a_note), strings.getString(R.string.ui_add_note)),
                )

                is CapsuleMode.TakeoverPending -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Paused, pulsing = false),
                    thought = ThoughtSpec(strings.getString(R.string.ui_takeover_pending)),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(strings.getString(R.string.ui_takeover_pending_action), enabled = false),
                        stop = stopButtonSpec(strings, isStopPending),
                    ),
                    input = InputSpec(strings.getString(R.string.ui_got_ideas_add_a_note), strings.getString(R.string.ui_add_note)),
                )

                is CapsuleMode.Takeover -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Paused, pulsing = false),
                    thought = ThoughtSpec(
                        mode.lastThought.ifEmpty { strings.getString(R.string.ui_paused) },
                        dimmed = true,
                    ),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(strings.getString(R.string.ui_resume)),
                        stop = stopButtonSpec(strings, isStopPending),
                    ),
                    input = InputSpec(strings.getString(R.string.ui_got_ideas_add_a_note), strings.getString(R.string.ui_add_note)),
                )

                is CapsuleMode.WaitingForInput -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec(strings.getString(R.string.ui_awaiting_response)),
                    expandedBody = mode.question,
                    buttons = ButtonsSpec(
                        primary = null,
                        stop = stopButtonSpec(strings, isStopPending),
                        layout = ControlLayout.RequiredActionsOnly,
                    ),
                    input = InputSpec(
                        hint = strings.getString(R.string.ui_type_your_response),
                        submitLabel = strings.getString(R.string.ui_send),
                        clearDraft = previousMode != null
                            && previousMode !is CapsuleMode.WaitingForInput,
                    ),
                )

                is CapsuleMode.WaitingForAction -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec(strings.getString(R.string.ui_action_needed)),
                    expandedBody = mode.instruction,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(strings.getString(R.string.ui_done_action)),
                        stop = stopButtonSpec(strings, isStopPending),
                        layout = ControlLayout.RequiredActionsOnly,
                    ),
                    input = null,
                )

                is CapsuleMode.WaitingForApproval -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Paused, pulsing = false),
                    thought = ThoughtSpec(strings.getString(R.string.ui_allow_closepaw_to_operate, mode.appLabel)),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = ButtonSpec(strings.getString(R.string.ui_always)),
                        secondary = ButtonSpec(strings.getString(R.string.ui_session)),
                        stop = ButtonSpec(strings.getString(R.string.ui_reject)),
                        layout = ControlLayout.RequiredActionsOnly,
                    ),
                    input = null,
                )

                is CapsuleMode.Done -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Success, pulsing = false),
                    thought = ThoughtSpec(mode.message),
                    expandedBody = null,
                    buttons = ButtonsSpec(primary = null, stop = null),
                    input = null,
                )

                is CapsuleMode.Error -> CapsuleRenderSpec(
                    dot = DotSpec(GlowState.Error, pulsing = false),
                    thought = ThoughtSpec(mode.message),
                    expandedBody = null,
                    buttons = ButtonsSpec(
                        primary = null,
                        stop = ButtonSpec(strings.getString(R.string.ui_close)),
                        layout = ControlLayout.RequiredActionsOnly,
                    ),
                    input = null,
                )

                is CapsuleMode.Hidden -> CapsuleRenderSpec(
                    dot = null,
                    thought = ThoughtSpec(""),
                    expandedBody = null,
                    buttons = ButtonsSpec(primary = null, stop = null),
                    input = InputSpec(strings.getString(R.string.ui_what_can_i_help_you_with), strings.getString(R.string.ui_send)),
                )
            }

        private fun stopButtonSpec(strings: android.content.res.Resources, isStopPending: Boolean): ButtonSpec =
            if (isStopPending) ButtonSpec(strings.getString(R.string.ui_stopping), enabled = false)
            else ButtonSpec(strings.getString(R.string.ui_stop))
    }
}

/**
 * NavSpec — navigation button visibility derived from context.
 *
 * Separate from CapsuleRenderSpec because nav visibility depends on
 * CapsuleContext + PlatformMode, not CapsuleMode.
 */
data class NavSpec(
    val showMinimize: Boolean,
    val showApp: Boolean,
    val showWatch: Boolean,
) {
    companion object {
        fun from(
            context: CapsuleContext,
            platformMode: PlatformMode,
            hasIsland: Boolean,
            mode: CapsuleMode? = null,
        ): NavSpec {
            // Done mode hides the entire control bar, so its nav cluster must also hide.
            val controlBarHidden = mode is CapsuleMode.Done

            return NavSpec(
                showMinimize = !controlBarHidden
                    && hasIsland
                    && context != CapsuleContext.MAIN_APP
                    && mode !is CapsuleMode.WaitingForInput
                    && mode !is CapsuleMode.WaitingForAction
                    && mode !is CapsuleMode.WaitingForApproval
                    && mode !is CapsuleMode.Error,
                showApp = !controlBarHidden
                    && context != CapsuleContext.MAIN_APP
                    && platformMode != PlatformMode.ACCESSIBILITY,
                showWatch = !controlBarHidden
                    && platformMode != PlatformMode.ACCESSIBILITY
                    && context != CapsuleContext.SCREEN_VIEWING,
            )
        }
    }
}
