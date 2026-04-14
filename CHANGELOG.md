# Orb Eye Changelog

---

## v2.5 (2026-04-15)

### Improved Endpoints

#### `POST /find` — diagnostics on failure + fuzzy matching

`/find` now returns structured diagnostics when lookup fails, instead of only generic NOT_FOUND.

New request options:
- `"fuzzy": true` — enable fuzzy fallback matching (text/desc/id similarity)
- `"candidates": 5` — max number of diagnostic candidates in failure response (0..20)

Failure response now includes:
- `found: false`
- `reason` (`text_mismatch`, `desc_mismatch`, `id_mismatch`, `not_clickable`, `need_scroll`, `off_screen`, `another_window`, etc.)
- `candidates[]` with `text/id/score/bounds`

Example failure response:
```json
{
  "ok": false,
  "found": false,
  "reason": "text_mismatch",
  "candidates": [
    {"text":"立即购买","score":0.72,"bounds":[120,1980,960,2088]}
  ]
}
```

Also on success, response now includes:
- `found: true`
- `reason: "exact_match"` or `"fuzzy_match"`
- `score` (fuzzy confidence)

---

## v2.2 (2026-03-24)

### New Endpoints

#### `GET/POST /ocr` — OCR for WebView and screenshot content
Recognize visible text from:
- current screen screenshot (default), or
- caller-provided `imageBase64`.

Supports:
- region crop (`region.x/y/width/height` or `left/top/right/bottom`)
- text matching (`text`, `contains`, `index`)
- optional auto-tap on matched line (`tap=true`)

**Example request:**
```json
{"text":"找朋友帮忙付","contains":true,"tap":true,"index":0}
```

**Example response (excerpt):**
```json
{
  "ok": true,
  "engine": "mlkit_chinese",
  "lineCount": 18,
  "matchCount": 1,
  "matches": [{"text":"找朋友帮忙付","centerX":540,"centerY":1662}],
  "tap": true
}
```

**Why:** Accessibility nodes are often empty on WebView pages. `/ocr` provides a visual fallback that keeps the same HTTP-agent architecture.

Also added alias endpoint `GET/POST /ocr-screen` for explicit current-screen OCR semantics (same behavior as `/ocr`).

---

## v2.1 (2026-02-28)

### New Endpoints

#### `POST /find` — Precise element search
Find a specific UI element by text, description, or resource ID — and get its center coordinates ready for `/tap`.

**Request body (one of):**
```json
{"text": "Send"}
{"desc": "Search button"}
{"id": "com.example:id/btn_submit"}
```

**Optional fields:**
- `"clickable": true` — only return clickable nodes (walks up to clickable parent if needed)
- `"index": N` — return the Nth match (0-based, default 0)

**Response:**
```json
{
  "ok": true,
  "text": "Send",
  "desc": "",
  "id": "com.twitter.android:id/tweet_button",
  "bounds": "[900,1800][1080,1900]",
  "centerX": 990,
  "centerY": 1850,
  "clickable": true,
  "editable": false,
  "scrollable": false,
  "matchCount": 1,
  "index": 0
}
```

**Why:** Eliminates the `/screen` → manual bounds math → `/tap` pattern. One call returns `centerX`/`centerY` ready to pass directly to `/tap`.

---

#### `GET /screenshot` — Screenshot via AccessibilityService
Takes a screenshot without root using `AccessibilityService.takeScreenshot()` (API 30+).

**Response:**
```json
{
  "ok": true,
  "image": "data:image/png;base64,...",
  "width": 1080,
  "height": 2400
}
```

**Why:** Replaces the 4-step root-dependent pipeline (`su -c screencap → sharp resize → base64 → read`). No root required.

**Requires:** API 30+ (Android 11). Returns `{"ok":false,"code":"NOT_SUPPORTED"}` on older devices.

---

#### `GET /clipboard` — Read clipboard
Returns current clipboard text content.

**Response:**
```json
{"ok": true, "text": "copied text here"}
```

#### `POST /clipboard` — Write clipboard
```json
{"text": "text to copy"}
```

