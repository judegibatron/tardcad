# Phone Agent

Hold a finger anywhere on the screen for five seconds and a voice session opens. Say what you want
done ("unpause my audiobook", "text Sam that I'm ten minutes late", "turn the flashlight on",
"what's on my screen?") and Claude does it on the phone, then tells you what happened. Every hold
starts a fresh conversation.

It is a single Android app (Kotlin, no Play Services) built for a rooted phone, with graceful
fallbacks when root or individual permissions are missing.

## What it can do

| Area | Tools Claude gets |
|---|---|
| Media | `media_control` (play/pause/toggle/next/previous/stop, per app), `list_media` |
| People | `find_contact`, `send_sms`, `make_call` |
| Apps | `open_app`, `list_apps`, `open_url`, `navigate` |
| Device | `get_device_state`, `set_volume`, `set_brightness`, `flashlight`, `set_do_not_disturb`, `set_connectivity`, `set_alarm`, `set_timer`, `set_clipboard` |
| Screen | `screen_read`, `screen_tap`, `screen_type`, `screen_scroll`, `screen_swipe`, `press_button`, `take_screenshot` |
| Anything else | `run_shell` (root), `read_notifications`, `ask_user`, `wait`, optional Claude web search |

Sending texts, placing calls and running shell commands ask you for a spoken "yes" first
(each can be switched off in the app).

## Requirements

- Android 11 or newer (`minSdk 30`). Written and checked against Android 15 APIs.
- Root (Magisk, KernelSU or APatch) for the hold-anywhere trigger and `run_shell`. Everything
  else works unrooted.
- A Claude API key from <https://console.anthropic.com/>. Calls go straight from the phone to
  `api.anthropic.com`; nothing else is contacted.
- A speech recognizer on the phone (Google's is on every Samsung; Android 12+ on-device
  recognition is preferred when available) and a text-to-speech engine.

## Build and install

```bash
cd phone-agent
./gradlew assembleDebug            # needs JDK 17+, Android SDK platform 35 (Android Studio installs it)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Android Studio: File > Open > `phone-agent`, then Run. The debug and release builds share one
application id on purpose so root grants and permissions survive switching between them.

## First-run setup

Open **Phone Agent** and work down the checklist. On a rooted phone the fast path is:

1. Tap **Request** next to Root and approve the prompt from Magisk/KernelSU.
2. Tap **Root: grant everything**. This runs the equivalent of:
   `pm grant` for microphone, SMS, contacts, phone, notifications and
   `WRITE_SECURE_SETTINGS`; `appops set` for draw-over-apps and modify-system-settings;
   `cmd notification allow_listener` and `allow_dnd`; battery-optimisation whitelist; and it
   appends the accessibility service to `enabled_accessibility_services`.
3. Paste your API key under **Claude** and tap Save.
4. Optional: **Root: make default assistant** so the phone's assistant gesture (Samsung: swipe up
   from a bottom corner, or the side key when set to the assistant) also opens Phone Agent.

Without root, tap each **Open** button and enable the item in Settings, then **Grant** the runtime
permissions. The accessibility service is the important one: it provides screen reading, taps,
system buttons, screenshots and the overlay card. Notification access is what lets the app see
and control other apps' media sessions.

## Triggers

- **Hold anywhere (root).** A root `cat /dev/input/eventN` streams raw touchscreen events into the
  app, which follows the Linux multi-touch protocol and fires when the configured number of
  fingers (default 1) stays put for the configured time (default 5 s). Duration, finger count and
  the touchscreen device are adjustable under **Trigger**. A second hold during a session ends it.
  The app underneath still sees the touch (a long-press menu may open); that is inherent to
  observing rather than intercepting input.
- **Volume key hold (no root).** Pick volume up or down; holding it 1.5 s (adjustable) opens a
  session. Short presses still change the volume.
- **Quick Settings tile**, the **Talk** action on the persistent notification, and the
  **assistant gesture** once Phone Agent is the default digital assistant.

## How a session runs

1. Haptic tick, the dark session card appears at the bottom of the screen (above the lock screen
   too, via the accessibility overlay), music ducks.
2. Speech is transcribed on-device when possible; the transcript shows live on the card.
3. Claude (default `claude-opus-5`, adaptive thinking, configurable effort) gets a compact system
   prompt, the device context and the tool list, and runs the tool loop until it has an answer.
   Tool activity shows on the card ("media control: play", "screen read", ...).
4. The reply is spoken. Unless the reply started audio (resuming an audiobook, for example), the
   app listens for a follow-up for a few seconds, then closes.

Model, effort, web search, follow-up listening, confirmations and TTS are all under settings.

## Safety and privacy

- The API key is encrypted with an Android Keystore key. Preferences are excluded from backups.
- Dangerous tools require a spoken confirmation. Claude is instructed never to invent message
  content and to ask (`ask_user`) rather than guess a recipient.
- The transcript, tool results and (when Claude asks for one) a downscaled screenshot are sent to
  the Claude API. Nothing is stored server-side by this app.
- `run_shell` runs as root. Keep the confirmation on unless you fully trust the prompts you speak.

## Troubleshooting

The **Log** section at the bottom of the app shows what happened (tap **Copy log** to share it).

| Symptom | Likely cause |
|---|---|
| Hold never fires; log says "root not granted" | Approve Phone Agent in Magisk/KernelSU, then tap Request again. |
| "no touchscreen found in getevent -p" | Set the touchscreen override under **Trigger** (run `su -c getevent -pl` in a terminal to see device names). |
| Card never appears | Enable the accessibility service or "Draw over other apps". |
| "Speech recognition failed: microphone permission missing" | Grant the microphone permission; on Android 14+ also let the hold-to-talk service run. |
| `media_control` says notification access is off | Enable Notification access for Phone Agent. |
| Claude rejects the key / model | Check the key and that your account has access to the chosen model. |

## Layout

```
phone-agent/
  app/src/main/java/io/github/judegibatron/phoneagent/
    trigger/   HoldDetector (root touch stream), TriggerService, TriggerManager
    access/    AgentAccessibilityService (engine + overlay + key trigger), ScreenReader, notification listener
    session/   SessionController (listen -> Claude -> speak loop), SessionOverlay, OverlayHost
    agent/     ClaudeAgent (Anthropic Java SDK tool loop), SystemPrompt
    tools/     one class per tool, ToolRegistry
    speech/    SpeechInput (SpeechRecognizer), SpeechOutput (TTS), AudioFocusHelper
    assist/    digital-assistant integration and the recognizer proxy
    root/      RootShell (su), RootSetup (one-tap grants)
    ui/        MainActivity setup dashboard, quick-settings tile
  app/src/test/  JVM tests for the getevent parser, fuzzy matching and yes/no parsing
  docs/ARCHITECTURE.md
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the design, the reasons behind the root
approach and the known limitations. Licensed under GPL-3.0-or-later like the rest of this repo.
