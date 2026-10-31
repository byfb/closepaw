# GPT-Live voice

ClosePaw can establish a GPT-Live WebRTC call using the ChatGPT account signed in
on the Android device. Tap **GPT-Live** above the chat input, grant microphone
access, and speak. The live model delegates phone tasks to the normal ClosePaw
agent and speaks its questions and results. Mute and end-call controls remain in
chat; the microphone foreground notification also offers **End voice** when
notification permission is granted.

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

`GptLiveService` owns the call, audio focus, microphone foreground notification,
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
  -Pandroid.testInstrumentationRunnerArguments.class=ai.closepaw.qa.GptLiveTransportTest
```

JVM tests cover request headers, fresh credentials on reconnect, authentication
failure, cancellation, malformed events, duplicate handoffs, transcript assembly,
and Unicode feedback. `GptLiveTransportTest` establishes two successive native
WebRTC loopback calls, exchanges events in both directions, toggles mute, and
closes the transport. It uses no account and sends no audio off the device.

For a real account test, sign in inside ClosePaw, start GPT-Live, ask it to open
Android Settings, and confirm both task execution and spoken completion. Also
check a follow-up question, app switching, mute, and hang-up. The remote account
test remains separate from the deterministic tests above.
