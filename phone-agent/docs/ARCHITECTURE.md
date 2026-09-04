# Architecture

## The problem

Three things make "hold anywhere for five seconds, then talk to an AI that controls the phone"
hard on stock Android:

1. **No app can see touches that belong to other apps.** Overlays only receive touches inside
   their own bounds; a touch-transparent window sees nothing; `FLAG_WATCH_OUTSIDE_TOUCH` delivers
   a single, coordinate-stripped event. Accessibility services can intercept touches only in
   touch-exploration mode, which turns the phone into a screen reader.
2. **Controlling other apps requires either an AccessibilityService or root.** Accessibility gives
   a UI tree, clicks, typing, gestures, system buttons and (Android 11+) screenshots. Root gives
   everything else (`svc`, `settings`, `input`, `cmd`, `am`, `pm`).
3. **Microphone and activity starts from the background are restricted** since Android 10/11.

Phone Agent uses root for (1), an accessibility service plus normal platform APIs for (2), and the
combination of an accessibility binding, a foreground service and the draw-over-apps permission to
satisfy (3).

## Components

```
                         +-------------------+     su -c cat /dev/input/eventN
   touchscreen kernel -->|  HoldDetector     |<---------------------------------- root shell
                         |  (protocol B FSM) |
                         +---------+---------+
                                   | onHold()
   volume key ---> AgentAccessibilityService.onKeyEvent ----+
   QS tile / notification action / ACTION_ASSIST / VIS ----+
                                                            v
                                                 +---------------------+
                                                 |  SessionController  |  one coroutine per session
                                                 +----+-----------+----+
                                 SpeechInput (STT) <--+           +--> SpeechOutput (TTS)
                                 OverlayHost/SessionOverlay <-----+    AudioFocusHelper
                                                            |
                                                            v
                                                 +---------------------+   Anthropic Java SDK
                                                 |    ClaudeAgent      |-----> api.anthropic.com
                                                 |  tool-use loop      |<-----
                                                 +----------+----------+
                                                            | ToolRegistry.execute
                                                            v
              +------------------------------------------------------------------------+
              | tools/*: media sessions, contacts/SMS/call, apps, device settings,     |
              |          screen (via AgentAccessibilityService.screenReader/gestures), |
              |          notifications, run_shell (RootShell), ask_user               |
              +------------------------------------------------------------------------+
```

Everything runs in one process, so the accessibility service, the notification listener, the
foreground service and the session controller share singletons through `PhoneAgentApp`.

### HoldDetector (trigger/)

- Finds the touchscreen with `getevent -pl` (falls back to `getevent -p` and hex codes), picking
  the multi-touch device with `INPUT_PROP_DIRECT` and a touchscreen-like name; the user can pin a
  device by path or name.
- Streams `struct input_event` records from `su -c "exec cat /dev/input/eventN"`. Record size is
  24 bytes on 64-bit devices (16 on 32-bit) because the reading process is the toybox `cat`.
- Implements multi-touch protocol B: `ABS_MT_SLOT` selects a slot, `ABS_MT_TRACKING_ID >= 0`
  creates a contact, `-1` removes it, `ABS_MT_POSITION_X/Y` update it. Protocol A / single-touch
  devices are handled through `BTN_TOUCH` and `ABS_X/Y`.
- A stationary finger produces no events, so the deadline is a scheduled timer: when the contact
  count equals the required finger count the detector schedules a check at `downTime + holdMs`
  and cancels it on any change. A contact that drifts more than 4 % of the axis range is marked
  moved and disqualifies the hold. Fires once per touch group and only while the screen is on.
- Runs on its own thread with exponential back-off reconnects; `TriggerManager` owns the single
  instance and `TriggerService` (a `microphone|specialUse` foreground service) keeps the process
  alive and carries the notification with the Talk/Stop actions.

### AgentAccessibilityService (access/)

- Declared with `canRetrieveWindowContent`, `canPerformGestures`, `canRequestFilterKeyEvents` and
  `canTakeScreenshot`. Tracks the foreground package from window-state events.
- `ScreenReader` flattens the window trees (application windows first, this app's own overlay
  excluded) into numbered lines: role, text, description, hint, resource id, tap point and flags.
  Node handles are kept so `screen_tap 12` can call `performAction` on element 12, walking up to
  a clickable ancestor and finally falling back to a gesture at the element's centre.
