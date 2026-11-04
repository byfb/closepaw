# GPT-Live voice

ClosePaw can establish a GPT-Live WebRTC call using the ChatGPT account signed in
on the Android device. Tap **GPT-Live** above the chat input, grant microphone
access, and speak. The live model delegates phone tasks to the normal ClosePaw
agent and speaks its questions and results. Mute and end-call controls remain in
chat; the microphone foreground notification also offers **End voice** when
notification permission is granted.

While another app is in front, a compact status pill appears by default. Tap it
to open a floating summary with the latest task progress and live captions. The
expanded card fills the available width with 12 dp side margins and respects
display cutouts. **Controls** opens takeover, stop, and response controls; **Open
chat** returns to the conversation. The arrow folds the card again. Task updates,
questions, and approval requests do not expand a folded overlay; tap it to view
and respond. Every new task starts folded. Voice-only calls remain available
without an active task; previews do not create a floating window.

## Home-screen widget and settings

Open **Settings → GPT-Live** (or the settings icon beside the chat's voice button).
**Add voice widget** opens the launcher's widget confirmation. The same widget
is also available from the launcher's Widgets menu.

**Add voice shortcut** pins a single start action. Long-pressing the ClosePaw app
icon also exposes **Start GPT-Live**. The shortcut uses a visible Activity to
request microphone permission and start the foreground service, then returns to
the previous screen. Repeated taps do not create another call.

**Add Quick Settings tile** (Android 13+) requests the system tile. On Android 12,
add GPT-Live using Quick Settings' Edit menu. Tap the tile to start voice, or tap
again to end it. Starting from a locked device first requests unlock. Tile state
updates while the panel is visible; it does not poll or open the microphone in
background. Android 14+ uses the PendingIntent tile launch API.

After ChatGPT sign-in, microphone permission, and Accessibility setup, **Start
voice** starts the existing foreground service directly from the widget. A widget
tap is an explicit user action; no timer or background task opens the microphone.
Incomplete setup opens the GPT-Live settings page. If a cached widget action is
used after permission or sign-in changes, the service rechecks prerequisites and
refreshes the widget; the next tap opens setup. The service does not launch an
Activity from the background.

The widget displays connection, microphone, playback, and worker states. **End
voice** releases the call and leaves delegated work running. Worker completion,
failure, or cancellation continues to update the widget from `AgentService`;
tapping the status opens the app and its recorded results. Widgets contain no
transcripts, task text, credentials, or captured screen data. Runtime status is
in memory and resets after process death; saved task results remain in history.

Voice preferences are shared by the app and widget:

| Setting | Choices |
| --- | --- |
| Voice | Cove (default), Juniper, Maple, Spruce, Ember, Vale, Breeze, Arbor, Sol |
| Reply language | Follow conversation, Hong Kong Cantonese, Mandarin, English |
| Tone | Natural, Calm, Lively |

Preferences are snapshotted for each new voice session. Language and tone become
instructions; they do not configure a separate transcription engine or a numeric
speed control. Changing them does not reconnect an active call or rebuild the
worker. Voice IDs come from the official Codex v3 catalog. Saved Marin/Cedar
selections from older ClosePaw versions resolve to Cove, preserving language and
tone. Those public Realtime v2 voices can return HTTP 201 on the Codex route
without ever completing the media handshake. A rejected configuration is shown
as an error without retrying another voice.

**Preview voice** starts a separate WebRTC session with the selected
preferences and a short sample. It negotiates duplex audio, which Codex v3 needs
to play speakable context, while disabling Android recording and using the SDK's
synthetic silence path. It creates no microphone track, never dispatches
delegations, and cannot start during a live conversation. Stop it explicitly,
leave the settings page, or let its 15-second playback window expire. A completed
transcript does not end playback. Speaking status comes from native playback PCM
activity; audio samples are inspected for energy and discarded immediately.

**Model & reasoning effort** opens the existing ChatGPT model settings. Voice
preferences and worker choices are saved independently. A running worker keeps
its configuration; model/effort changes apply when a new chat session is created.
The widget uses the same worker session path as the app.

## Authentication and availability

The app uses `AuthStore.codexHeaders(OPENAI_CODEX)`, including the existing OAuth
refresh path. Call creation sends the phone's access token and ChatGPT account ID
directly to:

```text
POST https://chatgpt.com/backend-api/codex/realtime/calls?intent=quicksilver&architecture=avas
OpenAI-Alpha: quicksilver=v2
```

The session selects `gpt-live-1-codex` with client delegation. No desktop process,
remote app-server, or API key participates. The bearer is used only on the HTTPS
call-creation request; audio and task updates use the WebRTC peer connection.
Redirects are disabled. Tokens, SDP, and raw error responses are not logged by
the voice implementation.

This is an experimental ChatGPT/Codex route, distinct from the public GPT-Live
API. Account entitlement and backend acceptance must be verified with an actual
phone-side sign-in. A successful local build or loopback test does not establish
account access. HTTP authentication, availability, and quota failures are shown
in chat, without switching to API-key billing.

The request and v3 event shapes follow the official Codex source at
[`a795f961`](https://github.com/openai/codex/tree/a795f961be68383738e56ac396742b3e8451696a):

- [`realtime_call.rs`](https://github.com/openai/codex/blob/a795f961be68383738e56ac396742b3e8451696a/codex-rs/codex-api/src/endpoint/realtime_call.rs)
- [`methods_frameless_bidi.rs`](https://github.com/openai/codex/blob/a795f961be68383738e56ac396742b3e8451696a/codex-rs/codex-api/src/endpoint/realtime_websocket/methods_frameless_bidi.rs)
- [`protocol_frameless_bidi.rs`](https://github.com/openai/codex/blob/a795f961be68383738e56ac396742b3e8451696a/codex-rs/codex-api/src/endpoint/realtime_websocket/protocol_frameless_bidi.rs)
- [`RealtimeVoicesList::builtin`](https://github.com/openai/codex/blob/a795f961be68383738e56ac396742b3e8451696a/codex-rs/protocol/src/protocol.rs#L356)

The public [GPT-Live delegation guide](https://developers.openai.com/api/docs/guides/live-delegation)
explains the division between live conversation and client-owned task execution;
its public wire event names differ from this Codex v3 adapter.

## Runtime ownership

`GptLiveService` owns the call, audio focus, microphone/media-playback foreground notification,
and event collectors. An Activity-scoped binder exposes state to Compose; leaving
the Activity does not end an active call. Permission denial, sign-out, audio-focus
loss, accessibility disconnection, and explicit hang-up release the peer
connection and microphone. Transient audio-focus interruptions mute input until
focus returns, preserving the user's own mute setting. Calls are not restarted after process
death. Ending voice leaves an already-running phone task under the ordinary
ClosePaw task controls. HTTP connection-pool cleanup runs on the client executor;
closing a TLS socket during service destruction must not perform network I/O on
the Android main thread.

`WebRtcLiveTransport` owns the native audio track and data channel. Native setup
and disposal run off the main thread. The service preserves headset routing and
restores the previous audio mode when it releases focus. Dictation controls are
hidden while GPT-Live owns the microphone, including in the overlay.
The one-shot SDP request waits for ICE gathering and includes the local network
candidates. Setup reports its current stage; a failed media connection is
distinguished from HTTP sign-in, account-access, and quota errors. Temporary ICE
disconnects have a 12-second recovery window; native failure or a closed event
channel requests a replacement voice connection. The UI stays in Connecting
until `session.started` arrives (a data channel alone is insufficient). A response
watchdog waits 30 seconds after transcribed speech or sustained local microphone
activity ends. Assistant output or delegation clears the response wait; ordinary
silence and worker execution do not start that watchdog. The separate idle timer
is described below. The energy detector discards samples
immediately and is not a speech recognizer.

After an established call fails, the service makes at most two automatic reconnect
attempts. It preserves the running worker, pending question, and bounded recent
conversation. Task updates received during recovery are buffered and delivered
as session context because delegation IDs belong to the old connection. No
request is automatically resubmitted, and the replacement connection ignores
new delegations until fresh user speech arrives. Exhausted retries show a
reconnect error. Explicit hang-up cancels all recovery work. Both the
summary and control overlays temporarily pass through injected agent gestures.
`LiveVoiceRuntime` holds context-free status shared with settings and the widget;
it does not own the service or native resources. `GptLiveWidget` uses short-lived
receiver work and event-driven RemoteViews updates, with no periodic polling.

`LiveConversation` handles transcripts, explicit `delegation.created` events,
duplicate delegation suppression, and UTF-8-safe feedback chunks. Transcription
alone never starts a phone task or ends voice. For each completed user utterance,
Android sends that turn plus current long-term memory as reference data to the
selected ChatGPT model (or an available account model when the selection uses
another provider). The request offers `end_gpt_live_call` and
`remember_experience`. A completed function call carrying the active call ID
ends this GPT-Live conversation. Assistant speech, partial transcripts and
historical context cannot execute the function. Selection runs alongside voice
playback. A phone handoff for the same utterance waits for the decision, with a
bounded timeout. New speech and disconnects invalidate pending decisions.

Only the phone-side voice controller receives the end-call tool. The task agent can see
earlier turns when it carries out a delegation, so giving it the tool would let
an old hang-up request end a new conversation. Executing the tool releases the
peer, microphone and foreground notification and leaves the running phone task
intact. The notification, widget and Quick Settings tile also work when a model
request is unavailable.

Live and the task agent share durable user, device and current-app memory.
`remember_experience` can add facts, replace an exact old entry, or forget an
entry. Existing facts are supplied to the selector so corrections can replace
outdated values. The same validation and app security policy apply in both paths.
Memory changes finish before releasing a related task handoff. Each connection
loads current memory and subscribes to updates from Live, Core and Settings;
the latest snapshot takes precedence over older conversation context. Memory-only
requests consume a redundant voice handoff after successful persistence. A
live-only Boolean in the native tool schema keeps mixed memory + phone-operation
requests eligible for the task agent; failed writes also allow that fallback.
Voice preview does not load or write memory.

Idle calls give one spoken reminder after 40 seconds and disconnect around 60
seconds, with at least 20 seconds between scheduling the reminder and hanging up.
Fresh user speech cancels that countdown. Normal assistant replies and running
phone tasks postpone idle timing; the reminder's own speech does not. Waiting for
user input counts as idle. Audio interruptions pause timing, and reconnections
start a fresh timer. Voice preview is excluded. The timer starts when the call is
ready, so an ordinary silent startup follows the same 40/60-second rule.
With Follow conversation selected and no previous user speech, the reminder uses
the app language (Chinese or English); an explicit voice language takes priority.
The separate response watchdog still handles input that gets no reply. Ambient
noise strong enough to count as microphone activity may renew the idle timer.

A bounded text handoff is saved locally: the initial user brief, up to 12 recent
turns (8,000 characters total), the latest phone request, progress/result, and
pending question. Automatic and manual reconnects restore this context. Worker
updates continue to refresh it after voice ends. No raw audio, screenshots, or
tool traces are kept in this handoff. The normal task history records delegated
requests as ordinary agent inputs.

**Settings → GPT-Live → Clear voice context** removes the handoff while voice is
stopped. New chats, switching history conversations, and ChatGPT sign-out clear
it too. A generation check prevents delayed saves from restoring cleared text.
A different active chat cannot inherit the previous chat's handoff. After process
death, saved progress is explicitly marked historical if there is no active
worker; reconnecting never restarts that task automatically. This bounded handoff
is not unlimited recall: put enduring preferences and background in custom
instructions. Core Agent instructions also survive chat checkpoint reloads.

`AgentService.getOrCreateLiveSession()` reuses an active ChatGPT-backed session
or creates one with the selected ChatGPT model (the preferred ChatGPT model when
another provider is selected). It refuses to replace a running session belonging
to another provider. Model selection in Settings is unchanged. The resolved model's
saved reasoning effort, approval settings, and perception settings are preserved.
Running-task follow-ups become supplements;
answers to a pending question use the existing user-response channel. Device
takeover and approval buttons remain authoritative.

## Verification

```bash
./gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  -e class ai.closepaw.qa.GptLiveTransportTest,ai.closepaw.qa.FloatingSummaryTest \
  ai.closepaw.test/androidx.test.runner.AndroidJUnitRunner
```

For a signed-in emulator, use the direct instrumentation commands above. Gradle's
connected-test cleanup can uninstall the app and remove its login data. Save an
emulator snapshot before testing and use replacement installs to retain app data.

JVM tests cover request headers, fresh credentials on reconnect, authentication
failure, cancellation, malformed events, duplicate handoffs, transcript assembly,
Unicode feedback, preference persistence, request configuration, playback activity,
task outcome presentation, structured hang-up calls, response deadlines, and
reconnect delegation suppression. `GptLiveTransportTest` establishes two successive native
WebRTC loopback calls, exchanges events in both directions, toggles mute, and
closes the transport. It also negotiates a preview with recording disabled and checks that
no microphone recording is active. It uses no account and sends no audio off the device.

Local acceptance on Android 16 covered adding/resizing the launcher widget,
portrait/landscape layouts, setup navigation, saved voice/language/tone after app
restart, and returning from the shared worker model/effort page. The settings page
was also checked with dark mode, enlarged text, reduced motion, and a small-phone
layout. The earlier widget checks passed all 18 selected device tests.

The floating-summary update passed all 2,142 JVM tests, five selected native/UI
device tests, and Lint with no errors.
Manual testing used a signed-in account and the emulator's WebView browser: a
text task opened the browser, loaded a page, and returned its summary. Progress,
collapse/expand, approval controls, returning to chat, and agent gestures beneath
the summary were checked, along with dark mode and 1.5× text in portrait and
landscape. Chrome was not installed on this emulator.

Authenticated testing on 2026-10-31 isolated the connection failure to the voice
catalog: with the same APK and ProtonVPN configuration, Marin returned HTTP 201
but failed ICE, while Cove repeatedly opened the data channel and emitted
`session.started`. A synthetic spoken request to open Android Settings was
recognized, delegated to the phone agent, and completed with the floating summary
visible over Settings.

Release builds identify ClosePaw screens by their manifest activity names. Events
from obfuscated overlay views must not switch the tracked location back to the
main app and hide the floating summary.

`GptLiveRemoteTest` is an opt-in check using an existing device login. It verifies
Cove and Juniper sessions, response transcripts, playback PCM activity, and the
absence of Android recording streams during preview. Run it with the direct
instrumentation command above, replacing the class with
`ai.closepaw.qa.GptLiveRemoteTest` and adding `-e live_remote true`. The test also
works with microphone permission denied; it is skipped by the normal device suite.

With the same `live_remote=true` instrumentation argument,
`GptLiveCallControlTest` checks native tool selection against the signed-in
account. Cases include Mandarin and Cantonese hang-up requests, a correction
about which conversation to end, negation, status questions, quoted commands,
and a request to end a WhatsApp call. In the signed release on the Android 16
emulator, two spoken turns in one call exercised both outcomes. “不要掛斷”
kept the recorder active. “我要掛斷的是跟你的對話” invoked the tool once,
released the recorder and stopped the foreground service without starting a
phone task. With earlier hang-up requests in context, a separate spoken request
to open Android Settings reached the task agent and kept voice connected.

For a real account test, sign in inside ClosePaw, start GPT-Live, ask it to open
Android Settings, and confirm both task execution and spoken completion. Also
check a follow-up question, app switching, mute, and hang-up. The remote account
test remains separate from the deterministic tests above.
For widget acceptance, repeat from the launcher with the app in the background;
check setup after microphone denial, duplicate taps, and task completion after
hang-up. Check incoming calls, headset routing, and screen-off behavior on the
target phone. Phone UI tasks still use the chosen execution platform and its
lock-screen restrictions; background voice does not grant access through a lock screen.

The appearance, shortcuts, and recovery update passed 2,160 JVM tests, the five
native/UI tests above, and Lint with no errors. Tests also cover bounded local
voice-context persistence, late-save rejection after clearing, chat isolation,
worker updates after hang-up, and Core Agent instruction checkpoint reloads.

On the signed Android 16 release, the pinned shortcut and Quick Settings tile
started voice; the spoken command “Please hang up”, tile, and summary controls
all ended it without killing the app process. A spoken request opened Android Settings and produced a spoken
completion. New tasks stayed folded; expanded summaries were checked in portrait
and landscape with dark mode and enlarged text. A temporary emulator-network
interruption exercised automatic reconnection. Manual reconnect testing recalled
an earlier spoken fact without repeating it, including after process restart.
These checks used the existing signed-in emulator and unchanged ProtonVPN
configuration; physical-device audio behavior was not part of this acceptance.

The shared-memory and idle-call update passed 2,182 JVM tests and Lint with no
errors. Eight memory-editor device tests cover live refresh, stale drafts and
session locking. Account-backed native-function tests cover add/correct/forget,
memory-only versus mixed phone requests, and hang-up intent with saved history.
On the signed emulator release, the idle reminder was spoken, fresh speech
cancelled the original deadline, a stated preference was saved without an explicit
remember command, forgetting avoided a duplicate task, and voice hang-up released
the recorder. A silent-call check confirmed automatic teardown after roughly 60
seconds of inactivity. Separate voice testing recalled a Core-saved fact in a new
chat, corrected it, recalled the new value, and removed it.
