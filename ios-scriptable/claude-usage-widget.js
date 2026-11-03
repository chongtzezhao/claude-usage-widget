/**
 * claude-usage-widget.js — Scriptable widget for Claude + Codex usage.
 * Version: 1.0.0
 * Issue: https://github.com/chongtzezhao/claude-usage-widget/issues/3
 * Docs: https://github.com/chongtzezhao/claude-usage-widget/tree/master/ios-scriptable
 *
 * Renders Claude (claude.ai) and Codex (chatgpt.com) subscription usage as
 * iPhone/iPad home-screen widgets and iOS 16 / iPadOS 17 lock-screen
 * accessories. No Apple Developer account, no backend, no native app.
 *
 * SECURITY NOTE: credentials (session cookies, session keys, Cloudflare
 * clearance) are stored in Scriptable's Keychain on this device; never commit
 * or share them. They are never written to the JSON cache file, alerts, logs
 * or error strings.
 *
 * Modes:
 *   - config.runsInWidget  -> home-screen / lock-screen widget rendering.
 *   - config.runsInApp     -> interactive setup / refresh / preview menu.
 *   - Node (module.exports)-> pure helpers for unit tests; no Scriptable APIs.
 *
 * Endpoint behaviour, parsing fallbacks, pace thresholds, countdown
 * formatting and colours mirror the Android app (data/Const.kt,
 * data/CodexConst.kt, data/UsageModels.kt, data/UsageRepository.kt,
 * data/CodexUsageRepository.kt, widget/Pace.kt, widget/TimeFmt.kt).
 */

// ---------------------------------------------------------------------------
// CONFIG — tweakable defaults. config.widgetParameter (long-press -> edit
// widget) overrides the provider set: "claude", "codex" or "both".
// ---------------------------------------------------------------------------
const CONFIG = {
  providers: ["claude", "codex"], // default rendering set when both are signed in
  liveFetchInWidget: true,        // opportunistic live refresh in widget, cache on failure
  widgetFetchTimeoutSeconds: 8,   // Request.timeoutInterval inside widgets
  liveFetchMinCacheAgeMinutes: 8, // only hit network when cache is older than this
  refreshMinutes: 15,             // widget.refreshAfterDate
  staleMinutes: 31,               // amber stale dot / "updated Xm ago"
};

// ---------------------------------------------------------------------------
// Constants (mirrors data/Const.kt and data/CodexConst.kt)
// ---------------------------------------------------------------------------
const CLAUDE = {
  BASE: "https://claude.ai",
  ORGS_URL: "https://claude.ai/api/organizations",
  LOGIN_URL: "https://claude.ai/login",
  CHALLENGE_URL: "https://claude.ai/new", // page loaded to re-solve the CF challenge
};

const CODEX = {
  BASE: "https://chatgpt.com",
  LOGIN_URL: "https://chatgpt.com/auth/login",
  SESSION_URL: "https://chatgpt.com/api/auth/session",
  USAGE_URL: "https://chatgpt.com/backend-api/wham/usage",
  // Older/alternate path seen in the wild; used only if the primary 404s.
  USAGE_FALLBACK_URL: "https://chatgpt.com/backend-api/codex/usage",
  CHALLENGE_URL: "https://chatgpt.com/",
  REFERER: "https://chatgpt.com/codex/settings/usage",
};

// NextAuth rotates its session-cookie name across Auth.js versions; the
// chunked `.0` / `.1` forms stay inside the raw cookie string (never rebuilt).
const CODEX_SESSION_COOKIES = [
  "__Secure-next-auth.session-token",
  "__Secure-authjs.session-token",
  "__Host-next-auth.session-token",
];

const PROVIDER_META = {
  claude: { name: "Claude usage", short: "Claude", sf: "sparkles", glyph: "\u2733" },
  codex: {
    name: "Codex usage",
    short: "Codex",
    sf: "chevron.left.forwardslash.chevron.right",
    glyph: "{ }",
  },
};

// Pace glyph mapping (Android's pace_* drawables -> SF Symbols + text fallback).
const PACE_SYMBOLS = {
  under: { sf: "arrow.down", glyph: "\u2193" },
  on_track: { sf: "checkmark", glyph: "\u2713" },
  over: { sf: "arrow.up", glyph: "\u2191" },
  critical: { sf: "exclamationmark.triangle.fill", glyph: "!" },
};

// Colours from res/values/colors.xml and values-night/colors.xml.
const COLORS = {
  accent: { claude: { light: "#D97757", dark: "#E8896B" }, codex: { light: "#10A37F", dark: "#2FBF9A" } },
  track: { light: "#DEDEDE", dark: "#3E3E3E" }, // pre-blended alpha track (see drawBar)
  tick: { light: "#A6A6A6", dark: "#7A7A7A" },  // pre-blended on-pace tick
  stale: { light: "#E0A000", dark: "#FFC233" },
  pace: {
    under: { light: "#3B82F6", dark: "#60A5FA" },
    on_track: { light: "#16A34A", dark: "#4ADE80" },
    over: { light: "#D97706", dark: "#FBBF24" },
    critical: { light: "#DC2626", dark: "#F87171" },
  },
  background: { light: "#FFFFFF", dark: "#0E0E0E" },
  textPrimary: { light: "#1A1A1A", dark: "#F2F2F2" },
  textSecondary: { light: "#6B7280", dark: "#9CA3AF" },
};

// ---------------------------------------------------------------------------
// Pure helpers (exported for Node tests). No Scriptable APIs below this point
// until the "Device & storage" section.
// ---------------------------------------------------------------------------

/** Parses "#RRGGBB" into [r, g, b]; returns [128, 128, 128] on failure. */
function hexToRgb(hex) {
  const m = /^#?([0-9a-fA-F]{6})$/.exec(String(hex || ""));
  if (!m) return [128, 128, 128];
  const n = parseInt(m[1], 16);
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255];
}

/** Blends a foreground hex at `alpha` over a background hex; used to avoid
 *  alpha-channel Color APIs for DrawContext fills. */
function blendHex(fgHex, alpha, bgHex) {
  const fg = hexToRgb(fgHex);
  const bg = hexToRgb(bgHex);
  const out = fg.map((c, i) => Math.round(bg[i] + alpha * (c - bg[i])));
  return "#" + out.map((c) => ("0" + Math.max(0, Math.min(255, c)).toString(16)).slice(-2)).join("");
}

/**
 * Parses RFC3339 / ISO-8601 timestamps with fractional seconds (up to 9
 * digits) and "Z", "±HH:MM" or "±HHMM" offsets, e.g.
 * "2026-06-20T20:00:00.483321+00:00". Returns epoch ms, or 0 when invalid.
 */
function parseIso8601Ms(value) {
  const str = String(value || "").trim();
  if (!str) return 0;
  const m = /^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d{1,9})?(Z|z|[+-]\d{2}:?\d{2})?$/.exec(str);
  if (!m) return 0;
  const y = +m[1], mo = +m[2], d = +m[3], h = +m[4], mi = +m[5], s = +m[6];
  if (mo < 1 || mo > 12 || d < 1 || d > 31 || h > 23 || mi > 59 || s > 59) return 0;
  let ms = 0;
  if (m[7]) {
    // Truncate to milliseconds; never let .999999 roll into the next second.
    const digits = (m[7].slice(1) + "000").slice(0, 3);
    ms = parseInt(digits, 10) || 0;
  }
  const base = Date.UTC(y, mo - 1, d, h, mi, s, ms);
  if (!isFinite(base)) return 0;
  // Reject rolled-over dates like 2026-02-30 or 2026-13-01.
  const check = new Date(base);
  if (check.getUTCFullYear() !== y || check.getUTCMonth() !== mo - 1 || check.getUTCDate() !== d) {
    return 0;
  }
  let offsetMin = 0;
  if (m[8] && m[8] !== "Z" && m[8] !== "z") {
    const sign = m[8].charAt(0) === "-" ? -1 : 1;
    const digits = m[8].slice(1).replace(":", "");
    const offHours = +digits.slice(0, 2);
    const offMins = +digits.slice(2, 4);
    if (offHours > 23 || offMins > 59) return 0;
    offsetMin = sign * (offHours * 60 + offMins);
  }
  const epoch = base - offsetMin * 60000;
  return isFinite(epoch) ? epoch : 0;
}

/** One Claude window from `five_hour` / `seven_day`. Missing/invalid fields
 *  fall back to util 0 and reset 0 (Android UsageSnapshot.parse). */
function claudeWindowFrom(obj) {
  if (!obj || typeof obj !== "object") return null;
  let util = Number(obj.utilization);
  if (!isFinite(util) || util < 0) util = 0;
  return { util: util, resetsAt: parseIso8601Ms(obj.resets_at) };
}

/** Parses the Claude /usage payload -> {fiveHour, sevenDay, fetchedAt}. */
function parseClaudeUsage(body, now) {
  let root;
  try {
    root = JSON.parse(body);
  } catch (e) {
    throw new Error("invalid json");
  }
  if (!root || typeof root !== "object" || Array.isArray(root)) {
    throw new Error("unexpected shape");
  }
  // A payload with neither window is not a usable snapshot; throwing here
  // keeps the merge helpers from overwriting the last good numbers.
  const hasFive = root.five_hour && typeof root.five_hour === "object";
  const hasWeek = root.seven_day && typeof root.seven_day === "object";
  if (!hasFive && !hasWeek) throw new Error("missing usage windows");
  return {
    fiveHour: claudeWindowFrom(root.five_hour),
    sevenDay: claudeWindowFrom(root.seven_day),
    fetchedAt: now,
  };
}

/** One Codex window; valid only when used_percent is a number >= 0 (clamped
 *  0..100). reset_at: >1e11 means ms, >0 means seconds, else
 *  reset_after_seconds >= 0 -> now + seconds, else 0. */
function codexWindowFrom(obj, now) {
  if (!obj || typeof obj !== "object") return null;
  if (!("used_percent" in obj)) return null;
  const raw = obj.used_percent;
  // Number(null) and Number("") are both 0; reject those rather than showing
  // a fake 0% bar for a malformed window.
  if (raw === null || raw === undefined || raw === "") return null;
  const used = Number(raw);
  if (!isFinite(used) || used < 0) return null;
  let resetMs = 0;
  const resetAt = Number(obj.reset_at);
  if (obj.reset_at !== undefined && obj.reset_at !== null && isFinite(resetAt)) {
    // Some cohorts have been observed returning milliseconds.
    if (resetAt > 100000000000) resetMs = Math.round(resetAt);
    else if (resetAt > 0) resetMs = Math.round(resetAt * 1000);
  }
  if (resetMs <= 0) {
    const after = Number(obj.reset_after_seconds);
    if (isFinite(after) && after >= 0) resetMs = now + after * 1000;
  }
  return { util: Math.min(100, Math.max(0, used)), resetsAt: resetMs };
}

/**
 * Parses the Codex /backend-api/wham/usage payload exactly like Android's
 * CodexSnapshot.parse. Throws "missing rate-limit shape" when the 200 body
 * has no rate_limit / primary_window / secondary_window so it cannot
 * overwrite good data.
 */
