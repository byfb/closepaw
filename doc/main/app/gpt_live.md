# GPT-Live voice

ClosePaw can establish a GPT-Live WebRTC call using the ChatGPT account signed in
on the Android device. Tap **GPT-Live** above the chat input, grant microphone
access, and speak. The live model delegates phone tasks to the normal ClosePaw
agent and speaks its questions and results. Mute and end-call controls remain in
chat; the microphone foreground notification also offers **End voice** when
notification permission is granted.

## Home-screen widget and settings

Open **Settings → GPT-Live** (or the settings icon beside the chat's voice button).
**Add to home screen** opens the launcher's widget confirmation. The same widget
is also available from the launcher's Widgets menu.

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
| Voice | Marin (default), Cedar |
| Reply language | Follow conversation, Hong Kong Cantonese, Mandarin, English |
| Tone | Natural, Calm, Lively |

Preferences are snapshotted for each new voice session. Language and tone become
instructions; they do not configure a separate transcription engine or a numeric
speed control. Changing them does not reconnect an active call or rebuild the
worker. The two voice IDs are protocol candidates, not an account capability
discovery result; account acceptance and pronunciation still need real-account
verification. A rejected configuration is shown as an error without silently
substituting another voice.

**Preview voice** starts a separate receive-only WebRTC session with the selected
preferences and a short sample. It creates no microphone track, never dispatches
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

The public [GPT-Live delegation guide](https://developers.openai.com/api/docs/guides/live-delegation)
explains the division between live conversation and client-owned task execution;
its public wire event names differ from this Codex v3 adapter.

## Runtime ownership

`GptLiveService` owns the call, audio focus, microphone/media-playback foreground notification,
and event collectors. An Activity-scoped binder exposes state to Compose; leaving
the Activity does not end an active call. Permission denial, sign-out, audio-focus
loss, accessibility disconnection, network failure, and explicit hang-up all
release the peer connection and microphone. Calls are not restarted after process
death. Ending voice leaves an already-running phone task under the ordinary
ClosePaw task controls.

`WebRtcLiveTransport` owns the native audio track and data channel. Native setup
and disposal run off the main thread. The service preserves headset routing and
restores the previous audio mode when it releases focus. Dictation controls are
hidden while GPT-Live owns the microphone, including in the overlay.
`LiveVoiceRuntime` holds context-free status shared with settings and the widget;
it does not own the service or native resources. `GptLiveWidget` uses short-lived
receiver work and event-driven RemoteViews updates, with no periodic polling.

`LiveConversation` handles transcripts, explicit `delegation.created` events,
duplicate delegation suppression, and UTF-8-safe feedback chunks. Transcription
alone never starts a task. Conversation context stays in memory for the call;
the normal task history records delegated requests as ordinary agent inputs.

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
./gradlew assembleDebug testDebugUnitTest lintDebug
./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.GptLiveTransportTest,ai.closepaw.qa.SettingsNavTest,ai.closepaw.qa.SettingsLlmAuthTest,ai.closepaw.qa.SettingsModelRefreshTest
```

JVM tests cover request headers, fresh credentials on reconnect, authentication
failure, cancellation, malformed events, duplicate handoffs, transcript assembly,
Unicode feedback, preference persistence, request configuration, playback activity,
and task outcome presentation. `GptLiveTransportTest` establishes two successive native
WebRTC loopback calls, exchanges events in both directions, toggles mute, and
closes the transport. It also negotiates a receive-only preview and checks that
no microphone recording is active. It uses no account and sends no audio off the device.

Local acceptance on Android 16 covered adding/resizing the launcher widget,
portrait/landscape layouts, setup navigation, saved voice/language/tone after app
restart, and returning from the shared worker model/effort page. The settings page
was also checked with dark mode, enlarged text, reduced motion, and a small-phone
layout. Build, all 2,130 JVM tests, and all 18 selected device tests passed; Lint
reported no errors. This does not verify the remote account or voice options.

For a real account test, sign in inside ClosePaw, start GPT-Live, ask it to open
Android Settings, and confirm both task execution and spoken completion. Also
check a follow-up question, app switching, mute, and hang-up. The remote account
test remains separate from the deterministic tests above.
For widget acceptance, repeat from the launcher with the app in the background;
check setup after microphone denial, duplicate taps, and task completion after
hang-up. Check incoming calls, headset routing, and screen-off behavior on the
target phone. Phone UI tasks still use the chosen execution platform and its
lock-screen restrictions; background voice does not grant access through a lock screen.
