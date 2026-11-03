# Troubleshooting — Claude + Codex Usage Widget (iOS/iPadOS)

Most problems fall into one of four buckets: the **session expired**, **Cloudflare** is blocking a request, **iOS has not refreshed** the widget yet, or **Scriptable could not store the session cookie**. Work through the entry that matches what you see.

Still stuck? Open the script in Scriptable and use **Help** in the menu, then file an issue at <https://github.com/chongtzezhao/claude-usage-widget/issues/3> with your iOS version, device, widget size and (if possible) a screenshot without account details.

---

## "Tap to sign in" even though I'm logged in

**What it means:** the widget is not seeing a usable credential for that provider — the stored session cookie is gone or rejected (HTTP 401, or a redirect to the provider's login page).

**Fix:**

1. Run the script in Scriptable, choose **Sign in again** → the provider, and complete the login.
2. Make sure the **signed-in page is visible before tapping Done**. Scriptable reads the session cookie from the page when the WebView closes; tapping Done too early can store nothing usable.
3. Open the widget's **Refresh now** once afterwards.

**Why sessions expire:** these are ordinary browser sessions, not API keys. Anthropic and OpenAI rotate or invalidate them (password change, "sign out of all devices", long inactivity, security policy). When that happens only a fresh login helps — no amount of refreshing will fix it. Do one provider at a time; the other provider's session is untouched.

## Cloudflare challenge, HTTP 403, or a "Just a moment" page

**What it means:** `claude.ai`/`chatgpt.com` served a bot challenge instead of data. The stored `cf_clearance` cookie has expired, or Cloudflare decided the request looked suspicious.

**Fix:**

1. Open the script in Scriptable and tap **Refresh now**. In-app refreshes can silently load the challenge page in a hidden WebView, re-mint clearance, and retry once — this needs no interaction while the session itself is valid.
2. Wait a minute and try again. Challenges are often transient and rate-limited.
3. If it keeps happening, **sign in again** for that provider (this also refreshes the User-Agent stored alongside the cookie).
4. Meanwhile the widget keeps showing the **last good numbers with the amber stale dot** — that is expected, not data corruption. Widgets never run the WebView repair on their own.

A 403 is *not* treated as a logout, so you should not have to re-authenticate just because of Cloudflare.

## Widget shows old numbers, or an amber dot

**What it means:** the snapshot is older than ~31 minutes (the dot appears) and iOS has not let the widget refresh.

**Fix:**

1. Tap the widget → **Refresh now** (or open the script and run it).
2. Wait — iOS schedules widget refreshes roughly every 15 minutes, but it is a *request*, not a guarantee. Low Power Mode, low battery, Focus modes, a rarely viewed widget or a busy device can delay refreshes substantially. Plugging in and opening the app once usually prompts a refresh.
3. Check that the provider is still signed in (see the first section). After a soft failure the widget deliberately keeps the old numbers rather than blanking out.
4. A Shortcut that runs the script with `refresh` updates the cache, but iOS still chooses when to redraw the widget — it may take until the next scheduled refresh. Opening the app or tapping the widget usually nudges it.

If the dot is showing but the numbers are correct, nothing is broken — the dot only reports age.

## "Limited refresh" alert, or the widget shows cached numbers and won't update

**What it means:** you are signed in, but Scriptable could not expose the provider's session cookie to the script, so background/live refresh is unavailable. The widget can still display the last snapshot captured through the sign-in WebView.

**Fix:**

1. Run the script → **Sign in again** → the provider.
2. Complete the login and **keep the signed-in page open until it has fully loaded**, then tap **Done**. The cookie is harvested while you are on a page of that provider.
3. Try **Refresh now** immediately afterwards. If it succeeds, the next widget render has fresh data.
4. If it still says "Limited refresh", the cookie is `HttpOnly` and was not surfaced in this Scriptable/iOS version. This is common: WebKit often hides the `Cookie` header from `shouldAllowRequest`. You can keep using the widget in snapshot mode: open the app and **Refresh now** whenever you want new numbers.

## Not signed in on a new device, or after restoring from iCloud

**What it means:** Scriptable scripts sync through iCloud, but **credentials do not**. The Keychain is per-device (and is not restored from an iCloud backup).

**Fix:** install Scriptable, let the script sync (or re-import it), then **sign in once per provider on that device**. Nothing is wrong with the account or the script; this is by design so a synced script can never carry your session to another device.

## After an update: the widget still runs the old version

**What it means:** you re-imported the script, but the widget is still configured with the old script name or a cached widget snapshot.

**Fix:**

1. Overwrite the existing script rather than creating a second copy with a different name: open the script in Scriptable, select all, paste the new version, **Done**.
2. Long-press the widget → **Edit Widget** → make sure **Script** is the one you updated.
3. Remove and re-add the widget if it still looks stale (this also clears iOS's cached widget rendering).
4. You do **not** need to sign in again — credentials are in the Keychain, keyed by provider, not stored in the script.

## iPad layout problems: clipped text, missing countdown, extra large

**What it means:** iPad widget geometry differs from iPhone, especially for extra large and for lock-screen accessories.

**Fix:**

1. **Lock screen on iPad requires iPadOS 17 or newer.** On iPadOS 16 there are no lock-screen widgets at all.
2. Resize the widget (long-press → **Edit Widget** → choose a different size) or remove/re-add it so iOS re-measures it.
3. Prefer **medium** or **extra large** when showing both providers. Small is designed for one provider and intentionally omits countdowns.
4. If text is still clipped or the layout looks wrong, open **Preview widget** in the app, pick the same family, and attach a screenshot to the issue — include the iPad model and iPadOS version.

## Codex-specific problems

- **Google sign-in does not work.** `chatgpt.com` blocks Google/OAuth sign-in inside embedded WebViews. Use **email + password** (or another non-Google method) in the sign-in screen.
- **Only a weekly bar shows.** Some plans expose only a weekly cap. That is a real plan shape: the widget hides the `5H` row and shows a "Weekly-only plan" caption instead of a fake bar. Nothing to fix.
- **"No usage limits" or "Open the app to refresh".** "No usage limits" means the provider returned a valid payload with no limit windows (rare). "Open the app to refresh" means the provider is signed in but the **first fetch has not succeeded yet**; open the script → **Refresh now**, and if it keeps failing, sign in again. Check the app menu for the short error (`Session expired`, `Cloudflare challenge`, `Network error`).
- **The percentages differ from the website.** Usage moves continuously; the widget shows the last fetch plus a reset countdown. Refresh once and compare again. A big difference usually means the widget is stale (amber dot).

## Where credentials live, signing out, and deleting all local data

**Where they live:** Claude's `sessionKey`/`cf_clearance`/User-Agent/org id and the raw `chatgpt.com` cookie/User-Agent are stored in **Scriptable's Keychain** (`cuw.*` keys) on that device. Usage numbers and timestamps live in `claude-usage-widget-cache.json` in Scriptable's local documents folder. There is no server and no repo secret.

**Sign out one provider:** open the script → **Sign out** → Claude or Codex. This removes that provider's Keychain entries **and** its cached usage; the other provider is untouched. The widget then shows "Tap to sign in" for the signed-out provider.

**Delete all local data:**

1. **Sign out** of Claude and Codex (removes Keychain entries and cache entries), then delete the widgets and the script in Scriptable.
2. To be certain nothing remains, delete the **Scriptable app** — iOS normally removes its Keychain items and container on uninstall, but signing out first (step 1) is the reliable path.
3. If you had enabled iCloud sync for scripts, delete the script from Scriptable on each device (or from iCloud Drive) so it does not sync back.

## Known limitations

- **Unofficial endpoints.** The script replays the same internal requests as the Claude and Codex usage pages. Anthropic/OpenAI can change them at any time; a future app update may be required. Not a supported integration.
- **iOS controls refresh.** The 15-minute interval is a hint (`refreshAfterDate`). There is no background job and no push; the widget cannot force iOS to wake it.
- **Widgets never open WebViews or prompt for login.** Anything interactive (sign-in, Cloudflare re-solve, preview) only happens when you run the script in the app or via a Shortcut.
- **Scriptable is third-party.** Bugs in Scriptable, iOS widget memory limits, or changes to WebView cookie exposure can affect the widget. Keep Scriptable updated.
- **No backend, by design.** Requests come from your device with your session. There is no hosted service to leak, but also no server-side retry or history.

---

Back to the [install guide](README.md) · [Repository](https://github.com/chongtzezhao/claude-usage-widget) · [Issue #3](https://github.com/chongtzezhao/claude-usage-widget/issues/3)