function parseCodexUsage(body, now) {
  let root;
  try {
    root = JSON.parse(body);
  } catch (e) {
    throw new Error("invalid json");
  }
  if (!root || typeof root !== "object") throw new Error("missing rate-limit shape");
  const rateLimit = root.rate_limit && typeof root.rate_limit === "object" ? root.rate_limit : null;
  if (!root.rate_limit && !root.primary_window && !root.secondary_window) {
    throw new Error("missing rate-limit shape");
  }
  const candidates = [];
  const primary = (rateLimit && rateLimit.primary_window) || root.primary_window;
  const secondary = (rateLimit && rateLimit.secondary_window) || root.secondary_window;
  if (primary && typeof primary === "object") candidates.push({ isPrimary: true, w: primary });
  if (secondary && typeof secondary === "object") candidates.push({ isPrimary: false, w: secondary });

  let fiveHour = null;
  let weekly = null;
  let weeklySeconds = 604800;
  let other = null;

  candidates.forEach(function (c) {
    const parsed = codexWindowFrom(c.w, now);
    if (!parsed) return;
    let seconds = -1; // missing/invalid -> legacy positional handling
    if (c.w.limit_window_seconds !== undefined && c.w.limit_window_seconds !== null) {
      const n = Number(c.w.limit_window_seconds);
      if (isFinite(n)) seconds = n;
    }
    if (seconds >= 1 && seconds <= 21600) fiveHour = parsed;          // ~5 hours
    else if (seconds >= 518400) { weekly = parsed; weeklySeconds = seconds; } // >= 6 days
    else if (seconds < 0 && c.isPrimary) fiveHour = parsed;           // legacy positional
    else if (seconds < 0) weekly = parsed;
    else if (!other) other = { seconds: seconds, window: parsed };
  });

  // A single monthly window (free tier) still gets a bar, labelled by duration.
  if (!fiveHour && !weekly && other) {
    weekly = other.window;
    weeklySeconds = other.seconds;
  }

  let planType = "";
  if (typeof root.plan_type === "string") planType = root.plan_type;

  return {
    fiveHour: fiveHour,
    weekly: weekly,
    weeklyWindowSeconds: weeklySeconds,
    planType: planType,
    fetchedAt: now,
  };
}

const B64_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

/** Pure base64url decoder (works in JavaScriptCore and Node alike). */
function base64UrlDecode(input) {
  let s = String(input || "").replace(/-/g, "+").replace(/_/g, "/");
  while (s.length % 4 !== 0) s += "=";
  let out = "";
  let buffer = 0;
  let bits = 0;
  for (let i = 0; i < s.length; i++) {
    const ch = s.charAt(i);
    if (ch === "=") break;
    const idx = B64_CHARS.indexOf(ch);
    if (idx < 0) continue;
    buffer = (buffer << 6) | idx;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out += String.fromCharCode((buffer >> bits) & 0xff);
    }
  }
  try {
    return decodeURIComponent(escape(out)); // UTF-8 decode; escape() exists in JSC & Node
  } catch (e) {
    return out;
  }
}

/** Reads chatgpt_account_id from the unverified JWT payload, when present. */
function decodeJwtAccountId(token) {
  try {
    const parts = String(token || "").split(".");
    if (parts.length < 2 || !parts[1]) return "";
    const json = JSON.parse(base64UrlDecode(parts[1]));
    const auth = json && json["https://api.openai.com/auth"];
    if (auth && typeof auth === "object") {
      const id = auth.chatgpt_account_id;
      if (typeof id === "string" && id && id !== "null") return id;
    }
    return "";
  } catch (e) {
    return "";
  }
}

/** Reset countdown, e.g. "3h 12m", "5d 2h", "12m", "<1m", "now" or "-". */
function resetsIn(resetAtMs, now) {
  if (!resetAtMs || resetAtMs <= 0) return "-";
  let s = Math.floor((resetAtMs - now) / 1000);
  if (s <= 0) return "now";
  const d = Math.floor(s / 86400); s %= 86400;
  const h = Math.floor(s / 3600); s %= 3600;
  const m = Math.floor(s / 60);
  if (d > 0) return d + "d " + h + "h";
  if (h > 0) return h + "h " + m + "m";
  if (m > 0) return m + "m";
  return "<1m";
}

/** Snapshot is stale once older than ~2 refresh cycles (default 31 min). */
function isStale(fetchedAt, now, staleMinutes) {
  if (!fetchedAt || fetchedAt <= 0) return true;
  const minutes = typeof staleMinutes === "number" ? staleMinutes : CONFIG.staleMinutes;
  return now - fetchedAt > minutes * 60000;
}

/** Elapsed fraction of the current window, or null when unknown/inactive. */
function elapsedFraction(resetAtMs, windowSeconds, now) {
  if (!resetAtMs || resetAtMs <= 0 || !windowSeconds || windowSeconds <= 0) return null;
  const start = resetAtMs - windowSeconds * 1000;
  if (now <= start || now >= resetAtMs) return null;
  return (now - start) / (windowSeconds * 1000);
}

/**
 * Projects current usage to the end of the window and classifies the pace.
 * Returns null when the window is unknown, not started, already reset, or
 * during its first 5% (too noisy to be meaningful). Thresholds match
 * Android widget/TimeFmt.pace exactly.
 */
function pace(usedPct, resetAtMs, windowSeconds, now) {
  if (!resetAtMs || resetAtMs <= 0 || !windowSeconds || windowSeconds <= 0) return null;
  const windowMillis = windowSeconds * 1000.0;
  if (windowMillis <= 0) return null;
  const start = resetAtMs - windowMillis;
  if (now <= start || now >= resetAtMs) return null;
  const elapsed = (now - start) / windowMillis;
  if (elapsed < 0.05) return null;
  const used = Math.min(100, Math.max(0, Number(usedPct) || 0));
  const projected = used / elapsed; // percentage at reset
  if (used >= 90.0 && elapsed < 1.0) return "critical";
  if (projected < 85.0) return "under";
  if (projected <= 105.0) return "on_track";
  if (projected <= 125.0) return "over";
  return "critical";
}

/** Human label for a rate-limit window duration; Codex plans vary by tier. */
function windowLabel(seconds) {
  if (!seconds || seconds <= 0 || seconds === 604800) return "1W";
  if (seconds % 86400 === 0) return (seconds / 86400) + "D";
  if (seconds % 3600 === 0) return (seconds / 3600) + "H";
  return "1W";
}

/** "updated Xm ago" fragment: "just now", "5m ago", "3h ago", "2d ago", "". */
function updatedAgo(fetchedAt, now) {
  if (!fetchedAt || fetchedAt <= 0) return "";
  const s = Math.floor((now - fetchedAt) / 1000);
  if (s < 60) return "just now";
  const m = Math.floor(s / 60);
  if (m < 60) return m + "m ago";
  const h = Math.floor(m / 60);
  if (h < 24) return h + "h ago";
  return Math.floor(h / 24) + "d ago";
}

/** Parses a raw Cookie header into a name -> value map. */
function parseCookieHeader(raw) {
  const map = {};
  String(raw || "").split(";").forEach(function (part) {
    const i = part.indexOf("=");
    if (i <= 0) return;
    const name = part.slice(0, i).trim();
    const value = part.slice(i + 1).trim();
    if (name) map[name] = value;
  });
  return map;
}

/** True when `name` is present, including NextAuth's chunked `name.0` form. */
function hasSessionCookie(raw, name) {
  return String(raw || "").split(";").some(function (part) {
    const c = part.trim();
    return c.indexOf(name + "=") === 0 || c.indexOf(name + ".") === 0;
  });
}

/** Reads a cookie value, joining NextAuth's optional `.0`, `.1`, ... chunks. */
function chunkedCookieValue(map, name) {
  if (map[name]) return map[name];
  const chunks = [];
  for (const key in map) {
    if (key.indexOf(name + ".") === 0) {
      const n = parseInt(key.slice(name.length + 1), 10);
      if (!isNaN(n)) chunks.push({ n: n, v: map[key] });
    }
  }
  if (chunks.length === 0) return "";
  chunks.sort(function (a, b) { return a.n - b.n; });
  return chunks.map(function (c) { return c.v; }).join("");
}

function hasProviderSession(raw, provider) {
  const str = String(raw || "");
  if (provider === "claude") return hasSessionCookie(str, "sessionKey");
  return CODEX_SESSION_COOKIES.some(function (name) { return hasSessionCookie(str, name); });
}

/** Cloudflare HTML/challenge heuristic (Android looksLikeHtml + isCloudflare). */
function looksLikeHtml(body) {
  const str = String(body || "");
  const trimmed = str.replace(/^\s+/, "");
  return trimmed.charAt(0) === "<" || str.toLowerCase().indexOf("just a moment") !== -1;
}

/**
 * Pure layout decision helper: which providers go where for a given family.
 * Returns {mode, providers, compact, wide} so Node tests can assert
 * iPhone/iPad layout decisions without Scriptable.
 */
function planLayout(family, providerIds) {
  const ids = Array.isArray(providerIds) ? providerIds.filter(function (id) { return !!id; }) : [];
  const both = ids.length >= 2;
  const one = [ids[0]].filter(function (id) { return !!id; });
  switch (family) {
    case "small":
      return { mode: "compact", providers: one, compact: true, wide: false };
    case "medium":
      if (both) return { mode: "columns", providers: ids.slice(0, 2), compact: false, wide: false };
      return { mode: "full", providers: one, compact: false, wide: false };
    case "large":
      if (both) return { mode: "stacked", providers: ids.slice(0, 2), compact: false, wide: false };
      return { mode: "full", providers: one, compact: false, wide: false };
    case "extraLarge": // iPad
      if (both) return { mode: "columns", providers: ids.slice(0, 2), compact: false, wide: true };
      return { mode: "full", providers: one, compact: false, wide: true };
    case "accessoryRectangular":
      return { mode: "accessoryRectangular", providers: ids.slice(0, 2), compact: false, wide: false };
    case "accessoryInline":
      return { mode: "accessoryInline", providers: ids.slice(0, 2), compact: false, wide: false };
    case "accessoryCircular":
      return { mode: "accessoryCircular", providers: one, compact: false, wide: false };
    default:
      return { mode: "full", providers: one, compact: false, wide: false };
  }
}

// ---------------------------------------------------------------------------
// Snapshot cache shape + merge helpers
// ---------------------------------------------------------------------------

function emptyClaudeEntry() {
  return { fetchedAt: 0, fiveHour: null, sevenDay: null, orgId: "", authState: "ok", error: "" };
}

function emptyCodexEntry() {
  return {
    fetchedAt: 0, fiveHour: null, weekly: null,
    weeklyWindowSeconds: 604800, planType: "", authState: "ok", error: "",
  };
}

function emptyEntry(provider) {
  return provider === "claude" ? emptyClaudeEntry() : emptyCodexEntry();
}

function emptyCache() {
  return { version: 1, claude: emptyClaudeEntry(), codex: emptyCodexEntry() };
}

function normWindow(w) {
  if (!w || typeof w !== "object") return null;
  const util = Number(w.util);
  if (!isFinite(util)) return null;
  const resetsAt = Number(w.resetsAt);
  return {
    util: Math.min(100, Math.max(0, util)),
    resetsAt: isFinite(resetsAt) && resetsAt > 0 ? resetsAt : 0,
  };
}

/** Normalises a cache entry so only known, non-secret fields survive. */
function normalizeClaudeEntry(e) {
  const out = emptyClaudeEntry();
  if (!e || typeof e !== "object") return out;
  out.fetchedAt = Number(e.fetchedAt) || 0;
  out.fiveHour = normWindow(e.fiveHour);
  out.sevenDay = normWindow(e.sevenDay);
  out.orgId = typeof e.orgId === "string" ? e.orgId : "";
  out.authState = e.authState === "needs_login" ? "needs_login" : "ok";
  out.error = typeof e.error === "string" ? e.error : "";
  return out;
}

function normalizeCodexEntry(e) {
  const out = emptyCodexEntry();
  if (!e || typeof e !== "object") return out;
  out.fetchedAt = Number(e.fetchedAt) || 0;
  out.fiveHour = normWindow(e.fiveHour);
  out.weekly = normWindow(e.weekly);
  const secs = Number(e.weeklyWindowSeconds);
  out.weeklyWindowSeconds = isFinite(secs) && secs > 0 ? secs : 604800;
  out.planType = typeof e.planType === "string" ? e.planType : "";
  out.authState = e.authState === "needs_login" ? "needs_login" : "ok";
  out.error = typeof e.error === "string" ? e.error : "";
  return out;
}

