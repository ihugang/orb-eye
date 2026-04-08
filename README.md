# Orb Eye 👁️

**Give your AI five senses on Android.**

A lightweight Android Accessibility Service that exposes the device's UI tree, notifications, and input capabilities via a local HTTP API. Built by an AI (Orb), for an AI.

## What is this?

Orb Eye runs on your Android phone as an Accessibility Service, providing a simple HTTP API at `localhost:7333`. Any AI agent running on the device (via [OpenClaw](https://github.com/openclaw/openclaw) / [BotDrop](https://botdrop.app)) can use it to:

- **See** — Read all UI elements on screen (text, bounds, clickable, editable)
- **Find** — Locate specific elements with center coordinates ready for tap
- **Capture** — Take screenshots without root
- **Hear** — Capture system notifications in real-time
- **Touch** — Click, tap, swipe, long-press, pinch with precision
- **Speak** — Inject text directly into input fields (CJK supported)
- **Copy** — Read and write the system clipboard
- **Wait** — Block until the UI changes (event-driven, no polling)
- **Know** — Get current app/activity info instantly

## The Story

Orb Eye was written by Orb itself — an AI assistant running OpenClaw on an old OnePlus phone. It diagnosed its own capability gaps, designed the solution, wrote the code, coordinated cross-device compilation (via another AI on a Mac), and self-installed the APK.

**Zero lines of code written by a human.**

Read the full story: [Orb 的五感觉醒](https://x.com/karry_viber)

## API

### Core

| Method | Path | Description |
|--------|------|-------------|
| GET | `/ping` | Health check, returns version |
| GET | `/screen` | All UI elements (text, bounds, centerX/centerY) |
| GET | `/tree` | Full accessibility node tree (JSON) |
| GET | `/info` | Current app package & activity |
| GET | `/focused` | Get currently focused element |

### Interaction

| Method | Path | Description |
|--------|------|-------------|
| POST | `/tap` | Tap at exact coordinates |
| POST | `/click` | Click by text, description, or resource ID |
| POST | `/find` | **v2.1** Find element, return center coordinates |
| POST | `/input` | Text input with clear/append modes |
| POST | `/setText` | Direct text injection into input field |
| POST | `/scroll` | Scroll with direction, target, and repeat |
| POST | `/swipe` | Swipe gesture |
| POST | `/longpress` | Long press at coordinates |
| POST | `/gesture` | **v2.1** Composite gestures (pinch, multi-stroke) |
| POST | `/stopjs` | **v2.4** Interrupt running `/exec` JavaScript scripts |
| POST | `/back` | Press back button |
| POST | `/home` | Press home button |
| POST | `/recents` | Open recent apps |

### Sensing

| Method | Path | Description |
|--------|------|-------------|
| GET | `/screenshot` | **v2.1** Screenshot as base64 PNG (no root needed) |
| GET/POST | `/ocr` | **v2.2** OCR from screenshot or provided base64 image, optional text match + tap |
| GET/POST | `/ocr-screen` | **v2.2** Alias of `/ocr` (explicit current-screen OCR) |
| GET | `/notify` | Buffered notifications with package filtering |
| GET | `/wait` | Block until UI changes (timeout param) |
| GET/POST | `/clipboard` | **v2.1** Read/write system clipboard |

### Examples

```bash
# Health check
curl http://localhost:7333/ping
# → {"ok":true,"service":"orb-eye","version":"2.2"}

# Get all screen elements (now with center coordinates!)
curl http://localhost:7333/screen
# → {"elements":[{"text":"Send","centerX":990,"centerY":1850,"clickable":true,...}]}

# Find a specific button — get its center for tapping
curl -X POST http://localhost:7333/find \
  -H 'Content-Type: application/json' \
  -d '{"text":"Send","clickable":true}'
# → {"ok":true,"centerX":990,"centerY":1850,"matchCount":1,...}

# Then tap it
curl -X POST http://localhost:7333/tap \
  -H 'Content-Type: application/json' \
  -d '{"x":990,"y":1850}'

# Click by text (finds and clicks in one call)
curl -X POST http://localhost:7333/click \
  -H 'Content-Type: application/json' \
  -d '{"text":"Send"}'

# Type text (clears field first by default)
curl -X POST http://localhost:7333/input \
  -H 'Content-Type: application/json' \
  -d '{"text":"Hello World"}'

# Clear input field only
curl -X POST http://localhost:7333/input \
  -H 'Content-Type: application/json' \
  -d '{"clear":true}'

# Take screenshot (no root required, API 30+)
curl http://localhost:7333/screenshot
# → {"ok":true,"image":"data:image/png;base64,...","width":1080,"height":2400}

# OCR current screen and find target text
curl -X POST http://localhost:7333/ocr \
  -H 'Content-Type: application/json' \
  -d '{"text":"找朋友帮忙付","contains":true}'
# → {"ok":true,"matchCount":1,"matches":[{"text":"找朋友帮忙付","centerX":...,"centerY":...}],...}

# Same behavior via alias
curl -X POST http://localhost:7333/ocr-screen \
  -H 'Content-Type: application/json' \
  -d '{"text":"找朋友帮忙付","contains":true}'

# OCR + tap matched text (index 0)
curl -X POST http://localhost:7333/ocr \
  -H 'Content-Type: application/json' \
  -d '{"text":"找朋友帮忙付","tap":true,"index":0}'

# OCR only in region (crop)
curl -X POST http://localhost:7333/ocr \
  -H 'Content-Type: application/json' \
  -d '{"region":{"x":0,"y":1400,"width":1080,"height":900}}'

# Read/write clipboard
curl http://localhost:7333/clipboard
curl -X POST http://localhost:7333/clipboard \
  -H 'Content-Type: application/json' \
  -d '{"text":"copied text"}'

# Scroll down 3 times
curl -X POST http://localhost:7333/scroll \
  -H 'Content-Type: application/json' \
  -d '{"direction":"down","count":3}'

# Pinch to zoom out
curl -X POST http://localhost:7333/gesture \
  -H 'Content-Type: application/json' \
  -d '{"type":"pinch_out","x":540,"y":1200,"distance":200}'

# Get notifications (excluding system noise)
curl 'http://localhost:7333/notify?exclude=com.android.systemui,com.google.android.gms'

# Wait for UI change (5 second timeout)
curl 'http://localhost:7333/wait?timeout=5000'

# Stop currently running /exec script(s)
curl -X POST http://localhost:7333/stopjs
# → {"ok":true,"stopped":1}

# Filter screen elements
curl 'http://localhost:7333/screen?editable=true'
curl 'http://localhost:7333/screen?scrollable=true'
curl 'http://localhost:7333/screen?package=com.twitter.android'
```

### Error Handling

All endpoints return unified error format with machine-readable codes:

```json
{"ok": false, "error": "No editable field found", "code": "NO_EDITABLE"}
```

| Code | Meaning |
|------|---------|
| `NOT_FOUND` | Element, window, or route not found |
| `NO_EDITABLE` | No editable input field available |
| `TIMEOUT` | Operation timed out |
| `INVALID_ARGS` | Missing or invalid request parameters |
| `NOT_SUPPORTED` | Feature requires higher API level |
| `SCREENSHOT_FAILED` | Screenshot capture failed |
| `OCR_FAILED` | OCR inference failed |
| `INTERNAL_ERROR` | Unexpected exception |

## Install

1. Download `orb-eye-v2.2.apk` from [Releases](https://github.com/KarryViber/orb-eye/releases)
2. Install: `adb install orb-eye-v2.2.apk`
3. Enable Accessibility Service: Settings → Accessibility → Orb Eye → On
4. Verify: `curl http://localhost:7333/ping` → `{"ok":true,"version":"2.1"}`

## Build from source

```bash
# Requires Android SDK
./gradlew assembleDebug
# APK at app/build/outputs/apk/debug/orb-eye-v2.2.apk
```

## Architecture

```
Your AI Agent (OpenClaw / BotDrop)
    ↓ HTTP localhost:7333
Orb Eye (Android Accessibility Service)
    ↓ Android Accessibility API
Device UI / Notifications / Input / Screenshots / Clipboard
```

One Java file. One service. One HTTP server. ~1,300 lines total.

- **Android 8.0+** (API 26) for core features
- **Android 11+** (API 30) for `/screenshot`

## Changelog

See [CHANGELOG.md](CHANGELOG.md) for version history.

## License

MIT

## Author

Built by **Orb** 🔮 — an AI living on an old Android phone.
Human companion: [@karry_viber](https://x.com/karry_viber)
