# Claude + Codex Usage Widget for iPhone & iPad

A single-file [Scriptable](https://scriptable.app) script that puts your **Claude** and **OpenAI Codex** subscription usage on your iPhone or iPad home screen and lock screen — the same `5H` and `1W` percentages, reset countdowns, pace marker and stale indicator as the Android app, with no backend and no Apple Developer account.

<p align="center">
  <a href="claude-usage-widget.js?raw=1"><b>Download the script</b></a>
  ·
  <a href="install.js?raw=1">Installer helper</a>
  ·
  <a href="TROUBLESHOOTING.md">Troubleshooting</a>
  ·
  <a href="https://github.com/chongtzezhao/claude-usage-widget/issues/3">Issue #3</a>
</p>

---

## What you get

- **Rolling windows at a glance.** `5H` and `1W` percentages with a live reset countdown.
- **Pace marker.** Projects current usage to the end of the window: under (blue ↓), on pace (green ✓), over (amber ↑), critical (red ⚠). Hidden in the first 5% of a window.
- **Stale dot.** A small amber dot and "updated 12m ago" when the snapshot is older than ~31 minutes.
- **Two providers, one script.** Show Claude, Codex, or both — per widget.
- **Home screen and lock screen.** Small/medium/large on iPhone, extra large on iPad, plus inline/circular/rectangular lock-screen accessories.
- **Light and dark themes.** Colours follow the system appearance.

## Requirements

- The **free Scriptable app** from the App Store (iOS/iPadOS 15.5 or newer).
- A **Claude Pro or Max** subscription and/or a **ChatGPT plan that includes Codex** (Plus/Pro/Team). This reads consumer subscription usage, not API-key usage.
- **Lock-screen widgets:** iOS 16+ on iPhone, iPadOS 17+ on iPad.
- No Apple Developer account, no Xcode, no Mac, no server. Everything runs on the device.

## Install

### 1. Install Scriptable

Install **Scriptable** from the App Store, then open it once.

### 2. Import the script (pick one)

Scriptable has no “import from URL” button, but its scripts are plain `.js` files, so you can put the file in its folder instead of pasting 2,000 lines.

**Option A — installer script (easiest).**

1. Open [`install.js`](install.js), tap **Raw**, select all, and copy. It is only a short bootstrap.
2. In Scriptable, tap **+**, paste, name it `install`, and tap **Done**.
3. Tap ▶ to run it. It downloads the full widget into Scriptable's script folder and shows **Installed**.
4. Close the alert, open the script list, and run `claude-usage-widget` to sign in. You can delete the `install` script afterwards.

The installer tries `master` first and falls back to the PR branch, so it works before and after the pull request is merged.

**Option B — iCloud Drive (no code at all).**

1. Open the raw widget file in Safari: [claude-usage-widget.js](claude-usage-widget.js?raw=1).
2. Tap **Share** → **Save to Files** → **iCloud Drive** → **Scriptable** → **Save**. Keep the filename `claude-usage-widget.js`.
3. Open Scriptable. The script appears in the list immediately after the folder syncs. If it does not, make sure iCloud Drive / iCloud sync is enabled for Scriptable.

> If Safari insists on saving it as `.txt`, rename it in the **Files** app so it ends in `.js`; Scriptable only lists JavaScript files.

**Option C — copy/paste the full file.** Open [`ios-scriptable/claude-usage-widget.js`](claude-usage-widget.js), tap **Raw**, select all, copy, then in Scriptable tap **+**, paste, and name it `claude-usage-widget`.

> **Reading this in the PR before it merges?** `master` does not contain the file yet, so a `master` raw URL returns **404**. The installer handles this automatically. For manual download, use the PR-branch raw URL:
> <https://raw.githubusercontent.com/chongtzezhao/claude-usage-widget/feat/scriptable-ios-widget/ios-scriptable/claude-usage-widget.js>
> After the PR is merged, the `master` URL works:
> <https://raw.githubusercontent.com/chongtzezhao/claude-usage-widget/master/ios-scriptable/claude-usage-widget.js>

**Option D — ScriptDude (third-party).** If you already use [ScriptDude](https://scriptdu.de/), share the GitHub file to it and it will install/update the script for you. It is not required and not affiliated with this project.

> Keep the name `claude-usage-widget` if you can — the instructions below use it. Any name works, but you will need to select it when configuring the widget.

### 3. Sign in (once per provider, per device)

Open the script in Scriptable and tap ▶ to run it. A welcome menu appears:

- **Sign in to Claude** — the normal `claude.ai` login page opens in a WebView. **Do not use “Continue with Google”**: Google blocks OAuth inside Scriptable’s WebView. Choose **Continue with email** and use your existing Claude account email (including a Gmail address). If Claude offers a verification code, enter it there. If tapping the email link opens Safari instead, copy the link from the email and choose **Paste email sign-in link** in Scriptable. Tap **Done** once the signed-in Claude page is visible.
- **Sign in to Codex** — sign in at `chatgpt.com` with **email and password**. Google sign-in is blocked inside embedded WebViews, so the Google button will not work. Again, tap **Done** once the signed-in ChatGPT page is visible.

You only need one of them; sign in to the other later from the same menu. If the script says **"Not signed in yet"**, run the sign-in again and make sure the signed-in page is fully visible before tapping Done.

> **Claude accounts created with Google:** Claude’s email login still uses the same account email. If necessary, Claude’s support docs explain how to unlink Google and use emailed sign-in links: <https://support.claude.com/en/articles/13189465-log-in-to-your-claude-account>.

### 4. Add the widget

1. Long-press an empty area of the home screen.
2. Tap **+** (top left), search for **Scriptable**, and pick a size.
3. Tap **Add Widget**.
4. Long-press the new widget → **Edit Widget**.
5. Set **Script** to `claude-usage-widget` and, optionally, set **Parameter** (see below).

**Sizes:** small, medium and large on iPhone; extra large on iPad. Medium and large look best with both providers; a medium widget with both shows two side-by-side columns.

**Widget parameter** (optional) chooses which provider(s) that particular widget shows:

| Parameter | Shows |
| --- | --- |
| *(empty)* | Whichever providers are signed in (both if both are) |
| `claude` | Claude only |
| `codex` | Codex only |
| `both` | Both, side by side / stacked |

The parameter is case-insensitive and ignores spaces. If you ask for a provider that is not signed in, the widget falls back to whichever provider *is* signed in. A **small** widget can only fit one provider, so it shows the first selected one (Claude when both are signed in).

## Lock screen (iOS 16+ / iPadOS 17+)

1. Long-press the lock screen → **Customize** → **Lock Screen**.
2. Tap the widget area below the clock → **Add Widgets** → **Scriptable**.
3. Choose **inline**, **circular** or **rectangular**.

Rectangular is the most useful: with one provider signed in it shows both windows with countdowns; with both providers it shows one percentage line per provider. **Inline and circular are compact by design** — inline shows both windows for one provider (or the first window per provider when two are shown), circular shows one provider's `5H` (or `1W` on a weekly-only plan) — so don't expect both windows there. Lock-screen widgets are also cache-only; they never make a live network request.

## How it works

- **Sign-in** opens the real `claude.ai` or `chatgpt.com` page in a Scriptable WebView. When you tap **Done**, the script harvests the provider cookies where WebKit exposes them, stores the session/User-Agent/org id in Scriptable's **Keychain**, and captures the first usage snapshot. The WebView's cookie store is shared with later runs on the same device; if a login ever fails to carry over, the next sign-in fixes it.
- **Fetching** normally uses `Request` with the stored cookies and the WebView User-Agent. When Cloudflare blocks that path, an in-app refresh falls back to a hidden WebView that runs the same `fetch()` from inside the signed-in page (so HttpOnly cookies apply) and retries once. Widget runs never open a WebView.
- **Caching** keeps the non-secret usage snapshot in `claude-usage-widget-cache.json`. Widgets render it instantly; when it is older than 8 minutes a home-screen widget tries one short (8 second) live request and falls back to the cache on any failure. Lock-screen accessories always render the cache.
- **Layouts** are chosen per family: compact for `small`, side-by-side columns for `medium`/`extraLarge`, stacked sections for `large`, and text-only accessories. Bars are drawn at fixed widths so they are never stretched or clipped on iPad.

## Refresh behaviour

Widget refresh is controlled by iOS, not by the script. Scriptable asks iOS for a refresh about every **15 minutes**, but iOS may delay it for battery, low-power mode or how often you look at the widget. The script adds two mitigations:

- **Live fetch when the cache is old.** If the cached snapshot is older than **8 minutes**, a home-screen widget tries a short (8 second) live fetch and falls back to the cached numbers if anything fails.
- **Tap to refresh.** Tapping a widget opens the script's menu, where **Refresh now** forces an update (and can silently re-solve a Cloudflare challenge). The menu also has **Preview widget**, **Sign in again**, **Sign out** and **Help**.

Prefer no taps? Make a Shortcut that runs the script with the text `refresh`:

1. Shortcuts → **+** → add **Text** with the content `refresh`.
2. Add **Run Script** (Scriptable), choose `claude-usage-widget`, and pass the Text as the action's input.
3. Run the Shortcut (or attach it to a personal automation) whenever you want a silent refresh. It updates the cache without showing a menu. **iOS still decides when the widget redraws**, so the new numbers may not appear until the next scheduled widget refresh; opening the app or tapping the widget nudges it.

### Snapshot mode

WebKit sometimes hides the `HttpOnly` session cookie from scripts. The widget can still show the last usage captured during sign-in, but it cannot fetch new numbers in the background. In that case it labels the provider **“Snapshot · updated Xm ago”** and shows **“Open Scriptable → Refresh now”**.

This is common and not an error. Open Scriptable, run `claude-usage-widget`, and choose **Refresh now** whenever you want current numbers; the in-app fallback fetches them through the signed-in WebView. Automatic background refresh requires the cookie to be exposed, which depends on Scriptable/iOS.

## Updating the script

Re-import or overwrite the script with the new version from this folder. Your credentials live in Scriptable's **Keychain on that device**, not in the script, so **you do not need to sign in again** after an update.

Two things to know:

- If you rename the script file, the widget may still point at the old name. Long-press the widget → **Edit Widget** → select the new script name. If it still runs old code, remove and re-add the widget.
- iCloud syncs Scriptable **scripts** between devices, but **not** Keychain credentials. Sign in once on each device.

## Privacy and security

- **Credentials stay on this device.** The Claude `sessionKey`/`cf_clearance` and the `chatgpt.com` session cookie are stored in Scriptable's **Keychain** — never in the repo, the cache file, alerts or logs.
- **The cache is non-secret.** `claude-usage-widget-cache.json` (in Scriptable's local documents folder) holds usage percentages, reset times, refresh timestamps and a little state such as the plan type, organisation id and a short error reason. No cookies, tokens or passwords.
- **No backend.** Requests go straight from your device to `claude.ai` and `chatgpt.com`, using the same endpoints the websites use.
- **Unofficial endpoints.** Anthropic and OpenAI publish no API for consumer subscription usage, so these internal endpoints can change or break at any time. Use this for your own account, at your own risk.
- **Signing out** (menu → **Sign out**) removes that provider's Keychain entries and cached usage from this device. To wipe everything, sign out of both providers and delete the widget/script, or delete the Scriptable app.

## Troubleshooting

Something not working? Start with **[TROUBLESHOOTING.md](TROUBLESHOOTING.md)** — it covers sign-in problems, Cloudflare challenges, stale widgets, "Limited refresh", new devices, updates, iPad layout and Codex quirks.

## Tests (for contributors)

The pure parsing/layout helpers and every widget-family render path are covered by a dependency-free Node test suite (Node 18+):

```bash
cd ios-scriptable
node --check claude-usage-widget.js
node --test test/*.test.mjs
```

`test/logic.test.mjs` covers endpoint parsing, pace/countdown/stale rules, merges and provider selection. `test/render.test.mjs` mocks Scriptable's widget API and builds `small`, `medium`, `large`, `extraLarge`, `accessoryInline`, `accessoryCircular` and `accessoryRectangular` to catch layout regressions. No network access and no credentials are needed.

## Links

- Repository: <https://github.com/chongtzezhao/claude-usage-widget>
- Issue / feature request: <https://github.com/chongtzezhao/claude-usage-widget/issues/3>
- Android app: see the [root README](../README.md)
- Scriptable: <https://scriptable.app>

> Unofficial project, not affiliated with Anthropic or OpenAI. Claude and ChatGPT are trademarks of their respective owners.