/**
 * Merges a fetch result into the Claude cache entry. Success replaces the
 * windows; needs_login updates the auth state; a soft/transient failure keeps
 * the last good snapshot (and its old fetchedAt, so the stale dot appears)
 * and records a short, non-secret error reason.
 */
function mergeClaudeSnapshot(prev, result, now) {
  const base = prev && typeof prev === "object" ? normalizeClaudeEntry(prev) : emptyClaudeEntry();
  if (result && result.kind === "ok" && result.snapshot) {
    return {
      fetchedAt: now,
      fiveHour: normWindow(result.snapshot.fiveHour),
      sevenDay: normWindow(result.snapshot.sevenDay),
      orgId: result.snapshot.orgId || base.orgId,
      authState: "ok",
      error: "",
    };
  }
  if (result && result.kind === "needs_login") {
    const out = Object.assign({}, base, { authState: "needs_login", error: "needs_login" });
    return out;
  }
  return Object.assign({}, base, {
    authState: base.authState,
    error: (result && result.reason) || "error",
  });
}

/** Same merge contract as mergeClaudeSnapshot, for the Codex entry. */
function mergeCodexSnapshot(prev, result, now) {
  const base = prev && typeof prev === "object" ? normalizeCodexEntry(prev) : emptyCodexEntry();
  if (result && result.kind === "ok" && result.snapshot) {
    return {
      fetchedAt: now,
      fiveHour: normWindow(result.snapshot.fiveHour),
      weekly: normWindow(result.snapshot.weekly),
      weeklyWindowSeconds: result.snapshot.weeklyWindowSeconds || base.weeklyWindowSeconds,
      planType: result.snapshot.planType || base.planType,
      authState: "ok",
      error: "",
    };
  }
  if (result && result.kind === "needs_login") {
    return Object.assign({}, base, { authState: "needs_login", error: "needs_login" });
  }
  return Object.assign({}, base, {
    authState: base.authState,
    error: (result && result.reason) || "error",
  });
}

/**
 * Resolves the widget parameter against the signed-in providers.
 * "claude" / "codex" / "both" (case-insensitive, trimmed) select explicitly;
 * invalid or missing falls back to CONFIG.providers filtered to what is
 * signed in. If the requested set has nothing signed in, whatever IS signed
 * in is shown instead.
 */
function selectProviders(widgetParameter, signedInIds) {
  const signed = Array.isArray(signedInIds)
    ? signedInIds.filter(function (id) { return id === "claude" || id === "codex"; })
    : [];
  const p = typeof widgetParameter === "string" ? widgetParameter.trim().toLowerCase() : "";
  let wanted;
  if (p === "claude" || p === "codex") wanted = [p];
  else if (p === "both") wanted = ["claude", "codex"];
  else wanted = CONFIG.providers.slice();
  const filtered = wanted.filter(function (id) { return signed.indexOf(id) !== -1; });
  if (filtered.length > 0) return filtered;
  return signed.slice();
}

// ---------------------------------------------------------------------------
// Device & storage — Scriptable-only code below this point.
// ---------------------------------------------------------------------------

/** Thin Keychain wrapper; tolerates a missing key and never throws. Values
 *  are device-only strings (sessionKey, cf_clearance, raw cookie, UA, org). */
const KeyStore = {
  get: function (key) {
    try {
      return Keychain.contains(key) ? Keychain.get(key) : "";
    } catch (e) {
      return "";
    }
  },
  set: function (key, value) {
    try {
      if (Keychain.contains(key)) Keychain.remove(key);
      Keychain.set(key, String(value));
    } catch (e) { /* best effort */ }
  },
  remove: function (key) {
    try {
      if (Keychain.contains(key)) Keychain.remove(key);
    } catch (e) { /* best effort */ }
  },
};

const KEYS = {
  claude: {
    sessionKey: "cuw.claude.sessionKey",
    cfClearance: "cuw.claude.cf_clearance",
    cookie: "cuw.claude.cookie", // full Cookie header, as harvested
    userAgent: "cuw.claude.userAgent",
    orgId: "cuw.claude.orgId",
  },
  codex: {
    cookie: "cuw.codex.cookie", // full jar string; NextAuth .0/.1 chunks stay intact
    cfClearance: "cuw.codex.cf_clearance",
    userAgent: "cuw.codex.userAgent",
  },
};

function keyGet(key) { return KeyStore.get(key); }
function keySet(key, value) { KeyStore.set(key, value); }
function keyRemove(key) { KeyStore.remove(key); }

function hasClaudeCreds() {
  return !!keyGet(KEYS.claude.sessionKey);
}

function hasCodexCreds() {
  return hasProviderSession(keyGet(KEYS.codex.cookie), "codex");
}

function signedInProviderIds() {
  const ids = [];
  if (hasClaudeCreds()) ids.push("claude");
  if (hasCodexCreds()) ids.push("codex");
  return ids;
}

/** True when the provider's session credential actually landed in Keychain. */
function cookiesHarvested(provider) {
  return provider === "claude" ? hasClaudeCreds() : hasCodexCreds();
}

/**
 * Merges a freshly observed cookie string (from the WebView's Cookie request
 * header or document.cookie) into the stored full cookie string, then
 * extracts the provider-specific credentials. Header names arrive in
 * whatever case the request used, so merging is by parsed name.
 */
function mergeStoredCookie(provider, incoming) {
  try {
    const key = provider === "claude" ? KEYS.claude.cookie : KEYS.codex.cookie;
    const merged = Object.assign({}, parseCookieHeader(keyGet(key)), parseCookieHeader(incoming));
    const parts = [];
    for (const name in merged) {
      if (merged[name]) parts.push(name + "=" + merged[name]);
    }
    if (parts.length > 0) keySet(key, parts.join("; "));

    if (provider === "claude") {
      const sessionKey = chunkedCookieValue(merged, "sessionKey");
      if (sessionKey) keySet(KEYS.claude.sessionKey, sessionKey);
      if (merged["cf_clearance"]) keySet(KEYS.claude.cfClearance, merged["cf_clearance"]);
      // Best-effort org id fallback straight from the cookie.
      if (merged["lastActiveOrg"] && !keyGet(KEYS.claude.orgId)) {
        keySet(KEYS.claude.orgId, merged["lastActiveOrg"]);
      }
    } else if (merged["cf_clearance"]) {
      keySet(KEYS.codex.cfClearance, merged["cf_clearance"]);
    }
  } catch (e) { /* harvesting must never break navigation */ }
}

// ---------------------------------------------------------------------------
// JSON snapshot cache (FileManager). Never contains cookies or tokens.
// ---------------------------------------------------------------------------

const CACHE_FILE_NAME = "claude-usage-widget-cache.json";

function cacheFilePath() {
  const fm = FileManager.local();
  return fm.joinPath(fm.documentsDirectory(), CACHE_FILE_NAME);
}

/** Corrupt/missing cache -> empty cache. Never throws into rendering. */
function loadCache() {
  try {
    const fm = FileManager.local();
    const path = cacheFilePath();
    if (!fm.fileExists(path)) return emptyCache();
    const parsed = JSON.parse(fm.readString(path));
    if (!parsed || typeof parsed !== "object") return emptyCache();
    const cache = emptyCache();
    if (parsed.claude && typeof parsed.claude === "object") {
      cache.claude = normalizeClaudeEntry(parsed.claude);
    }
    if (parsed.codex && typeof parsed.codex === "object") {
      cache.codex = normalizeCodexEntry(parsed.codex);
    }
    return cache;
  } catch (e) {
    return emptyCache();
  }
}

/** Atomic-ish write: serialized to a temp file then moved into place; any
 *  failure is swallowed so rendering continues with in-memory data. */
function saveCache(cache) {
  try {
    const fm = FileManager.local();
    const dir = fm.documentsDirectory();
    const target = fm.joinPath(dir, CACHE_FILE_NAME);
    const temp = fm.joinPath(dir, CACHE_FILE_NAME + ".tmp");
    const json = JSON.stringify({
      version: 1,
      claude: normalizeClaudeEntry(cache && cache.claude),
      codex: normalizeCodexEntry(cache && cache.codex),
    });
    fm.writeString(temp, json);
    if (fm.fileExists(target)) fm.remove(target);
    fm.move(temp, target);
  } catch (e) { /* keep going; stale data is better than a crash */ }
}

function clearCacheEntry(provider) {
  const cache = loadCache();
  cache[provider] = emptyEntry(provider);
  saveCache(cache);
}

// ---------------------------------------------------------------------------
// Networking (Scriptable Request) + shared result helpers
// ---------------------------------------------------------------------------

/** Lower-cased view of a response header dictionary. */
function lowerKeys(headers) {
  const out = {};
  if (headers) {
    for (const k in headers) out[String(k).toLowerCase()] = headers[k];
  }
  return out;
}

function isRedirectCode(code) {
  return code >= 300 && code < 400;
}

/** True when the final URL landed on the provider's login page. */
function landedOnLogin(finalUrl, provider) {
  const url = String(finalUrl || "");
  return provider === "claude"
    ? url.indexOf("/login") !== -1
    : url.indexOf("/auth/login") !== -1;
}

/** Android's isCloudflare: 401/403/503 plus a challenge marker or HTML body. */
function isCloudflareStatus(code, body, headers) {
  if (code !== 401 && code !== 403 && code !== 503) return false;
  if (headers && String(headers["cf-mitigated"] || "") === "challenge") return true;
  return looksLikeHtml(body);
}

/**
 * Performs a GET via Scriptable Request. Never throws: network failures come
 * back as {kind:"exception"} so callers can map them to transient results.
 */
async function httpRequest(url, headers, timeoutSeconds) {
  const req = new Request(url);
  try {
    req.timeoutInterval = timeoutSeconds || 20;
    if (headers) req.headers = headers;
  } catch (e) { /* defaults are fine */ }
  let body = "";
  try {
    body = (await req.loadString()) || "";
  } catch (e) {
    return { kind: "exception", error: "network" };
  }
  const resp = req.response || {};
  return {
    kind: "ok",
    code: resp.statusCode || 0,
    body: body,
    finalUrl: resp.url || url,
    headers: lowerKeys(resp.headers),
  };
}

/** Races a promise against a Scriptable Timer so nothing can hang the script. */
function withTimeout(promise, ms, fallback) {
  return new Promise(function (resolve) {
    let settled = false;
    let timer = null;
    const finish = function (value) {
      if (settled) return;
      settled = true;
      if (timer) { try { timer.invalidate(); } catch (e) {} }
      resolve(value);
    };
    try {
      // Scriptable's Timer.schedule interval is in milliseconds.
      timer = Timer.schedule(Math.max(1, ms), false, function () { finish(fallback); });
    } catch (e) { /* fall through to promise-only path */ }
    Promise.resolve(promise).then(
      function (v) { finish(v); },
      function () { finish(fallback); }
    );
  });
}

/** Loads a URL in a WebView and converts loadURL's undefined-on-success
 *  resolution into a boolean, with a hard timeout. */
async function loadWebView(wv, url, timeoutMs) {
  try {
    const loaded = await withTimeout(
      wv.loadURL(url).then(function () { return true; }),
      timeoutMs,
      false
    );
    return loaded === true;
  } catch (e) {
    return false;
  }
}

// ---------------------------------------------------------------------------
// Claude fetcher (mirrors data/UsageRepository.kt)
// ---------------------------------------------------------------------------

function claudeRequestHeaders(sessionKey, cfClearance, userAgent) {
  const headers = { Accept: "*/*" };
  let cookie = "sessionKey=" + sessionKey;
  if (cfClearance) cookie += "; cf_clearance=" + cfClearance;
  headers["Cookie"] = cookie;
  if (userAgent) headers["User-Agent"] = userAgent;
  return headers;
}

