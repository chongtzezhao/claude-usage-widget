<div align="center">

# 🦞 Claude + Codex Usage Widget

**Android home-screen widgets for your Claude and OpenAI Codex subscription usage.**
Keeps Claude's and ChatGPT/Codex's `5H` and `1W` percentages (plus reset countdowns, a weekly pace marker, and a local 5H burn rate) live on your home screen — in one APK, with one widget each.

![status: working](https://img.shields.io/badge/status-working-brightgreen)
![platform: Android 8+](https://img.shields.io/badge/platform-Android%208%2B-3DDC84?logo=android&logoColor=white)
![built with: Kotlin + Glance](https://img.shields.io/badge/built%20with-Kotlin%20%2B%20Glance-7F52FF?logo=kotlin&logoColor=white)

<br />

<img src="docs/promo.png" alt="Claude Usage widget on an Android home screen, with a close-up of the 5H and 1W usage bars" width="720" />

<br />

### [![Download APK](https://img.shields.io/badge/⬇%20Download%20APK-3DDC84?style=for-the-badge&logo=android&logoColor=white)](https://github.com/chongtzezhao/claude-usage-widget/releases/latest/download/claude-usage-widget.apk)

Sign in once per provider; each widget refreshes itself in the background.

</div>

---

## 📲 Install in 3 steps

This fork uses a new APK signing key. If you installed the upstream app, uninstall it before installing this fork. Then sign in again. Uninstalling removes the app’s saved credentials and settings.

Updating from v1.0.x of **this** repository? Install over the top: the signing key is unchanged, so your Claude sign-in and settings survive the update.

1. **[Download the APK](https://github.com/chongtzezhao/claude-usage-widget/releases/latest/download/claude-usage-widget.apk)** on your Android phone (tap the button above).
2. **Open the downloaded file** and tap **Install**.
3. The first time, Android asks to **allow installs from this source** (your browser or file app). Tap **Settings → enable “Allow from this source” → back → Install**. If Play Protect shows an *“unsafe app?”* prompt, that warning appears for any app not installed from the Play Store; choose **Install anyway**.

> Why the warnings? The APK is sideloaded rather than shipped through the Play Store, so Android plays it safe. It's the normal two-tap detour for any direct APK.

Then continue with first-run setup below.

## 👋 First-run setup

1. Open the **Claude & Codex Usage** app. Sign in to **Claude** and/or **ChatGPT/Codex** — each has its own button and its own session.
   - Claude: complete the normal `claude.ai` login in the WebView; it closes automatically once it captures your session.
   - Codex: sign in at `chatgpt.com` with **email + password** (Google sign-in is blocked inside embedded WebViews). The session cookie is captured automatically.
2. Tap **Test fetch now** under each provider to confirm it prints your current percentages. These should match `claude.ai/settings/usage` and the Codex usage page.
3. Tap **Disable battery optimization** and allow it, so Android doesn't kill the 15-minute background refresh.
4. Long-press your home screen → **Widgets** → **Claude Usage** or **Codex Usage**, drag one (or both) on, and resize it however you like. Tap a widget any time to refresh it.

## 📋 Requirements

- A Claude **Pro/Max** subscription and/or a ChatGPT plan that includes **Codex** (Plus/Pro/Team). This reads consumer subscription usage, not API-key usage.
- **Android 8.0 (API 26)** or newer. Built and tested on a Pixel 9 / Android 16.

---

## ⚠️ Unofficial: use at your own risk

Anthropic and OpenAI provide no public API for consumer subscription usage. These widgets replay the same **undocumented internal endpoints** that the `claude.ai/settings/usage` and Codex usage pages call in the browser, authorized by session cookies harvested from an in-app WebView login. They can break at any time if either provider changes its endpoint, auth flow, or Cloudflare protection. They are not sanctioned integrations, so use them for your own account only.

## ✨ Highlights

- **Two providers, two widgets, one APK.** Add Claude Usage, Codex Usage, or both.
- **Rolling windows at a glance.** 5-hour and 1-week percentages, with a live countdown to each reset, a weekly pace marker, and a local 5H burn rate. Codex plans that only expose a weekly cap show a weekly-only layout instead of a fake 5H bar.
- **How many sessions are left.** The Claude and Codex widgets' full layouts also show an estimated number of remaining fully-used 5H sessions (`≈N×5H`), EWMA-calibrated from recorded history and hidden until enough data exists. The Codex badge needs a plan that exposes both a 5H and a weekly-style (6+ day) window.
- **Refreshes itself.** A background job updates each provider every ~15 minutes; tap a widget to force an immediate refresh.
- **Sign in once per provider.** A single `claude.ai` or `chatgpt.com` login; you only re-authenticate when that session finally expires.
- **Resizes freely.** Each widget snaps between a compact and a full layout, and adapts to light & dark themes.

## 🛠 How it works

You log in once through a real `claude.ai` WebView. The app harvests the `sessionKey` and `cf_clearance` cookies plus the WebView User-Agent, stores them encrypted on-device, and fetches usage headlessly with `OkHttp`. When a headless fetch hits Cloudflare's challenge, the app silently re-solves it in an off-screen WebView (no interaction needed while `sessionKey` is valid) and retries. A `WorkManager` job refreshes every ~15 minutes. You only sign in again when the long-lived `sessionKey` itself expires, at which point the widget shows a *“Tap to sign in”* state.

Each widget snaps between a **Compact** layout (icon + mini bars + percentages) and a **Full** layout (icon + label + bars with percentage, reset countdown, a weekly pace icon, and a 5H burn-rate line). Both adapt to light and dark themes, and a small amber dot appears when the numbers are stale.

The weekly pace icon projects current usage to the end of the window and uses action-oriented markers: **use more** (blue sparkle) means you'll leave quota unused, **on pace** (green tick) means you'll roughly land at 100%, **slow down** (amber warning) means you'll hit the cap early unless you ease off, and **stop** (red stop sign) means you're likely to exhaust the window well before it resets. It stays hidden for the first 5% of a window, where a short burst would be misleading.

The 5H row adds a **local burn rate** instead: it keeps the last few 5H readings, shows the rate in percentage points per hour, and extends the bar with a translucent segment showing where that rate lands at the 5H reset. The reading interval is whatever the OS delivers — 15 minutes at best, often closer to an hour when Android batches the refresh — so any two readings 10 minutes to 2 hours apart are enough, and readings older than 2 hours are ignored. If the projection reaches 100% before the reset, the line shows `caps in …` and turns amber or red. It shows `collecting` only until there are two fresh readings, `stale` when the newest reading is too old to describe the current pace, and a 5H reset or a real drop in usage starts a new sample window.

**Codex** works the same way, with one extra hop: the harvested `chatgpt.com` session cookie is exchanged for a short-lived access token at `/api/auth/session`, and that token authorises `/backend-api/wham/usage`. The token is never persisted; it is re-minted on every refresh. Window type is derived from `limit_window_seconds` (18000 = 5H, 604800 = 1W), so a plan that puts its weekly cap in the primary slot is still labelled correctly, and a weekly-only plan simply hides the 5H row.

## 🔒 Security notes

Credentials (`sessionKey`, `cf_clearance`, User-Agent, org ID, and the raw `chatgpt.com` cookie) are stored in `EncryptedSharedPreferences` backed by the Android Keystore, and `android:allowBackup="false"` keeps them out of cloud backups. Both session cookies are as sensitive as an account password (anyone who extracts one can act as you), so treat a rooted or compromised device accordingly. No secrets are stored in the repo or baked into the APK.

---

<details>
<summary><b>🧰 Build from source</b></summary>

<br />

You only need this if you want to build the APK yourself instead of downloading it.

**Requirements:** JDK 17, the Android SDK with platform 36, and the bundled Gradle wrapper.

**1. Point Gradle at your Android SDK** by creating `local.properties` in the project root (gitignored):

```properties
sdk.dir=/path/to/your/Android/Sdk
```

**2. Build the debug APK:**

```bash
./gradlew :app:assembleDebug
```

**3. Install it.** The APK lands at `app/build/outputs/apk/debug/app-debug.apk`. Enable USB debugging (Settings → System → Developer options → USB debugging), plug in the phone, and run:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy that APK to the phone and tap it to sideload it directly.

**Project layout:**

| Path | What lives there |
| --- | --- |
| `data` | Endpoint constants, encrypted storage, cookie harvesting, the Cloudflare resolver, and the repositories that fetch Claude and ChatGPT/Codex usage. |
| `auth/LoginActivity.kt` | The shared WebView login (a `provider` extra selects `claude.ai` or `chatgpt.com`). |
| `ui/MainActivity.kt` | The setup / debug screen, with a section per provider. |
| `widget/` | Both Glance widgets (`UsageWidget` for Claude, `CodexWidget` for Codex) and their layouts. |
| `work/` | Background refresh scheduling for both providers. |

</details>