- Gestures use `dispatchGesture`; screenshots use `takeScreenshot` (hardware buffer -> software
  bitmap -> JPEG, downscaled to 1280 px, with the scale factor reported to the model).
- The volume-key trigger consumes the configured key: a hold starts a session, a short press is
  re-injected as a normal volume step so the button keeps working.
- Because the service is bound by the system, its process may use the microphone from the
  background and may start activities, which is what makes background sessions work reliably.

### SessionController (session/)

`start()` cancels any running session (`cancelAndJoin`) and launches a new coroutine on the main
dispatcher:

```
vibrate -> show card -> request transient audio focus
-> listen (STT) -> ClaudeAgent.runTurn -> speak (TTS)
-> [follow-up listen unless the turn started audio playback] -> repeat or close
```

The controller also implements `ToolContext`: `askUser` speaks a question and listens;
`confirm` does the same and parses the answer with a small yes/no vocabulary (negatives win).
Errors from the SDK are mapped to short spoken messages (bad key, rate limit, offline, ...).

### ClaudeAgent (agent/)

- Uses the Anthropic Java SDK's beta Messages endpoint so the request can carry
  `fallbacks: "default"` (server-side refusal fallbacks) alongside the tools.
- Request shape: tools (fixed order) -> system block 1 (static instructions, `cache_control`)
  -> system block 2 (volatile device context) -> messages; a top-level `cache_control` auto-caches
  the growing conversation. Adaptive thinking plus `output_config.effort` on every model except
  Haiku 4.5.
- Loop: `stop_reason == tool_use` -> execute every `tool_use` block, return all results in one
  user message (images as `image` blocks inside `tool_result`), repeat; `pause_turn` -> resend;
  `refusal` -> spoken decline; `max_tokens` -> return what there is. `response.toParam()` is
  appended verbatim so thinking blocks and server-tool blocks round-trip correctly.
- `ToolRegistry` converts `ToolSpec`s to `BetaTool` definitions and wraps execution with the
  confirmation gate, a per-tool timeout and exception-to-`is_error` conversion.

### Speech (speech/)

`SpeechInput` prefers `createOnDeviceSpeechRecognizer` (Android 12+), otherwise binds an explicit
`RecognitionService` (Google's first) rather than the system default, because once the app is the
digital assistant the system default points at the app's own `ProxyRecognitionService`. That proxy
exists only because the assistant framework demands one; it forwards to the real recognizer so
other apps keep working. `SpeechOutput` wraps `TextToSpeech` with utterance completion callbacks so
`speak()` suspends until playback ends.

### Digital-assistant integration (assist/)

`AgentVoiceInteractionService` + `AgentVoiceSessionService` make the app selectable under
Settings > Default apps > Digital assistant; `onShow` starts a normal session and hides the system
session. `AssistActivity` handles plain `ACTION_ASSIST` for launchers that use it. With root,
`RootSetup.makeDefaultAssistant` writes the two secure settings directly.

## Trust boundaries

- The API key never leaves the device except in the `x-api-key` header to Anthropic.
- Claude only acts through the tool set above; it has no direct shell unless `run_shell` is
  called, and that tool is confirmation-gated by default.
- Spoken confirmation is a UX safeguard, not a security boundary: anyone who can talk to the phone
  while it is unlocked can trigger actions. Do not disable the confirmations on a phone others
  handle.
- Root grants (`pm grant`, `appops`, `settings put`) are visible and reversible through the same
  commands.

## Known limitations

- The hold gesture is observed, not intercepted: the app underneath still receives the long
  press. Intercepting would need `EVIOCGRAB` plus re-injection through `uinput`; that is a
  possible later step (a small native helper).
- No wake word. The trigger is always a physical gesture or button.
- `take_screenshot` needs Android 11+ (or root `screencap`).
- Connectivity toggles need root; unrooted phones only get the settings panel.
- Speech recognition quality depends on the phone's recognizer; there is no fallback to a cloud
  transcription API yet.
- Sessions are ephemeral by design ("new chat" per hold). There is no memory across sessions.

## Roadmap ideas

- Native input grab (consume the hold so apps never see it) and a configurable two-finger variant.
- Optional streaming responses so long answers start speaking before Claude finishes.
- Per-app tool descriptions (deep links for Spotify, Audible, Messages) to reduce screen driving.
- A conversation history screen and an "undo last action" tool where reversible.