/** Org discovery: prefer an entry whose capabilities include chat/claude_ai. */
function pickClaudeOrg(arr) {
  if (!Array.isArray(arr) || arr.length === 0) return "";
  for (let i = 0; i < arr.length; i++) {
    const o = arr[i];
    if (!o || typeof o !== "object") continue;
    const caps = o.capabilities;
    if (Array.isArray(caps)) {
      const isChat = caps.some(function (c) {
        const s = String(c).toLowerCase();
        return s.indexOf("chat") !== -1 || s.indexOf("claude_ai") !== -1;
      });
      if (isChat) return String(o.uuid || o.id || "");
    }
  }
  const first = arr[0] || {};
  return String(first.uuid || first.id || "");
}

/** Maps any HTTP outcome to a FetchResult-style object (Android getUsage). */
function claudeStatusResult(code, body, finalUrl) {
  if (code === 401 || landedOnLogin(finalUrl, "claude")) {
    return { kind: "needs_login" };
  }
  if (isRedirectCode(code)) return { kind: "soft", reason: "http-" + code };
  if (code === 200) return null; // handled by caller
  if (code === 403 || code === 503 || looksLikeHtml(body)) {
    return { kind: "soft", reason: "cloudflare" };
  }
  return { kind: "soft", reason: "http-" + code };
}

async function discoverClaudeOrg(sessionKey, cfClearance, userAgent, timeoutSeconds) {
  const r = await httpRequest(
    CLAUDE.ORGS_URL,
    claudeRequestHeaders(sessionKey, cfClearance, userAgent),
    timeoutSeconds
  );
  if (r.kind === "exception") return { kind: "soft", reason: "network" };
  const status = claudeStatusResult(r.code, r.body, r.finalUrl);
  if (status) return status;
  try {
    const orgId = pickClaudeOrg(JSON.parse(r.body));
    if (orgId) return { kind: "ok", orgId: orgId };
  } catch (e) { /* fall through */ }
  return { kind: "soft", reason: "org-unknown" };
}

async function claudeGetUsage(orgId, sessionKey, cfClearance, userAgent, timeoutSeconds) {
  const url = CLAUDE.BASE + "/api/organizations/" + encodeURIComponent(orgId) + "/usage";
  const r = await httpRequest(
    url,
    claudeRequestHeaders(sessionKey, cfClearance, userAgent),
    timeoutSeconds
  );
  if (r.kind === "exception") return { kind: "soft", reason: "network" };
  const status = claudeStatusResult(r.code, r.body, r.finalUrl);
  if (status) return status;
  try {
    const snapshot = parseClaudeUsage(r.body, Date.now());
    snapshot.orgId = orgId;
    return { kind: "ok", snapshot: snapshot };
  } catch (e) {
    return { kind: "soft", reason: "parse" };
  }
}

/**
 * Fetches Claude usage. `opts.allowWebviewRepair` enables the silent hidden
 * WebView Cloudflare re-solve + one retry — in-app runs only; a widget run
 * must never spin up a WebView or trigger interactive login.
 */
async function fetchClaude(opts) {
  const sessionKey = keyGet(KEYS.claude.sessionKey);
  if (!sessionKey) return { kind: "needs_login" };
  const cfClearance = keyGet(KEYS.claude.cfClearance);
  const userAgent = keyGet(KEYS.claude.userAgent);
  const timeoutSeconds = (opts && opts.timeoutSeconds) || 20;
  const allowRepair = !!(opts && opts.allowWebviewRepair);

  let orgId = keyGet(KEYS.claude.orgId);
  if (!orgId) {
    const disc = await discoverClaudeOrg(sessionKey, cfClearance, userAgent, timeoutSeconds);
    if (disc.kind !== "ok") return disc;
    orgId = disc.orgId;
    keySet(KEYS.claude.orgId, orgId);
  }

  let result = await claudeGetUsage(orgId, sessionKey, cfClearance, userAgent, timeoutSeconds);
  // A deleted/rotated org id would otherwise 404 forever; rediscover once.
  if (result.kind === "soft" && result.reason === "http-404") {
    keyRemove(KEYS.claude.orgId);
    const disc = await discoverClaudeOrg(sessionKey, cfClearance, userAgent, timeoutSeconds);
    if (disc.kind === "ok") {
      orgId = disc.orgId;
      keySet(KEYS.claude.orgId, orgId);
      result = await claudeGetUsage(orgId, sessionKey, cfClearance, userAgent, timeoutSeconds);
    }
  }
  if (result.kind === "soft" && result.reason === "cloudflare" && allowRepair) {
    // Could be an expired cf_clearance: one silent re-solve, then one retry.
    const solved = await resolveCloudflare("claude");
    if (solved) {
      result = await claudeGetUsage(
        orgId, sessionKey, keyGet(KEYS.claude.cfClearance) || cfClearance, userAgent, timeoutSeconds
      );
    } else {
      return { kind: "soft", reason: "cloudflare-unresolved" };
    }
  }
  return result;
}

// ---------------------------------------------------------------------------
// Codex fetcher (mirrors data/CodexUsageRepository.kt)
// ---------------------------------------------------------------------------

function codexBaseHeaders(cookie, userAgent) {
  const headers = {
    "Accept-Language": "en-US,en;q=0.9",
    "Cookie": cookie, // raw jar string, so NextAuth .0/.1 chunks stay intact
    "Referer": CODEX.REFERER,
    "Origin": CODEX.BASE,
  };
  if (userAgent) headers["User-Agent"] = userAgent;
  return headers;
}

/** Mints the short-lived accessToken from /api/auth/session. Never persisted. */
async function mintCodexToken(cookie, userAgent, timeoutSeconds) {
  const headers = codexBaseHeaders(cookie, userAgent);
  headers["Accept"] = "application/json";
  const r = await httpRequest(CODEX.SESSION_URL, headers, timeoutSeconds);
  if (r.kind === "exception") return { kind: "soft", reason: "network" };
  if (landedOnLogin(r.finalUrl, "codex")) return { kind: "needs_login" };
  if (looksLikeHtml(r.body) || r.headers["cf-mitigated"] === "challenge") {
    return { kind: "soft", reason: "cloudflare" };
  }
  if (r.code === 200) {
    try {
      const json = JSON.parse(r.body);
      const token = (json && (json.accessToken || json.access_token)) || "";
      if (token) return { kind: "ok", token: token };
      return { kind: "needs_login" };
    } catch (e) {
      return { kind: "needs_login" };
    }
  }
  if (r.code === 401 || landedOnLogin(r.finalUrl, "codex")) {
    return { kind: "needs_login" };
  }
  return { kind: "soft", reason: "session-http-" + r.code };
}

async function codexGetUsageAt(url, cookie, token, userAgent, timeoutSeconds) {
  const headers = codexBaseHeaders(cookie, userAgent);
  headers["Accept"] = "application/json";
  headers["Authorization"] = "Bearer " + token;
  const accountId = decodeJwtAccountId(token);
  if (accountId) headers["ChatGPT-Account-Id"] = accountId;

  const r = await httpRequest(url, headers, timeoutSeconds);
  if (r.kind === "exception") return { kind: "soft", reason: "network" };
  if (landedOnLogin(r.finalUrl, "codex")) return { kind: "needs_login" };
  if (r.code === 200 && looksLikeHtml(r.body)) return { kind: "soft", reason: "cloudflare" };
  if (r.code === 200) {
    try {
      return { kind: "ok", snapshot: parseCodexUsage(r.body, Date.now()) };
    } catch (e) {
      return { kind: "soft", reason: "parse" };
    }
  }
  if (isCloudflareStatus(r.code, r.body, r.headers)) return { kind: "soft", reason: "cloudflare" };
  if (r.code === 401 || landedOnLogin(r.finalUrl, "codex")) {
    return { kind: "needs_login" };
  }
  return { kind: "soft", reason: "http-" + r.code };
}

async function codexGetUsage(cookie, token, userAgent, timeoutSeconds) {
  // Retry the fallback path only on 404 (Android getUsage).
  let result = await codexGetUsageAt(CODEX.USAGE_URL, cookie, token, userAgent, timeoutSeconds);
  if (result.kind === "soft" && result.reason === "http-404") {
    result = await codexGetUsageAt(CODEX.USAGE_FALLBACK_URL, cookie, token, userAgent, timeoutSeconds);
  }
  return result;
}

/**
 * Fetches Codex usage: session cookie -> access token -> usage. The token is
 * re-minted on every refresh and never persisted.
 */
async function fetchCodex(opts) {
  let cookie = keyGet(KEYS.codex.cookie);
  if (!cookie || !hasProviderSession(cookie, "codex")) return { kind: "needs_login" };
  const userAgent = keyGet(KEYS.codex.userAgent);
  const timeoutSeconds = (opts && opts.timeoutSeconds) || 20;
  const allowRepair = !!(opts && opts.allowWebviewRepair);

  let mint = await mintCodexToken(cookie, userAgent, timeoutSeconds);
  if (mint.kind !== "ok" && mint.kind === "soft" && mint.reason === "cloudflare" && allowRepair) {
    // A Cloudflare challenge is transient: re-mint clearance off-screen and
    // retry once. Never treat a 403/HTML page as a logout.
    const solved = await resolveCloudflare("codex");
    if (solved) {
      cookie = keyGet(KEYS.codex.cookie) || cookie;
      mint = await mintCodexToken(cookie, userAgent, timeoutSeconds);
    } else {
      return { kind: "soft", reason: "cloudflare-unresolved" };
    }
  }
  if (mint.kind !== "ok") return mint;

  let usage = await codexGetUsage(cookie, mint.token, userAgent, timeoutSeconds);
  if (usage.kind === "needs_login") {
    // The token can expire between calls; mint once more before giving up.
    const fresh = await mintCodexToken(cookie, userAgent, timeoutSeconds);
    if (fresh.kind === "ok") {
      usage = await codexGetUsage(cookie, fresh.token, userAgent, timeoutSeconds);
    }
  }
  // A 200 with no usable window must not overwrite the last good snapshot.
  if (usage.kind === "ok" && !usage.snapshot.fiveHour && !usage.snapshot.weekly) {
    return { kind: "soft", reason: "empty" };
  }
  return usage;
}

// ---------------------------------------------------------------------------
// WebView cookie harvesting + in-page fetch fallback
// ---------------------------------------------------------------------------