**Response:**
```json
{"ok": true, "text": "text to copy"}
```

**Why:** Some apps (GitHub WebView, etc.) don't accept `SET_TEXT` but do accept paste. Now you can write to clipboard then use long-press → Paste.

---

#### `POST /gesture` — Composite gestures
Multi-touch and custom stroke gestures.

**Pinch in/out:**
```json
{"type": "pinch_in",  "x": 540, "y": 1200, "distance": 200, "durationMs": 300}
{"type": "pinch_out", "x": 540, "y": 1200, "distance": 200, "durationMs": 300}
```

**Custom multi-stroke:**
```json
{
  "type": "multi",
  "strokes": [
    {"path": [[100, 500], [100, 200]], "startMs": 0, "durationMs": 300},
    {"path": [[300, 500], [300, 800]], "startMs": 0, "durationMs": 300}
  ]
}
```

---

### Enhanced Endpoints

#### `POST /input` — Input improvements
- Added `previousText` field in response — contains the text that was in the field **before** modification. Use to verify SET_TEXT actually cleared/replaced (fixes the X/Twitter compose accumulation bug).
- Added `{"clear": true}` operation — clears the field without writing any new text.
- Added `"action"` field in response: `"set"` | `"append"` | `"clear"`.

**Example response:**
```json
{
  "ok": true,
  "text": "Hello world",
  "previousText": "",
  "action": "set"
}
```

**Clear-only:**
```json
// Request:
{"clear": true}
// Response:
{"ok": true, "previousText": "old content", "action": "clear"}
```

---

#### `GET /notify` — Package exclude filter
Added `?exclude=` parameter to suppress noise packages.

```
GET /notify?exclude=com.android.systemui,com.google.android.gms
GET /notify?package=com.twitter.android&exclude=com.android.systemui
```

Existing `?package=xxx` include-filter still works.

---

#### `POST /scroll` — Enhanced scrolling
- `"target"` field: find the scrollable container that **contains** matching text, then scroll it.
- `"count"` field: repeat scroll N times with 300ms intervals.

```json
{"direction": "down", "target": "Timeline", "count": 3}
```

---

#### `GET /screen` — Enhanced element listing
New query parameters:
- `?scrollable=true` — only return elements inside a scrollable container
- `?editable=true` — only return editable input fields
- `?package=xxx` — filter elements by package name

Every element now includes `centerX` and `centerY` (computed from bounds), eliminating manual coordinate math.

**Element shape:**
```json
{
  "text": "Follow",
  "desc": "",
  "id": "com.twitter.android:id/follow_btn",
  "clickable": true,
  "editable": false,
  "scrollable": false,
  "bounds": "[800,400][980,460]",
  "centerX": 890,
  "centerY": 430
}
```

---

### Error Handling
All endpoints now return unified error format with machine-readable error codes:

```json
{"ok": false, "error": "No editable field found", "code": "NO_EDITABLE"}
```

**Error codes:**
| Code | Meaning |
|------|---------|
| `NOT_FOUND` | Element, window, or route not found |
| `NO_EDITABLE` | No editable input field available |
| `TIMEOUT` | Operation timed out |
| `INVALID_ARGS` | Missing or invalid request parameters |
| `NOT_SUPPORTED` | Feature requires higher API level |
| `SCREENSHOT_FAILED` | Screenshot API returned error |
| `INTERNAL_ERROR` | Unexpected exception |
| `UNKNOWN` | Unclassified error (legacy compat) |

---

### Version Updates
- `/ping` now returns `"version": "2.1"`
- `build.gradle`: `versionCode 2`, `versionName "2.1"`
- Log tag updated: `"Orb Eye v2.1 service connected"`

---

## v2.0

- Core HTTP server on port 7333
- `/screen`, `/tree`, `/tap`, `/click`, `/input`, `/setText`
- `/scroll`, `/swipe`, `/longpress`
- `/notify` with package filter
- `/wait` for UI change detection
- `/info`, `/focused`
- Global actions: `/back`, `/home`, `/recents`, `/notifications`