/** Extracts the hostname from an absolute URL ("" when unparseable). */
function urlHost(url) {
  const m = /^[a-z][a-z0-9+.-]*:\/\/([^\/?#]+)/i.exec(String(url || ""));
  if (!m) return "";
  const hostPart = m[1].split("@").pop().split(":")[0];
  return hostPart.toLowerCase();
}

function providerHost(provider) {
  return provider === "claude" ? "claude.ai" : "chatgpt.com";
}

function hostMatches(url, host) {
  const h = urlHost(url);
  return h === host || h === "www." + host || h.endsWith("." + host);
}

/**
 * Installs a harvester on a WebView. Scriptable hands each navigated request
 * to shouldAllowRequest; on platforms where WebKit exposes the request's
 * Cookie header (including HttpOnly cookies), we merge it into the Keychain
 * copy. The header is not guaranteed on every navigation, so the in-page
 * fetch path and document.cookie fallback remain the safety net.
 */
function installHarvester(wv, provider) {
  const host = providerHost(provider);
  wv.shouldAllowRequest = function (request) {
    try {
      const url = request && request.url ? String(request.url) : "";
      if (hostMatches(url, host)) {
        const headers = (request && request.headers) || {};
        for (const name in headers) {
          if (String(name).toLowerCase() === "cookie") {
            mergeStoredCookie(provider, headers[name]);
          }
        }
      }
    } catch (e) { /* harvesting must never block navigation */ }
    return true;
  };
  return wv;
}

/** Builds the in-page fetch snippet; the async evaluateJavaScript contract
 *  requires calling the global completion(...) exactly once. */
function buildInPageFetchScript(url, init) {
  return "(function(){try{fetch(" + JSON.stringify(url) + "," + JSON.stringify(init) + ")" +
    ".then(function(r){return r.text().then(function(t){completion({status:r.status,ok:r.ok,body:String(t).slice(0,20000)})})})" +
    ".catch(function(e){completion({status:0,ok:false,body:''})})}" +
    "catch(e){completion({status:0,ok:false,body:''})}})()";
}

/**
 * Runs a same-origin fetch inside the WebView page. Returns {status, ok,
 * body} or null; a rejected/timed-out evaluation resolves to null instead of
 * hanging the script. Body is truncated — never used to carry secrets out.
 */
async function inPageFetch(wv, url, init) {
  const script = buildInPageFetchScript(url, init || { credentials: "include" });
  try {
    const result = await withTimeout(wv.evaluateJavaScript(script, true), 15000, null);
    if (result && typeof result === "object") {
      return { status: Number(result.status) || 0, ok: !!result.ok, body: String(result.body || "") };
    }
  } catch (e) { /* fall through */ }
  return null;
}

/** Reads document.cookie + navigator.userAgent as a harvesting fallback. */
async function harvestDocumentState(wv, provider) {
  try {
    const cookie = await withTimeout(wv.evaluateJavaScript("document.cookie", false), 5000, null);
    if (cookie) mergeStoredCookie(provider, String(cookie));
  } catch (e) { /* optional */ }
  try {
    const ua = await withTimeout(wv.evaluateJavaScript("navigator.userAgent", false), 5000, null);
    if (ua) keySet(provider === "claude" ? KEYS.claude.userAgent : KEYS.codex.userAgent, String(ua));
  } catch (e) { /* optional */ }
}

/**
 * Silently re-mints a fresh cf_clearance by loading the challenge page in a
 * hidden (never presented) WebView, which executes Cloudflare's JS challenge.
 * The WebView jar already holds the session cookie from the interactive
 * login. Returns true when a clearance cookie was harvested afterwards.
 */
async function resolveCloudflare(provider) {
  let wv = null;
  const cookieKey = provider === "claude" ? KEYS.claude.cookie : KEYS.codex.cookie;
  const cfKey = provider === "claude" ? KEYS.claude.cfClearance : KEYS.codex.cfClearance;
  const readCf = function () {
    return parseCookieHeader(keyGet(cookieKey))["cf_clearance"] || keyGet(cfKey) || "";
  };
  const before = readCf();
  try {
    wv = new WebView();
    installHarvester(wv, provider);
    const url = provider === "claude" ? CLAUDE.CHALLENGE_URL : CODEX.CHALLENGE_URL;
    const loaded = await loadWebView(wv, url, 30000);
    await harvestDocumentState(wv, provider);
    const after = readCf();
    // Only report success when the challenge actually minted a new cookie;
    // the previously stored (now failed) clearance is not good enough.
    return loaded && !!after && after !== before;
  } catch (e) {
    return false;
  }
}

/**
 * Validates a Claude sign-in with a hidden WebView: load claude.ai and run an
 * in-page fetch of /api/organizations (same-origin, so HttpOnly session
 * cookies apply). On success the chat-capable org id is persisted.
 */
async function validateClaudeHidden() {
  try {
    const wv = new WebView();
    installHarvester(wv, "claude");
    const loaded = await loadWebView(wv, CLAUDE.BASE + "/", 30000);
    if (!loaded) {
      // Cookie header may still have been harvested during interactive login.
      return { ok: cookiesHarvested("claude"), orgId: "", webview: null };
    }
    const res = await inPageFetch(wv, CLAUDE.ORGS_URL, {
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    if (res && res.status === 200 && !looksLikeHtml(res.body)) {
      let orgId = "";
      try {
        orgId = pickClaudeOrg(JSON.parse(res.body));
      } catch (e) { /* keep orgId empty */ }
      if (orgId) keySet(KEYS.claude.orgId, orgId);
      return { ok: true, orgId: orgId, webview: wv };
    }
    // 401-ish responses mean the WebView session is not signed in.
    return { ok: false, orgId: "", webview: null };
  } catch (e) {
    return { ok: false, orgId: "", webview: null };
  }
}

/** In-page Claude usage fetch, used when Request-based fetches hit Cloudflare
 *  or the harvested cookie header is incomplete. */
async function claudeInPageUsage(webview, orgId) {
  if (!webview || !orgId) return { kind: "soft", reason: "org-unknown" };
  const url = CLAUDE.BASE + "/api/organizations/" + encodeURIComponent(orgId) + "/usage";
  const res = await inPageFetch(webview, url, {
    credentials: "include",
    headers: { Accept: "application/json" },
  });
  if (res && res.status === 200 && !looksLikeHtml(res.body)) {
    try {
      const snapshot = parseClaudeUsage(res.body, Date.now());
      snapshot.orgId = orgId;
      return { kind: "ok", snapshot: snapshot };
    } catch (e) {
      return { kind: "soft", reason: "parse" };
    }
  }
  if (res && res.status === 401) return { kind: "needs_login" };
  return { kind: "soft", reason: "cloudflare" };
}

/**
 * Validates a Codex sign-in with a hidden WebView: in-page fetch of
 * /api/auth/session. Returns the access token when the session is live.
 */
async function validateCodexHidden() {
  try {
    const wv = new WebView();
    installHarvester(wv, "codex");
    const loaded = await loadWebView(wv, CODEX.BASE + "/", 30000);
    if (!loaded) return { ok: false, token: "", webview: null };
    const res = await inPageFetch(wv, CODEX.SESSION_URL, {
      credentials: "include",
      headers: { Accept: "application/json" },
    });
    if (res && res.status === 200 && !looksLikeHtml(res.body)) {
      let token = "";
      try {
        const json = JSON.parse(res.body);
        token = (json && (json.accessToken || json.access_token)) || "";
      } catch (e) { /* token stays empty */ }
      if (token) {
        await harvestDocumentState(wv, "codex");
        return { ok: true, token: token, webview: wv };
      }
    }
    return { ok: false, token: "", webview: null };
  } catch (e) {
    return { ok: false, token: "", webview: null };
  }
}

/** In-page Codex usage fetch, used when Request-based fetches hit Cloudflare. */
async function codexInPageUsage(webview, token) {
  const headers = { Accept: "application/json", Authorization: "Bearer " + token };
  const accountId = decodeJwtAccountId(token);
  if (accountId) headers["ChatGPT-Account-Id"] = accountId;
  let res = await inPageFetch(webview, CODEX.USAGE_URL, { credentials: "include", headers: headers });
  if (res && res.status === 404) {
    res = await inPageFetch(webview, CODEX.USAGE_FALLBACK_URL, { credentials: "include", headers: headers });
  }
  if (res && res.status === 200 && !looksLikeHtml(res.body)) {
    try {
      const snapshot = parseCodexUsage(res.body, Date.now());
      if (snapshot.fiveHour || snapshot.weekly) return { kind: "ok", snapshot: snapshot };
      return { kind: "soft", reason: "empty" };
    } catch (e) {
      return { kind: "soft", reason: "parse" };
    }
  }
  if (res && res.status === 401) return { kind: "needs_login" };
  return { kind: "soft", reason: "cloudflare" };
}

// ---------------------------------------------------------------------------
// Interactive sign-in flows (in-app only)
// ---------------------------------------------------------------------------

function signInInstructions(provider) {
  if (provider === "claude") {
    return "Sign in to claude.ai in the next screen.\n\n" +
      "When the signed-in Claude page is visible, tap Done. " +
      "Scriptable then reads the session cookie from the page and fetches your usage.";
  }
  return "Sign in to chatgpt.com in the next screen.\n\n" +
    "Note: Google sign-in is blocked on chatgpt.com — use email or another method. " +
    "When the signed-in ChatGPT page is visible, tap Done. " +
    "Scriptable then reads the session cookie and fetches your Codex usage.";
}

/**
 * Interactive sign-in for one provider: pre-login alert, presented WebView,
 * hidden-WebView validation, cookie harvesting, first usage snapshot.
 * Returns {ok: boolean}.
 */
async function signIn(provider) {
  const meta = PROVIDER_META[provider];
  const alert = new Alert();
  alert.title = "Sign in to " + meta.short;
  alert.message = signInInstructions(provider);
  alert.addAction("Open sign-in page");
  alert.addAction("Cancel");
  if ((await alert.present()) !== 0) return { ok: false };

  const wv = new WebView();
  installHarvester(wv, provider);
  const loginUrl = provider === "claude" ? CLAUDE.LOGIN_URL : CODEX.LOGIN_URL;
  try {
    await wv.loadURL(loginUrl);
  } catch (e) { /* the user can still navigate manually */ }
  // Resolves when the user taps Done.
  await wv.present(true);
  await harvestDocumentState(wv, provider);

  let result;
  if (provider === "claude") {
    result = await validateClaudeHidden();
    if (!result.ok) {
      await showAlert("Not signed in yet",
        "Claude did not confirm the session. Run setup again and make sure the signed-in page is visible before tapping Done.",
        [{ label: "OK" }]);
      return { ok: false };
    }
  } else {
    result = await validateCodexHidden();
    if (!result.ok) {
      await showAlert("Not signed in yet",
        "ChatGPT did not confirm the session. Run setup again and make sure the signed-in page is visible before tapping Done.",
        [{ label: "OK" }]);
      return { ok: false };
    }
  }

  // Fetch the first usage snapshot; the hidden WebView is the reliable
  // in-app fallback whenever Request + harvested cookies hit Cloudflare.
  let fetchResult;
  if (provider === "claude") {
    fetchResult = await withTimeout(fetchClaude({ allowWebviewRepair: true }), 60000,
      { kind: "soft", reason: "timeout" });
    // The hidden WebView already has the signed-in session; use it when the
    // harvested cookie header is incomplete or Cloudflare blocks Request.
    if (fetchResult.kind !== "ok" && result.orgId && result.webview) {
      fetchResult = await claudeInPageUsage(result.webview, result.orgId);
    }
  } else {
    fetchResult = await withTimeout(fetchCodex({ allowWebviewRepair: true }), 60000,
      { kind: "soft", reason: "timeout" });
    if (fetchResult.kind !== "ok" && result.token && result.webview) {
      fetchResult = await codexInPageUsage(result.webview, result.token);
    }
  }

  const cache = loadCache();
  cache[provider] = provider === "claude"
    ? mergeClaudeSnapshot(cache.claude, fetchResult, Date.now())
    : mergeCodexSnapshot(cache.codex, fetchResult, Date.now());
  saveCache(cache);

  if (!cookiesHarvested(provider)) {
    // The cookie header was never exposed: the in-page snapshot is kept, but
    // live widget refresh is unavailable until a login exposes it.
    await showAlert("Limited refresh",
      "Signed in, but Scriptable could not expose the session cookie for background refresh. " +
      "The widget will show this snapshot; reopen the app to update it.",
      [{ label: "OK" }]);
  }
  return { ok: true };
}

/** Removes every credential for one provider; the other provider is kept. */
function signOut(provider) {
  if (provider === "claude") {
    keyRemove(KEYS.claude.sessionKey);
    keyRemove(KEYS.claude.cfClearance);
    keyRemove(KEYS.claude.cookie);
    keyRemove(KEYS.claude.userAgent);
    keyRemove(KEYS.claude.orgId);
  } else {
    keyRemove(KEYS.codex.cookie);
    keyRemove(KEYS.codex.cfClearance);
    keyRemove(KEYS.codex.userAgent);
  }
  clearCacheEntry(provider);
}

// ---------------------------------------------------------------------------
// Widget rendering
// ---------------------------------------------------------------------------

/** Dynamic (light/dark) Color for text and widget backgrounds. */
function dynColor(pair) {
  return Color.dynamic(new Color(pair.light), new Color(pair.dark));
}

function colorPrimary() { return dynColor(COLORS.textPrimary); }
function colorSecondary() { return dynColor(COLORS.textSecondary); }
function staleColor() { return dynColor(COLORS.stale); }
function accentColor(provider) { return dynColor(COLORS.accent[provider]); }
function paceColor(name) { return COLORS.pace[name] ? dynColor(COLORS.pace[name]) : colorSecondary(); }

function widgetBackgroundColor() { return dynColor(COLORS.background); }

function widgetText(stack, str, opts) {
  const o = opts || {};
  const t = stack.addText(String(str));
  const size = o.size || 13;
  if (o.bold) t.font = Font.boldSystemFont(size);
  else if (o.medium) t.font = Font.mediumSystemFont(size);
  else t.font = Font.regularSystemFont(size);
  if (o.color) t.textColor = o.color;
  if (o.lineLimit) t.lineLimit = o.lineLimit;
  if (o.scale) t.minimumScaleFactor = o.scale;
  if (o.center) t.centerAlignText();
  return t;
}

/**
 * Draws a usage bar with DrawContext: rounded track + rounded fill plus a
 * thin on-pace tick at the elapsed fraction. Track/tick colours are
 * pre-blended against the widget background (see blendHex) so no
 * alpha-channel Colour APIs are needed. Fixed pixel size, never stretched
 * via resizable: the caller sets WidgetImage.imageSize to the drawn size.
 */
function drawBarImage(pct, width, height, provider, elapsed) {
  const scale = 3;
  const W = Math.round(width * scale);
  const H = Math.round(height * scale);
  const dc = new DrawContext();
  dc.size = new Size(W, H);
  dc.opaque = false;

  const dark = Device.isUsingDarkAppearance();
  const bg = dark ? COLORS.background.dark : COLORS.background.light;

  // Track.
  const pTrack = new Path();
  pTrack.addRoundedRect(new Rect(0, 0, W, H), H / 2, H / 2);
  dc.addPath(pTrack);
  dc.setFillColor(new Color(blendHex(dark ? "#FFFFFF" : "#000000", dark ? 0.20 : 0.13, bg)));
  dc.fillPath();

  // Fill.
  const frac = Math.min(1, Math.max(0, (Number(pct) || 0) / 100));
  if (frac > 0) {
    const fillW = Math.max(H, Math.round(W * frac));
    const accent = dark ? COLORS.accent[provider].dark : COLORS.accent[provider].light;
    const pFill = new Path();
    pFill.addRoundedRect(new Rect(0, 0, fillW, H), H / 2, H / 2);
    dc.addPath(pFill);
    dc.setFillColor(new Color(accent));
    dc.fillPath();
  }

  // Thin on-pace tick at the elapsed fraction (hidden when pace is null).
  if (elapsed !== null && elapsed !== undefined) {
    const tickX = Math.round(Math.min(1, Math.max(0, elapsed)) * W);
    const tickW = Math.max(1, Math.round(1.2 * scale));
    const pTick = new Path();
    pTick.addRect(new Rect(tickX, 0, tickW, H));
    dc.addPath(pTick);
    dc.setFillColor(new Color(blendHex(dark ? "#FFFFFF" : "#000000", 0.40, bg)));
    dc.fillPath();
  }

  return dc.getImage();
}

/** Small circular dot (stale indicator), drawn the same way as the bars. */
function drawDotImage(diameter, hexPair, dark) {
  const scale = 3;
  const D = Math.round(diameter * scale);
  const dc = new DrawContext();
  dc.size = new Size(D, D);
  dc.opaque = false;
  const p = new Path();
  p.addEllipse(new Rect(0, 0, D, D));
  dc.addPath(p);
  dc.setFillColor(new Color(dark ? hexPair.dark : hexPair.light));
  dc.fillPath();
  return dc.getImage();
}

function addBarImage(stack, pct, width, height, provider, elapsed) {
  const img = stack.addImage(drawBarImage(pct, width, height, provider, elapsed));
  img.imageSize = new Size(width, height);
  return img;
}

/** Pace glyph: SF Symbol tinted with the pace colour, text fallback included
 *  in case SFSymbol.named() fails or the symbol is unavailable. */
function addPaceGlyph(stack, paceName, size) {
  const meta = PACE_SYMBOLS[paceName];
  if (!meta) return;
  const color = paceColor(paceName);
  try {
    const sym = SFSymbol.named(meta.sf);
    if (sym && sym.image) {
      const img = stack.addImage(sym.image);
      img.tintColor = color;
      img.imageSize = new Size(size, size);
      return;
    }
  } catch (e) { /* fall back to text below */ }
  widgetText(stack, meta.glyph, { size: size + 1, color: color, bold: true });
}

function addProviderGlyph(stack, provider, size) {
  const meta = PROVIDER_META[provider];
  try {
    const sym = SFSymbol.named(meta.sf);
    if (sym && sym.image) {
      const img = stack.addImage(sym.image);
      img.tintColor = accentColor(provider);
      img.imageSize = new Size(size, size);
      return;
    }
  } catch (e) { /* fall back to text below */ }
  widgetText(stack, meta.glyph, { size: size + 2, color: accentColor(provider), bold: true });
}

// ---------------------------------------------------------------------------
// Per-provider view state
// ---------------------------------------------------------------------------

function makeMeter(label, win, now, windowSeconds) {
  return {
    label: label,
    pct: Math.round(Math.min(100, Math.max(0, Number(win.util) || 0))),
    resets: resetsIn(win.resetsAt, now),
    pace: pace(win.util, win.resetsAt, windowSeconds, now),
    elapsed: elapsedFraction(win.resetsAt, windowSeconds, now),
  };
}

function claudeMeters(entry, now) {
  const meters = [];
  if (entry.fiveHour) meters.push(makeMeter("5H", entry.fiveHour, now, 5 * 3600));
  if (entry.sevenDay) meters.push(makeMeter("1W", entry.sevenDay, now, 7 * 86400));
  return meters;
}

function codexMeters(entry, now) {
  const meters = [];
  const weeklySeconds = entry.weeklyWindowSeconds || 604800;
  if (entry.fiveHour) meters.push(makeMeter("5H", entry.fiveHour, now, 5 * 3600));
  if (entry.weekly) meters.push(makeMeter(windowLabel(weeklySeconds), entry.weekly, now, weeklySeconds));
  return meters;
}

/** Immutable-ish view state per provider, assembled from cache on each render. */
function buildViewState(provider, cache, now) {
  const entry = (cache && cache[provider]) || emptyEntry(provider);
  const signedIn = provider === "claude" ? hasClaudeCreds() : hasCodexCreds();
  const hasSnapshot = !!entry.fetchedAt;
  // Without harvested cookies we cannot refresh in the background, but a
  // snapshot captured through the hidden WebView is still worth showing.
  const needsLogin = entry.authState === "needs_login" || (!signedIn && !hasSnapshot);
  const meters = provider === "claude" ? claudeMeters(entry, now) : codexMeters(entry, now);
  let caption = "";
  if (provider === "codex" && !needsLogin && !entry.fiveHour && entry.weekly) {
    caption = windowLabel(entry.weeklyWindowSeconds || 604800) === "1W"
      ? "Weekly-only plan"
      : "Single window";
  }
  return {
    provider: provider,
    needsLogin: needsLogin,
    hasData: !needsLogin && meters.length > 0,
    stale: !needsLogin && hasSnapshot && isStale(entry.fetchedAt, now, CONFIG.staleMinutes),
    fetchedAt: entry.fetchedAt,
    now: now,
    meters: meters,
    caption: caption,
    error: entry.error || "",
    // Signed in but the first fetch failed: don't pretend the plan has no limits.
    unavailable: !needsLogin && !hasSnapshot && !!entry.error,
    noLimits: !needsLogin && !(!hasSnapshot && !!entry.error) && meters.length === 0,
  };
}

// ---------------------------------------------------------------------------
// Building blocks
// ---------------------------------------------------------------------------

/** Header row: provider glyph + name, right-aligned "updated Xm ago" + amber
 *  dot when the snapshot is stale. */
function addProviderHeader(stack, state, compact) {
  const row = stack.addStack();
  row.layoutHorizontally();
  row.centerAlignContent();
  row.spacing = 4;
  row.size = new Size(0, 0);
  addProviderGlyph(row, state.provider, compact ? 14 : 16);
  widgetText(row, PROVIDER_META[state.provider].name, {
    size: compact ? 10 : 12,
    color: colorSecondary(),
    medium: true,
    lineLimit: 1,
    scale: 0.7,
  });
  if (state.stale) {
    row.addSpacer();
    const ago = updatedAgo(state.fetchedAt, state.now || Date.now());
    if (ago) {
      widgetText(row, compact ? ago : "updated " + ago, {
        size: 8, color: staleColor(), lineLimit: 1, scale: 0.7,
      });
    }
    const dark = Device.isUsingDarkAppearance();
    const dot = row.addImage(drawDotImage(7, COLORS.stale, dark));
    dot.imageSize = new Size(7, 7);
  }
}

/** Full meter row: bold label + bold % + pace glyph + reset countdown, with a
 *  5-6 px drawn bar underneath. */
function addFullMeter(parent, meter, provider, barWidth) {
  const col = parent.addStack();
  col.layoutVertically();
  col.spacing = 3;
  col.size = new Size(0, 0);

  const row = col.addStack();
  row.layoutHorizontally();
  row.centerAlignContent();
  row.spacing = 5;
  row.size = new Size(0, 0);
  widgetText(row, meter.label, { size: 12, color: colorSecondary(), bold: true, lineLimit: 1 });
  widgetText(row, meter.pct + "%", { size: 14, color: colorPrimary(), bold: true, lineLimit: 1, scale: 0.7 });
  row.addSpacer();
  if (meter.pace) {
    addPaceGlyph(row, meter.pace, 12);
    row.spacing = 5;
  }
  widgetText(row, meter.resets, { size: 11, color: colorSecondary(), lineLimit: 1, scale: 0.7 });

  addBarImage(col, meter.pct, barWidth, 6, provider, meter.pace ? meter.elapsed : null);
  return col;
}

/** Compact meter: label + NN% above a shorter bar, no countdown. */
function addCompactMeter(parent, meter, provider, barWidth) {
  const col = parent.addStack();
  col.layoutVertically();
  col.spacing = 2;
  col.size = new Size(0, 0);

  const row = col.addStack();
  row.layoutHorizontally();
  row.size = new Size(0, 0);
  widgetText(row, meter.label, { size: 10, color: colorSecondary(), bold: true, lineLimit: 1 });
  row.addSpacer();
  widgetText(row, meter.pct + "%", { size: 12, color: colorPrimary(), bold: true, lineLimit: 1, scale: 0.7 });

  addBarImage(col, meter.pct, barWidth, 4, provider, meter.pace ? meter.elapsed : null);
  return col;
}

/** "Tap to sign in" section body. */
function addSignInSection(stack, state, compact) {
  const col = stack.addStack();
  col.layoutVertically();
  col.centerAlignContent();
  col.size = new Size(0, 0);
  addProviderGlyph(col, state.provider, compact ? 16 : 22);
  col.addSpacer(4);
  widgetText(col, compact ? "Sign in" : "Tap to sign in", {
    size: compact ? 11 : 13,
    color: colorPrimary(),
    medium: true,
  });
}

/** One provider section: header plus its meters (or sign-in / no-limits). */
function addProviderSection(stack, state, opts) {
  const col = stack.addStack();
  col.layoutVertically();
  col.spacing = opts.spacing;
  col.size = new Size(0, 0);

  if (state.needsLogin) {
    addSignInSection(col, state, opts.compact);
    return col;
  }
  addProviderHeader(col, state, opts.compact);
  col.addSpacer(opts.headerGap);

  if (state.unavailable) {
    widgetText(col, opts.compact ? "Open app" : "Open the app to refresh", {
      size: opts.compact ? 10 : 11,
      color: colorSecondary(),
      lineLimit: 1,
      scale: 0.7,
    });
    return col;
  }

  if (state.noLimits) {
    widgetText(col, opts.compact ? "No limits" : "No usage limits", {
      size: 11, color: colorSecondary(),
    });
    return col;
  }

  for (let i = 0; i < state.meters.length; i++) {
    if (i > 0) col.addSpacer(opts.meterGap);
    if (opts.compact) {
      addCompactMeter(col, state.meters[i], state.provider, opts.barWidth);
    } else {
      addFullMeter(col, state.meters[i], state.provider, opts.barWidth);
    }
  }
  if (state.caption) {
    col.addSpacer(4);
    widgetText(col, state.caption, { size: 10, color: colorSecondary(), lineLimit: 1 });
  }
  return col;
}

// ---------------------------------------------------------------------------
// Family layouts (driven by the pure planLayout helper)
// ---------------------------------------------------------------------------

function renderPlaceholder(widget) {
  widget.setPadding(12, 12, 12, 12);
  const col = widget.addStack();
  col.layoutVertically();
  col.centerAlignContent();
  col.addSpacer();
  addProviderGlyph(col, "claude", 22);
  col.addSpacer(6);
  widgetText(col, "Tap to sign in", { size: 13, color: colorPrimary(), medium: true, center: true });
  col.addSpacer();
}

/** small: one compact column for a single provider. */
function renderCompact(widget, states) {
  widget.setPadding(10, 10, 10, 10);
  const col = widget.addStack();
  col.layoutVertically();
  col.spacing = 6;
  col.size = new Size(0, 0);
  addProviderSection(col, states[0], {
    compact: true, spacing: 5, headerGap: 4, meterGap: 6, barWidth: 118,
  });
}

/** medium (both) / extraLarge (both): side-by-side columns. */
function renderColumns(widget, states, plan) {
  widget.setPadding(plan.wide ? 16 : 12, plan.wide ? 16 : 12, plan.wide ? 16 : 12, plan.wide ? 16 : 12);
  const rowStack = widget.addStack();
  rowStack.layoutHorizontally();
  rowStack.spacing = plan.wide ? 20 : 12;
  for (let i = 0; i < states.length; i++) {
    if (i > 0) {
      const spacer = rowStack.addStack();
      spacer.size = new Size(plan.wide ? 1 : 0, 0);
    }
    addProviderSection(rowStack, states[i], {
      compact: plan.compact,
      spacing: plan.wide ? 10 : 6,
      headerGap: plan.wide ? 10 : 5,
      meterGap: plan.wide ? 12 : 7,
      barWidth: plan.wide ? 270 : 118,
    });
  }
}

/** large (both): two stacked sections, one per provider. */
function renderStacked(widget, states) {
  widget.setPadding(14, 14, 14, 14);
  const col = widget.addStack();
  col.layoutVertically();
  col.spacing = 10;
  col.size = new Size(0, 0);
  for (let i = 0; i < states.length; i++) {
    if (i > 0) col.addSpacer(8);
    addProviderSection(col, states[i], {
      compact: false, spacing: 6, headerGap: 8, meterGap: 10, barWidth: 258,
    });
  }
}

/** medium/large/extraLarge (single): one full-width section. */
function renderFull(widget, states, plan) {
  widget.setPadding(plan.wide ? 20 : 14, plan.wide ? 20 : 14, plan.wide ? 20 : 14, plan.wide ? 20 : 14);
  const col = widget.addStack();
  col.layoutVertically();
  col.spacing = plan.wide ? 14 : 10;
  col.size = new Size(0, 0);
  addProviderSection(col, states[0], {
    compact: false,
    spacing: plan.wide ? 10 : 6,
    headerGap: plan.wide ? 12 : 8,
    meterGap: plan.wide ? 16 : 10,
    barWidth: plan.wide ? 540 : 258,
  });
}

// --- Lock-screen accessories -------------------------------------------------

function meterText(meter) {
  return meter.label + " " + meter.pct + "% \u00B7 " + meter.resets;
}

function accessoryLine(state) {
  if (state.needsLogin) return PROVIDER_META[state.provider].short + " \u00B7 Tap to sign in";
  if (state.unavailable) return PROVIDER_META[state.provider].short + " \u00B7 Open app";
  if (state.noLimits) return PROVIDER_META[state.provider].short + " \u00B7 No usage limits";
  if (state.meters.length > 0) {
    return PROVIDER_META[state.provider].short + " " + meterText(state.meters[0]);
  }
  return PROVIDER_META[state.provider].short;
}

/** Compact lock-screen line for two providers: percentages, no countdowns. */
function accessoryCompactLine(state) {
  if (state.needsLogin) return PROVIDER_META[state.provider].short + " \u00B7 Tap to sign in";
  if (state.unavailable) return PROVIDER_META[state.provider].short + " \u00B7 Open app";
  if (state.noLimits) return PROVIDER_META[state.provider].short + " \u00B7 No limits";
  if (state.meters.length === 0) return PROVIDER_META[state.provider].short;
  return PROVIDER_META[state.provider].short + " " +
    state.meters.map(function (m) { return m.label + " " + m.pct + "%"; }).join(" \u00B7 ");
}

/** accessoryRectangular: two lines. Single provider gets both windows with
 *  countdowns; two providers get one percentage-only line each. */
function renderAccessoryRectangular(widget, states) {
  widget.addAccessoryWidgetBackground = true;
  widget.setPadding(4, 6, 4, 2);
  const col = widget.addStack();
  col.layoutVertically();
  col.spacing = 1;
  const lines = [];
  if (states.length === 1 && states[0].meters.length >= 2) {
    const s = states[0];
    lines.push(PROVIDER_META[s.provider].short + " \u00B7 " + meterText(s.meters[0]));
    lines.push(meterText(s.meters[1]));
  } else if (states.length === 1) {
    lines.push(accessoryLine(states[0]));
  } else {
    states.forEach(function (s) { lines.push(accessoryCompactLine(s)); });
  }
  lines.forEach(function (line) {
    widgetText(col, line, { size: 12, color: colorPrimary(), lineLimit: 1, scale: 0.7 });
  });
}

/** accessoryInline: one short line. Percentages only — countdowns do not fit
 *  on the inline accessory. */
function renderAccessoryInline(widget, states) {
  const perProvider = states.length > 1 ? 1 : 2;
  const parts = states.map(function (s) {
    if (s.needsLogin) return PROVIDER_META[s.provider].short + " sign in";
    if (s.unavailable) return PROVIDER_META[s.provider].short + " open app";
    if (s.meters.length === 0) return PROVIDER_META[s.provider].short;
    const windows = s.meters.slice(0, perProvider).map(function (m) {
      return m.label + " " + m.pct + "%";
    }).join(" \u00B7 ");
    return PROVIDER_META[s.provider].short + " " + windows;
  });
  widgetText(widget, parts.join(" \u00B7 "), {
    size: 13, color: colorPrimary(), medium: true, lineLimit: 1, scale: 0.7,
  });
}

/** accessoryCircular: a single short text, e.g. "5H 42%" (accessory widgets
 *  are documented to display one image and one text). */
function renderAccessoryCircular(widget, states) {
  const state = states[0];
  if (state.needsLogin) {
    widgetText(widget, "Sign in", {
      size: 12, color: colorPrimary(), medium: true, center: true, lineLimit: 1,
    });
    return;
  }
  if (state.meters.length === 0) {
    widgetText(widget, "--", { size: 12, color: colorSecondary(), center: true });
    return;
  }
  const meter = state.meters[0];
  widgetText(widget, meter.label + " " + meter.pct + "%", {
    size: 12, color: colorPrimary(), bold: true, center: true, lineLimit: 1, scale: 0.7,
  });
}

/** Dispatches to the family layout chosen by planLayout. */
function renderWidgetLayout(widget, cache, family, providerIds, now) {
  const plan = planLayout(family, providerIds);
  const states = plan.providers.map(function (id) { return buildViewState(id, cache, now); });
  if (states.length === 0) {
    renderPlaceholder(widget);
    return;
  }
  switch (plan.mode) {
    case "compact":
      renderCompact(widget, states);
      break;
    case "columns":
      renderColumns(widget, states, plan);
      break;
    case "stacked":
      renderStacked(widget, states);
      break;
    case "accessoryRectangular":
      renderAccessoryRectangular(widget, states);
      break;
    case "accessoryInline":
      renderAccessoryInline(widget, states);
      break;
    case "accessoryCircular":
      renderAccessoryCircular(widget, states);
      break;
    default:
      renderFull(widget, states, plan);
      break;
  }
}

/** Builds a complete widget (background + layout) from cache. */
function buildWidget(family, cache, providers, now) {
  const widget = new ListWidget();
  // Lock-screen accessories keep ListWidget's transparent background so the
  // wallpaper shows through; only home-screen widgets get the card colour.
  if (String(family).indexOf("accessory") !== 0) {
    widget.backgroundColor = widgetBackgroundColor();
  }
  renderWidgetLayout(widget, cache, family, providers, now);
  return widget;
}

// ---------------------------------------------------------------------------
// Widget runtime
// ---------------------------------------------------------------------------

/**
 * Opportunistic live refresh in the widget: only when enabled, credentials
 * look complete and the cache is older than liveFetchMinCacheAgeMinutes.
 * Any soft failure keeps the last snapshot (merge helpers handle this) and
 * records a short reason. Never runs interactive login or WebViews here.
 */
async function opportunisticLiveRefresh(cache, providerIds, now) {
  const jobs = [];
  for (let i = 0; i < providerIds.length; i++) {
    const id = providerIds[i];
    if (id !== "claude" && id !== "codex") continue;
    const entry = cache[id] || emptyEntry(id);
    const age = now - (entry.fetchedAt || 0);
    if (entry.fetchedAt > 0 && age < CONFIG.liveFetchMinCacheAgeMinutes * 60000) continue;
    if (id === "claude" && !hasClaudeCreds()) continue;
    if (id === "codex" && !hasCodexCreds()) continue;

    jobs.push((async function (providerId) {
      const timeoutMs = CONFIG.widgetFetchTimeoutSeconds * 1000;
      // Run both providers concurrently so the widget stays inside WidgetKit's
      // refresh budget even when both are stale.
      const result = providerId === "claude"
        ? await withTimeout(fetchClaude({ timeoutSeconds: CONFIG.widgetFetchTimeoutSeconds }), timeoutMs,
            { kind: "soft", reason: "timeout" })
        : await withTimeout(fetchCodex({ timeoutSeconds: CONFIG.widgetFetchTimeoutSeconds }), timeoutMs,
            { kind: "soft", reason: "timeout" });
      cache[providerId] = providerId === "claude"
        ? mergeClaudeSnapshot(cache[providerId], result, Date.now())
        : mergeCodexSnapshot(cache[providerId], result, Date.now());
    })(id));
  }
  if (jobs.length > 0) {
    await Promise.all(jobs);
    saveCache(cache);
  }
}

async function runWidget() {
  const now = Date.now();
  const family = (typeof config !== "undefined" && config.widgetFamily) || "";
  const parameter = (typeof config !== "undefined" && config.widgetParameter) || "";
  // Accessory families use the cache only, to stay inside the lock-screen
  // refresh budget.
  const accessory = String(family).indexOf("accessory") === 0;

  const cache = loadCache();
  // Render a provider when it is signed in OR has a cached snapshot. The
  // latter keeps the limited-refresh fallback (in-page fetch without a
  // harvestable cookie) visible instead of showing a sign-in prompt.
  const available = signedInProviderIds();
  ["claude", "codex"].forEach(function (id) {
    if (cache[id] && cache[id].fetchedAt > 0 && available.indexOf(id) === -1) available.push(id);
  });
  const providers = selectProviders(parameter, available);

  if (!accessory && CONFIG.liveFetchInWidget) {
    try {
      await opportunisticLiveRefresh(cache, providers, now);
    } catch (e) { /* render whatever the cache holds */ }
  }

  const widget = buildWidget(family, cache, providers, Date.now());
  widget.refreshAfterDate = new Date(Date.now() + CONFIG.refreshMinutes * 60000);
  // Tapping the widget opens the setup/refresh screen.
  widget.url = "scriptable:///run?scriptName=" + encodeURIComponent(Script.name());
  Script.setWidget(widget);
  Script.complete();
}

// ---------------------------------------------------------------------------
// App mode — Alert-based menu (setup / refresh / preview / sign-out / help)
// ---------------------------------------------------------------------------

/** Presents an alert; returns the index of the tapped option (no cancel
 *  actions are used, so indices map 1:1 to the options array). */
async function showAlert(title, message, optionLabels) {
  const alert = new Alert();
  alert.title = title;
  alert.message = message;
  (optionLabels || []).forEach(function (label) { alert.addAction(label); });
  const index = await alert.present();
  return typeof index === "number" ? index : 0;
}

function formatPct(win) {
  if (!win) return "--";
  return Math.round(Math.min(100, Math.max(0, Number(win.util) || 0))) + "%";
}

/** One-line status for the menu/summary, or a short error reason. */
function describeProviderEntry(provider, entry) {
  const meta = PROVIDER_META[provider];
  if (entry.authState === "needs_login") {
    return meta.short + " — Session expired — sign in again";
  }
  const err = entry.error || "";
  if (err === "cloudflare" || err === "cloudflare-unresolved") {
    return meta.short + " — Cloudflare challenge — open the app and Refresh now";
  }
  if (err === "network" || err === "timeout") {
    return meta.short + " — Network error — try again";
  }
  if (err === "parse") return meta.short + " — Unexpected response — try again";
  if (err === "org-unknown") return meta.short + " — Organization not found — sign in again";
  if (err === "empty") return meta.short + " — No usage limits reported";
  if (err) return meta.short + " — Update failed (" + err + ") — cached data shown";
  if (provider === "claude") {
    return meta.short + " — 5H: " + formatPct(entry.fiveHour) + "  1W: " + formatPct(entry.sevenDay);
  }
  let line = meta.short + " — 5H: " + formatPct(entry.fiveHour) +
    "  " + windowLabel(entry.weeklyWindowSeconds || 604800) + ": " + formatPct(entry.weekly);
  if (entry.planType) line += "  plan " + entry.planType;
  return line;
}

async function refreshNow() {
  const providers = ["claude", "codex"].filter(function (id) {
    return id === "claude" ? hasClaudeCreds() : hasCodexCreds();
  });
  if (providers.length === 0) {
    await showAlert("Refresh", "No providers are signed in yet.", [{ label: "OK" }]);
    return;
  }
  const cache = loadCache();
  const lines = [];
  for (let i = 0; i < providers.length; i++) {
    const id = providers[i];
    const fetcher = id === "claude"
      ? fetchClaude({ allowWebviewRepair: true })
      : fetchCodex({ allowWebviewRepair: true });
    let result = await withTimeout(fetcher, 90000, { kind: "soft", reason: "timeout" });
    if (result.kind !== "ok") {
      // Last resort in-app path: fetch same-origin in a hidden WebView, which
      // still carries the signed-in session when cookies are unavailable.
      if (id === "claude") {
        const v = await validateClaudeHidden();
        if (v.ok && v.orgId && v.webview) result = await claudeInPageUsage(v.webview, v.orgId);
      } else {
        const v = await validateCodexHidden();
        if (v.ok && v.token && v.webview) result = await codexInPageUsage(v.webview, v.token);
      }
    }
    cache[id] = id === "claude"
      ? mergeClaudeSnapshot(cache[id], result, Date.now())
      : mergeCodexSnapshot(cache[id], result, Date.now());
    lines.push(describeProviderEntry(id, cache[id]));
  }
  saveCache(cache);
  await showAlert("Refreshed", lines.join("\n"), [{ label: "OK" }]);
}

/** Silent refresh for a Shortcut/automation. No alerts, no interactive login. */
async function refreshAllSilently() {
  const providers = signedInProviderIds();
  if (providers.length === 0) {
    Script.complete();
    return;
  }
  const cache = loadCache();
  for (let i = 0; i < providers.length; i++) {
    const id = providers[i];
    const fetcher = id === "claude"
      ? fetchClaude({ allowWebviewRepair: true })
      : fetchCodex({ allowWebviewRepair: true });
    const result = await withTimeout(fetcher, 90000, { kind: "soft", reason: "timeout" });
    cache[id] = id === "claude"
      ? mergeClaudeSnapshot(cache[id], result, Date.now())
      : mergeCodexSnapshot(cache[id], result, Date.now());
  }
  saveCache(cache);
  Script.complete();
}

async function signInAgain() {
  const choice = await showAlert("Sign in again", "Which provider?", [
    { label: "Claude" },
    { label: "Codex" },
    { label: "Cancel" },
  ]);
  if (choice === 0) await signIn("claude");
  else if (choice === 1) await signIn("codex");
}

async function signOutFlow() {
  const choice = await showAlert("Sign out", "Which provider? Its credentials and cached usage are removed from this device.", [
    { label: "Claude" },
    { label: "Codex" },
    { label: "Cancel" },
  ]);
  if (choice === 0) signOut("claude");
  else if (choice === 1) signOut("codex");
  else return;
  await showAlert("Signed out", "Credentials removed from the Keychain.", [{ label: "OK" }]);
}

/** Preview: small/medium/large/extraLarge first, accessory families last
 *  (Scriptable presents them as small widgets, so they are approximate). */
async function previewFlow() {
  const isPad = typeof Device !== "undefined" && typeof Device.isPad === "function" && Device.isPad();
  const options = [
    { label: "Small" },
    { label: "Medium" },
    { label: "Large" },
  ];
  const families = ["small", "medium", "large"];
  if (isPad) {
    options.push({ label: "Extra large (iPad)" });
    families.push("extraLarge");
  }
  options.push(
    { label: "Accessory rectangular" },
    { label: "Accessory inline" },
    { label: "Accessory circular" },
    { label: "Cancel" }
  );
  families.push("accessoryRectangular", "accessoryInline", "accessoryCircular");
  const choice = await showAlert("Preview widget", "Choose a family to preview. Accessory previews are approximate in Scriptable.", options);
  if (choice < 0 || choice >= families.length) return;
  const family = families[choice];
  const cache = loadCache();
  const available = signedInProviderIds();
  ["claude", "codex"].forEach(function (id) {
    if (cache[id] && cache[id].fetchedAt > 0 && available.indexOf(id) === -1) available.push(id);
  });
  const providers = selectProviders(
    (typeof config !== "undefined" && config.widgetParameter) || "",
    available
  );
  const widget = buildWidget(family, cache, providers, Date.now());
  if (family === "small") await widget.presentSmall();
  else if (family === "medium") await widget.presentMedium();
  else if (family === "large") await widget.presentLarge();
  else if (family === "extraLarge") await widget.presentExtraLarge();
  else if (family === "accessoryRectangular") await widget.presentAccessoryRectangular();
  else if (family === "accessoryInline") await widget.presentAccessoryInline();
  else await widget.presentAccessoryCircular();
}

async function showHelp() {
  await showAlert("Help",
    "Setup: open the app, sign in to each provider and tap Done once the signed-in page is visible, then add the Scriptable widget to your home screen.\n\n" +
    "Tap the widget to refresh or reconfigure. A stale dot means the snapshot is older than " + CONFIG.staleMinutes + " minutes.\n\n" +
    "Cloudflare challenges: run Refresh now from the app so a hidden WebView can re-mint clearance.\n\n" +
    "Docs: https://github.com/chongtzezhao/claude-usage-widget/tree/master/ios-scriptable",
    [{ label: "OK" }]);
}

async function runApp() {
  const startCache = loadCache();
  const anyData = ["claude", "codex"].some(function (id) {
    return startCache[id] && startCache[id].fetchedAt > 0;
  });
  const anySignedIn = hasClaudeCreds() || hasCodexCreds() || anyData;
  if (!anySignedIn) {
    const choice = await showAlert("Welcome",
      "This Scriptable widget shows your Claude and Codex subscription usage on the home screen and lock screen.\n\n" +
      "One-time setup: sign in below; each sign-in opens a WebView — sign in normally and tap Done once the signed-in page is visible. " +
      "Credentials are stored in Scriptable's Keychain on this device only.\n\n" +
      "Then add the Scriptable widget to your home screen.",
      [
        { label: "Sign in to Claude" },
        { label: "Sign in to Codex" },
        { label: "Help" },
        { label: "Cancel" },
      ]);
    if (choice === 0) await signIn("claude");
    else if (choice === 1) await signIn("codex");
    else if (choice === 2) await showHelp();
    return;
  }

  // Main menu — loops until the user picks Cancel.
  for (;;) {
    const cache = loadCache();
    const status = ["claude", "codex"]
      .filter(function (id) {
        const hasCreds = id === "claude" ? hasClaudeCreds() : hasCodexCreds();
        return hasCreds || (cache[id] && cache[id].fetchedAt > 0);
      })
      .map(function (id) { return describeProviderEntry(id, cache[id] || emptyEntry(id)); })
      .join("\n");
    const choice = await showAlert("Claude Usage Widget", status || "Not signed in.", [
      { label: "Refresh now" },
      { label: "Sign in again" },
      { label: "Preview widget" },
      { label: "Sign out" },
      { label: "Help" },
      { label: "Cancel" },
    ]);
    if (choice === 0) await refreshNow();
    else if (choice === 1) await signInAgain();
    else if (choice === 2) await previewFlow();
    else if (choice === 3) await signOutFlow();
    else if (choice === 4) await showHelp();
    else return;
  }
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

async function main() {
  if (typeof config !== "undefined" && config.runsInWidget) {
    await runWidget();
    return;
  }
  // A Shortcut/automation can run the script with the text "refresh" to update
  // the cache silently, without showing the setup menu.
  const shortcutArg = typeof args !== "undefined" && args.shortcutParameter;
  const wantsSilentRefresh =
    shortcutArg === "refresh" ||
    (shortcutArg && typeof shortcutArg === "object" && shortcutArg.action === "refresh");
  if (wantsSilentRefresh) {
    await refreshAllSilently();
    return;
  }
  await runApp();
}

if (typeof module === "undefined") {
  main().catch(async function (err) {
    // Friendly failure; never expose cookie values in the message.
    const inApp = typeof config !== "undefined" && (config.runsInApp || config.runsWithSiri);
    if (inApp) {
      try {
        const alert = new Alert();
        alert.title = "Claude Usage Widget";
        alert.message = "Something went wrong: " +
          (err && err.message ? String(err.message) : "unknown error") +
          ". Run the script again; if it persists, sign in again from the menu.";
        alert.addAction("OK");
        await alert.present();
      } catch (e2) { /* fall through */ }
    }
    Script.complete();
  });
}

// ---------------------------------------------------------------------------
// Node-testable exports (pure helpers only; no Scriptable APIs here)
// ---------------------------------------------------------------------------

if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    CONFIG: CONFIG,
    CLAUDE: CLAUDE,
    CODEX: CODEX,
    parseIso8601Ms: parseIso8601Ms,
    parseClaudeUsage: parseClaudeUsage,
    parseCodexUsage: parseCodexUsage,
    decodeJwtAccountId: decodeJwtAccountId,
    resetsIn: resetsIn,
    isStale: isStale,
    pace: pace,
    elapsedFraction: elapsedFraction,
    windowLabel: windowLabel,
    updatedAgo: updatedAgo,
    planLayout: planLayout,
    mergeClaudeSnapshot: mergeClaudeSnapshot,
    mergeCodexSnapshot: mergeCodexSnapshot,
    selectProviders: selectProviders,
    parseCookieHeader: parseCookieHeader,
    hasSessionCookie: hasSessionCookie,
    hasProviderSession: hasProviderSession,
    chunkedCookieValue: chunkedCookieValue,
    pickClaudeOrg: pickClaudeOrg,
    codexWindowFrom: codexWindowFrom,
    normalizeCodexEntry: normalizeCodexEntry,
    buildWidget: buildWidget,
    looksLikeHtml: looksLikeHtml,
  };
}
