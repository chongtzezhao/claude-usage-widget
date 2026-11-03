/**
 * logic.test.mjs — dependency-free unit tests for claude-usage-widget.js.
 *
 * The Scriptable script is CommonJS-compatible: requiring it only evaluates the
 * pure helpers and never touches Scriptable APIs. Run with:
 *
 *   cd ios-scriptable && node --test test/logic.test.mjs
 */

import { describe, it } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
const widget = require("../claude-usage-widget.js");

const {
  CONFIG,
  parseIso8601Ms,
  parseClaudeUsage,
  parseCodexUsage,
  codexWindowFrom,
  decodeJwtAccountId,
  resetsIn,
  isStale,
  pace,
  elapsedFraction,
  windowLabel,
  updatedAgo,
  planLayout,
  mergeClaudeSnapshot,
  mergeCodexSnapshot,
  selectProviders,
  providersToRefresh,
  isClaudeLink,
  parseCookieHeader,
  hasSessionCookie,
  hasProviderSession,
  chunkedCookieValue,
  pickClaudeOrg,
  looksLikeHtml,
} = widget;

const MIN = 60 * 1000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

// A fixed "now" so every assertion is deterministic. 2026-06-20T20:00:00Z.
const NOW = Date.UTC(2026, 5, 20, 20, 0, 0, 0);

const iso = (ms) => new Date(ms).toISOString();

// ---------------------------------------------------------------------------
// parseIso8601Ms
// ---------------------------------------------------------------------------

describe("parseIso8601Ms", () => {
  it("parses a Z timestamp", () => {
    assert.equal(parseIso8601Ms("2026-06-20T20:00:00Z"), Date.UTC(2026, 5, 20, 20, 0, 0, 0));
  });

  it("parses a lowercase z timestamp", () => {
    assert.equal(parseIso8601Ms("2026-06-20t20:00:00z"), Date.UTC(2026, 5, 20, 20, 0, 0, 0));
  });

  it("parses a +02:00 offset", () => {
    assert.equal(
      parseIso8601Ms("2026-06-20T20:00:00+02:00"),
      Date.UTC(2026, 5, 20, 18, 0, 0, 0)
    );
  });

  it("parses a -05:30 offset", () => {
    assert.equal(
      parseIso8601Ms("2026-06-20T20:00:00-05:30"),
      Date.UTC(2026, 5, 21, 1, 30, 0, 0)
    );
  });

  it("parses an offset without a colon", () => {
    assert.equal(
      parseIso8601Ms("2026-06-20T20:00:00+0230"),
      Date.UTC(2026, 5, 20, 17, 30, 0, 0)
    );
  });

  it("parses 6-digit fractional seconds (the claude.ai shape)", () => {
    assert.equal(
      parseIso8601Ms("2026-06-20T20:00:00.483321+00:00"),
      Date.UTC(2026, 5, 20, 20, 0, 0, 483)
    );
    assert.equal(
      parseIso8601Ms("2026-06-20T20:00:00.123456Z"),
      Date.UTC(2026, 5, 20, 20, 0, 0, 123)
    );
  });

  it("treats a timestamp with no offset as UTC", () => {
    assert.equal(parseIso8601Ms("2026-06-20 20:00:00"), Date.UTC(2026, 5, 20, 20, 0, 0, 0));
  });

  it("returns 0 for invalid or empty input", () => {
    assert.equal(parseIso8601Ms(""), 0);
    assert.equal(parseIso8601Ms(null), 0);
    assert.equal(parseIso8601Ms(undefined), 0);
    assert.equal(parseIso8601Ms("not a date"), 0);
    assert.equal(parseIso8601Ms("2026-06-20"), 0);
    assert.equal(parseIso8601Ms(1750000000000), 0);
  });
});

// ---------------------------------------------------------------------------
// parseClaudeUsage
// ---------------------------------------------------------------------------

describe("parseClaudeUsage", () => {
  const normal = JSON.stringify({
    five_hour: { utilization: 42, resets_at: "2026-06-20T23:10:00.123456+00:00" },
    seven_day: { utilization: 78, resets_at: "2026-06-26T12:00:00Z" },
  });

  it("parses a normal payload", () => {
    const snap = parseClaudeUsage(normal, NOW);
    assert.deepEqual(snap.fiveHour, {
      util: 42,
      resetsAt: Date.UTC(2026, 5, 20, 23, 10, 0, 123),
    });
    assert.deepEqual(snap.sevenDay, {
      util: 78,
      resetsAt: Date.UTC(2026, 5, 26, 12, 0, 0, 0),
    });
    assert.equal(snap.fetchedAt, NOW);
  });

  it("keeps fractional utilization", () => {
    const snap = parseClaudeUsage(
      JSON.stringify({
        five_hour: { utilization: 42.5, resets_at: iso(NOW + HOUR) },
        seven_day: { utilization: 99.9, resets_at: iso(NOW + DAY) },
      }),
      NOW
    );
    assert.equal(snap.fiveHour.util, 42.5);
    assert.equal(snap.sevenDay.util, 99.9);
  });

  it("defaults missing window fields to 0", () => {
    const snap = parseClaudeUsage(JSON.stringify({ five_hour: {} }), NOW);
    assert.deepEqual(snap.fiveHour, { util: 0, resetsAt: 0 });
    assert.equal(snap.sevenDay, null);
  });

  it("throws when neither window is present", () => {
    assert.throws(() => parseClaudeUsage(JSON.stringify({ error: "nope" }), NOW), /missing usage windows/);
    assert.throws(() => parseClaudeUsage("{}", NOW), /missing usage windows/);
  });

  it("throws on malformed JSON", () => {
    assert.throws(() => parseClaudeUsage("<html>Just a moment...</html>", NOW), /invalid json/);
    assert.throws(() => parseClaudeUsage("", NOW), /invalid json/);
  });

  it("throws on a JSON array or scalar", () => {
    assert.throws(() => parseClaudeUsage("[]", NOW), /unexpected shape/);
    assert.throws(() => parseClaudeUsage("42", NOW), /unexpected shape/);
  });
});

// ---------------------------------------------------------------------------
// parseCodexUsage
// ---------------------------------------------------------------------------

describe("parseCodexUsage", () => {
  const seconds = 1800000000;

  function payload(primary, secondary, extra) {
    const root = Object.assign({ plan_type: "plus" }, extra || {});
    if (primary || secondary) {
      root.rate_limit = {};
      if (primary) root.rate_limit.primary_window = primary;
      if (secondary) root.rate_limit.secondary_window = secondary;
    }
    return JSON.stringify(root);
  }

  it("parses a normal payload with a 5H primary and 1W secondary", () => {
    const snap = parseCodexUsage(
      payload(
        { used_percent: 12.5, reset_at: seconds, limit_window_seconds: 18000 },
        { used_percent: 64, reset_at: seconds + 86400, limit_window_seconds: 604800 }
      ),
      NOW
    );
    assert.deepEqual(snap.fiveHour, { util: 12.5, resetsAt: seconds * 1000 });
    assert.deepEqual(snap.weekly, { util: 64, resetsAt: (seconds + 86400) * 1000 });
    assert.equal(snap.weeklyWindowSeconds, 604800);
    assert.equal(snap.planType, "plus");
    assert.equal(snap.fetchedAt, NOW);
  });

  it("accepts reset_at in seconds", () => {
    const win = codexWindowFrom({ used_percent: 10, reset_at: 1750000000 }, NOW);
    assert.deepEqual(win, { util: 10, resetsAt: 1750000000000 });
  });

  it("accepts reset_at in milliseconds", () => {
    const win = codexWindowFrom({ used_percent: 10, reset_at: 1750000000123 }, NOW);
    assert.deepEqual(win, { util: 10, resetsAt: 1750000000123 });
  });

  it("falls back to reset_after_seconds when reset_at is absent", () => {
    const win = codexWindowFrom({ used_percent: 5, reset_after_seconds: 3600 }, NOW);
    assert.deepEqual(win, { util: 5, resetsAt: NOW + 3600 * 1000 });
  });

  it("treats reset_after_seconds = 0 as a valid reset time", () => {
    const win = codexWindowFrom({ used_percent: 5, reset_after_seconds: 0 }, NOW);
    assert.deepEqual(win, { util: 5, resetsAt: NOW });
  });

  it("classifies by limit_window_seconds regardless of slot position", () => {
    // Weekly cap in the primary slot, 5H cap in the secondary slot.
    const snap = parseCodexUsage(
      payload(
        { used_percent: 30, reset_at: seconds, limit_window_seconds: 604800 },
        { used_percent: 70, reset_at: seconds, limit_window_seconds: 18000 }
      ),
      NOW
    );
    assert.equal(snap.fiveHour.util, 70);
    assert.equal(snap.weekly.util, 30);
    assert.equal(snap.weeklyWindowSeconds, 604800);
  });

  it("uses legacy positional windows when limit_window_seconds is missing", () => {
    const snap = parseCodexUsage(
      payload({ used_percent: 11, reset_at: seconds }, { used_percent: 22, reset_at: seconds }),
      NOW
    );
    assert.equal(snap.fiveHour.util, 11);
    assert.equal(snap.weekly.util, 22);
    assert.equal(snap.weeklyWindowSeconds, 604800);
  });

  it("handles a weekly-only plan", () => {
    const snap = parseCodexUsage(
      payload({ used_percent: 55, reset_at: seconds, limit_window_seconds: 604800 }),
      NOW
    );
    assert.equal(snap.fiveHour, null);
    assert.equal(snap.weekly.util, 55);
    assert.equal(snap.weeklyWindowSeconds, 604800);
  });

  it("renders a monthly 'other' window in the weekly slot with its own label", () => {
    const snap = parseCodexUsage(
      payload({ used_percent: 80, reset_at: seconds, limit_window_seconds: 2592000 }),
      NOW
    );
    assert.equal(snap.fiveHour, null);
    assert.equal(snap.weekly.util, 80);
    assert.equal(snap.weeklyWindowSeconds, 2592000);
    assert.equal(windowLabel(snap.weeklyWindowSeconds), "30D");
  });

  it("accepts windows at the root instead of under rate_limit", () => {
    const snap = parseCodexUsage(
      JSON.stringify({
        primary_window: { used_percent: 5, reset_at: seconds, limit_window_seconds: 18000 },
        secondary_window: { used_percent: 6, reset_at: seconds, limit_window_seconds: 604800 },
      }),
      NOW
    );
    assert.equal(snap.fiveHour.util, 5);
    assert.equal(snap.weekly.util, 6);
    assert.equal(snap.planType, "");
  });

  it("throws when the rate-limit shape is missing", () => {
    assert.throws(() => parseCodexUsage(JSON.stringify({ plan_type: "plus" }), NOW), /missing rate-limit shape/);
    assert.throws(() => parseCodexUsage("[]", NOW), /missing rate-limit shape/);
    assert.throws(() => parseCodexUsage("<html></html>", NOW), /invalid json/);
  });

  it("ignores a negative used_percent", () => {
    const win = codexWindowFrom({ used_percent: -1, reset_at: seconds }, NOW);
    assert.equal(win, null);

    const snap = parseCodexUsage(
      payload(
        { used_percent: -1, reset_at: seconds, limit_window_seconds: 18000 },
        { used_percent: 50, reset_at: seconds, limit_window_seconds: 604800 }
      ),
      NOW
    );
    assert.equal(snap.fiveHour, null);
    assert.equal(snap.weekly.util, 50);
  });

  it("clamps used_percent into 0..100", () => {
    assert.equal(codexWindowFrom({ used_percent: 150 }, NOW).util, 100);
    assert.equal(codexWindowFrom({ used_percent: 0 }, NOW).util, 0);
    assert.equal(parseCodexUsage(payload({ used_percent: 250 }), NOW).fiveHour.util, 100);
  });

  it("normalises plan_type", () => {
    assert.equal(parseCodexUsage(payload({ used_percent: 1 }, null, { plan_type: null }), NOW).planType, "");
    assert.equal(parseCodexUsage(payload({ used_percent: 1 }, null, { plan_type: 42 }), NOW).planType, "");
    assert.equal(
      parseCodexUsage(payload({ used_percent: 1 }, null, { plan_type: "pro" }), NOW).planType,
      "pro"
    );
  });
});

// ---------------------------------------------------------------------------
// decodeJwtAccountId
// ---------------------------------------------------------------------------

describe("decodeJwtAccountId", () => {
  function jwt(payload) {
    const enc = (obj) =>
      Buffer.from(JSON.stringify(obj), "utf8").toString("base64url");
    return enc({ alg: "none", typ: "JWT" }) + "." + enc(payload) + ".signature";
  }

  it("reads chatgpt_account_id from the OpenAI auth claim", () => {
    const token = jwt({
      sub: "user-1",
      "https://api.openai.com/auth": { chatgpt_account_id: "acct_123" },
    });
    assert.equal(decodeJwtAccountId(token), "acct_123");
  });

  it("returns '' when the claim is absent or null", () => {
    assert.equal(decodeJwtAccountId(jwt({ sub: "user-1" })), "");
    assert.equal(
      decodeJwtAccountId(jwt({ "https://api.openai.com/auth": { chatgpt_account_id: null } })),
      ""
    );
  });

  it("returns '' for a malformed token", () => {
    assert.equal(decodeJwtAccountId(""), "");
    assert.equal(decodeJwtAccountId("abc"), "");
    assert.equal(decodeJwtAccountId("a.!!!.c"), "");
    assert.equal(decodeJwtAccountId(null), "");
  });
});

// ---------------------------------------------------------------------------
// resetsIn
// ---------------------------------------------------------------------------

describe("resetsIn", () => {
  it("formats days", () => {
    assert.equal(resetsIn(NOW + 5 * DAY + 2 * HOUR, NOW), "5d 2h");
  });

  it("formats hours and minutes", () => {
    assert.equal(resetsIn(NOW + 3 * HOUR + 12 * MIN, NOW), "3h 12m");
  });

  it("formats minutes", () => {
    assert.equal(resetsIn(NOW + 12 * MIN, NOW), "12m");
  });

  it("formats sub-minute remainders", () => {
    assert.equal(resetsIn(NOW + 30 * 1000, NOW), "<1m");
    assert.equal(resetsIn(NOW + 1 * 1000, NOW), "<1m");
  });

  it("returns 'now' at or past the reset instant", () => {
    assert.equal(resetsIn(NOW, NOW), "now");
    assert.equal(resetsIn(NOW - HOUR, NOW), "now");
  });

  it("returns '-' when the reset time is unknown", () => {
    assert.equal(resetsIn(0, NOW), "-");
    assert.equal(resetsIn(null, NOW), "-");
    assert.equal(resetsIn(undefined, NOW), "-");
  });

  it("handles exact unit boundaries", () => {
    assert.equal(resetsIn(NOW + 604800 * 1000, NOW), "7d 0h");
    assert.equal(resetsIn(NOW + DAY, NOW), "1d 0h");
    assert.equal(resetsIn(NOW + HOUR, NOW), "1h 0m");
    assert.equal(resetsIn(NOW + MIN, NOW), "1m");
  });
});

// ---------------------------------------------------------------------------
// pace
// ---------------------------------------------------------------------------

describe("pace", () => {
  const WINDOW = 3600; // one hour
  const resetAt = NOW + WINDOW * 1000;

  function at(elapsed, used) {
    // Place `now` so that exactly `elapsed` of the window has passed.
    const now = resetAt - WINDOW * 1000 * (1 - elapsed);
    return pace(used, resetAt, WINDOW, now);
  }

  it("returns null when the window is unknown", () => {
    assert.equal(pace(50, 0, WINDOW, NOW), null);
    assert.equal(pace(50, NOW + HOUR, 0, NOW), null);
    assert.equal(pace(50, NOW + HOUR, -1, NOW), null);
  });

  it("returns null before the window starts", () => {
    assert.equal(pace(50, NOW + HOUR, WINDOW, NOW), null);
  });

  it("returns null once the window has reset", () => {
    assert.equal(pace(50, NOW - 1000, WINDOW, NOW), null);
    assert.equal(pace(50, NOW, WINDOW, NOW), null);
  });

  it("returns null during the first 5%", () => {
    assert.equal(at(0, 0), null);
    assert.equal(at(0.04, 20), null);
    assert.equal(at(0.049, 20), null);
  });

  it("classifies at 5% elapsed", () => {
    // projected = used / 0.05
    assert.equal(at(0.05, 2), "under"); // projected 40
    assert.equal(at(0.05, 5), "on_track"); // projected 100
  });

  it("classifies under (< 85 projected)", () => {
    assert.equal(at(0.5, 25), "under"); // 50
    assert.equal(at(0.5, 42), "under"); // 84
  });

  it("classifies on_track (85..105 projected)", () => {
    assert.equal(at(0.5, 42.5), "on_track"); // 85
    assert.equal(at(0.5, 50), "on_track"); // 100
    assert.equal(at(0.5, 52.5), "on_track"); // 105
  });

  it("classifies over (105..125 projected)", () => {
    assert.equal(at(0.5, 55), "over"); // 110
    assert.equal(at(0.5, 62.5), "over"); // 125
  });

  it("classifies critical (> 125 projected)", () => {
    assert.equal(at(0.5, 65), "critical"); // 130
  });

  it("always reports critical once usage is at least 90%", () => {
    assert.equal(at(0.9, 90), "critical"); // projected 100, but >= 90 used
    assert.equal(at(0.99, 95), "critical");
  });

  it("treats a missing/NaN percentage as 0", () => {
    assert.equal(at(0.5, undefined), "under");
    assert.equal(at(0.5, "nope"), "under");
  });
});

describe("elapsedFraction", () => {
  const WINDOW = 3600;
  const resetAt = NOW + WINDOW * 1000;

  it("returns the elapsed fraction", () => {
    const now = resetAt - WINDOW * 1000 * 0.25;
    assert.equal(elapsedFraction(resetAt, WINDOW, now), 0.75);
  });

  it("returns null when unknown or inactive", () => {
    assert.equal(elapsedFraction(0, WINDOW, NOW), null);
    assert.equal(elapsedFraction(resetAt, 0, NOW), null);
    assert.equal(elapsedFraction(resetAt, WINDOW, resetAt - WINDOW * 1000), null);
    assert.equal(elapsedFraction(resetAt, WINDOW, resetAt), null);
  });
});

// ---------------------------------------------------------------------------
// windowLabel
// ---------------------------------------------------------------------------

describe("windowLabel", () => {
  it("labels the common Codex windows", () => {
    assert.equal(windowLabel(604800), "1W");
    assert.equal(windowLabel(86400), "1D");
    assert.equal(windowLabel(2592000), "30D");
    assert.equal(windowLabel(21600), "6H");
    assert.equal(windowLabel(3600), "1H");
    assert.equal(windowLabel(172800), "2D");
  });

  it("falls back to 1W for unknown or sub-hour durations", () => {
    assert.equal(windowLabel(0), "1W");
    assert.equal(windowLabel(-5), "1W");
    assert.equal(windowLabel(undefined), "1W");
    assert.equal(windowLabel(null), "1W");
    // 30 minutes is not a whole hour, so the helper reports the weekly default.
    assert.equal(windowLabel(1800), "1W");
  });
});

// ---------------------------------------------------------------------------
// isStale / updatedAgo
// ---------------------------------------------------------------------------

describe("isStale", () => {
  it("is stale when there is no snapshot", () => {
    assert.equal(isStale(0, NOW, 31), true);
    assert.equal(isStale(null, NOW, 31), true);
  });

  it("uses a strict threshold", () => {
    assert.equal(isStale(NOW - 10 * MIN, NOW, 31), false);
    assert.equal(isStale(NOW - 31 * MIN, NOW, 31), false);
    assert.equal(isStale(NOW - 31 * MIN - 1, NOW, 31), true);
  });

  it("honours an explicit window", () => {
    assert.equal(isStale(NOW - 5 * MIN, NOW, 5), false);
    assert.equal(isStale(NOW - 6 * MIN, NOW, 5), true);
  });

  it("defaults to CONFIG.staleMinutes", () => {
    assert.equal(isStale(NOW - (CONFIG.staleMinutes + 1) * MIN, NOW), true);
    assert.equal(isStale(NOW - (CONFIG.staleMinutes - 1) * MIN, NOW), false);
  });
});

describe("updatedAgo", () => {
  it("returns '' when the snapshot time is unknown", () => {
    assert.equal(updatedAgo(0, NOW), "");
    assert.equal(updatedAgo(null, NOW), "");
  });

  it("formats each magnitude", () => {
    assert.equal(updatedAgo(NOW - 30 * 1000, NOW), "just now");
    assert.equal(updatedAgo(NOW - 59 * 1000, NOW), "just now");
    assert.equal(updatedAgo(NOW - 60 * 1000, NOW), "1m ago");
    assert.equal(updatedAgo(NOW - 5 * MIN, NOW), "5m ago");
    assert.equal(updatedAgo(NOW - 59 * MIN, NOW), "59m ago");
    assert.equal(updatedAgo(NOW - HOUR, NOW), "1h ago");
    assert.equal(updatedAgo(NOW - 3 * HOUR, NOW), "3h ago");
    assert.equal(updatedAgo(NOW - 23 * HOUR, NOW), "23h ago");
    assert.equal(updatedAgo(NOW - DAY, NOW), "1d ago");
    assert.equal(updatedAgo(NOW - 2 * DAY, NOW), "2d ago");
  });
});

// ---------------------------------------------------------------------------
// planLayout
// ---------------------------------------------------------------------------

describe("planLayout", () => {
  it("uses one compact column for small", () => {
    const plan = planLayout("small", ["claude", "codex"]);
    assert.equal(plan.mode, "compact");
    assert.deepEqual(plan.providers, ["claude"]);
    assert.equal(plan.compact, true);
    assert.equal(plan.wide, false);
  });

  it("uses the full single-provider layout for medium", () => {
    const plan = planLayout("medium", ["claude"]);
    assert.equal(plan.mode, "full");
    assert.deepEqual(plan.providers, ["claude"]);
    assert.equal(plan.compact, false);
    assert.equal(plan.wide, false);
  });

  it("uses two full columns for medium with both providers", () => {
    const plan = planLayout("medium", ["claude", "codex"]);
    assert.equal(plan.mode, "columns");
    assert.deepEqual(plan.providers, ["claude", "codex"]);
    // compact=false means the countdown-capable full meter is used.
    assert.equal(plan.compact, false);
    assert.equal(plan.wide, false);
  });

  it("stacks the two providers for large", () => {
    const plan = planLayout("large", ["claude", "codex"]);
    assert.equal(plan.mode, "stacked");
    assert.deepEqual(plan.providers, ["claude", "codex"]);
    assert.equal(plan.compact, false);
  });

  it("uses a single full section for large with one provider", () => {
    assert.equal(planLayout("large", ["codex"]).mode, "full");
  });

  it("uses wide columns for extraLarge with both providers", () => {
    const plan = planLayout("extraLarge", ["claude", "codex"]);
    assert.equal(plan.mode, "columns");
    assert.equal(plan.wide, true);
    assert.equal(plan.compact, false);
    assert.deepEqual(plan.providers, ["claude", "codex"]);
  });

  it("uses a wide full layout for extraLarge with one provider", () => {
    const plan = planLayout("extraLarge", ["claude"]);
    assert.equal(plan.mode, "full");
    assert.equal(plan.wide, true);
  });

  it("maps the accessory families", () => {
    const rect = planLayout("accessoryRectangular", ["claude", "codex"]);
    assert.equal(rect.mode, "accessoryRectangular");
    assert.deepEqual(rect.providers, ["claude", "codex"]);

    const inline = planLayout("accessoryInline", ["claude", "codex"]);
    assert.equal(inline.mode, "accessoryInline");
    assert.deepEqual(inline.providers, ["claude", "codex"]);

    const circular = planLayout("accessoryCircular", ["claude", "codex"]);
    assert.equal(circular.mode, "accessoryCircular");
    assert.deepEqual(circular.providers, ["claude"]);
  });

  it("falls back to the full single-provider layout for unknown families", () => {
    const plan = planLayout("somethingNew", ["claude", "codex"]);
    assert.equal(plan.mode, "full");
    assert.deepEqual(plan.providers, ["claude"]);
    assert.equal(plan.compact, false);
    assert.equal(plan.wide, false);
  });

  it("never plans more than two providers", () => {
    for (const family of ["medium", "large", "extraLarge", "accessoryRectangular", "accessoryInline"]) {
      const plan = planLayout(family, ["claude", "codex", "extra"]);
      assert.equal(plan.providers.length, 2, family);
    }
  });

  it("tolerates empty and non-array input", () => {
    assert.deepEqual(planLayout("medium", []).providers, []);
    assert.deepEqual(planLayout("medium", null).providers, []);
    assert.deepEqual(planLayout("small", ["", null]).providers, []);
  });
});

// ---------------------------------------------------------------------------
// mergeClaudeSnapshot / mergeCodexSnapshot
// ---------------------------------------------------------------------------

describe("mergeClaudeSnapshot", () => {
  const prev = {
    fetchedAt: NOW - 2 * HOUR,
    fiveHour: { util: 42, resetsAt: NOW + HOUR },
    sevenDay: { util: 78, resetsAt: NOW + DAY },
    orgId: "org-1",
    authState: "ok",
    error: "cloudflare",
  };

  it("replaces data and clears the error on success", () => {
    const merged = mergeClaudeSnapshot(
      prev,
      {
        kind: "ok",
        snapshot: {
          fiveHour: { util: 10, resetsAt: NOW + HOUR },
          sevenDay: { util: 20, resetsAt: NOW + DAY },
          orgId: "org-2",
        },
      },
      NOW
    );
    assert.equal(merged.fetchedAt, NOW);
    assert.equal(merged.fiveHour.util, 10);
    assert.equal(merged.sevenDay.util, 20);
    assert.equal(merged.orgId, "org-2");
    assert.equal(merged.error, "");
    assert.equal(merged.authState, "ok");
  });

  it("preserves the previous orgId when the snapshot has none", () => {
    const merged = mergeClaudeSnapshot(
      prev,
      { kind: "ok", snapshot: { fiveHour: { util: 1 }, sevenDay: null } },
      NOW
    );
    assert.equal(merged.orgId, "org-1");
    assert.equal(merged.sevenDay, null);
  });

  it("keeps old numbers and the old fetchedAt on a soft failure", () => {
    const merged = mergeClaudeSnapshot(prev, { kind: "soft", reason: "cloudflare" }, NOW);
    assert.equal(merged.fetchedAt, prev.fetchedAt); // not refreshed -> stale dot
    assert.equal(merged.fiveHour.util, 42);
    assert.equal(merged.sevenDay.util, 78);
    assert.equal(merged.error, "cloudflare");
    assert.equal(merged.authState, "ok");
  });

  it("records a generic reason when none is supplied", () => {
    const merged = mergeClaudeSnapshot(prev, { kind: "soft" }, NOW);
    assert.equal(merged.error, "error");
  });

  it("sets authState on needs_login", () => {
    const merged = mergeClaudeSnapshot(prev, { kind: "needs_login" }, NOW);
    assert.equal(merged.authState, "needs_login");
    assert.equal(merged.error, "needs_login");
    assert.equal(merged.fiveHour.util, 42);
  });

  it("works from an empty cache", () => {
    const merged = mergeClaudeSnapshot(null, { kind: "soft", reason: "network" }, NOW);
    assert.equal(merged.fetchedAt, 0);
    assert.equal(merged.fiveHour, null);
    assert.equal(merged.error, "network");
  });
});

describe("mergeCodexSnapshot", () => {
  const prev = {
    fetchedAt: NOW - HOUR,
    fiveHour: { util: 12, resetsAt: NOW + HOUR },
    weekly: { util: 64, resetsAt: NOW + DAY },
    weeklyWindowSeconds: 604800,
    planType: "plus",
    authState: "ok",
    error: "",
  };

  it("replaces data and keeps the window metadata on success", () => {
    const merged = mergeCodexSnapshot(
      prev,
      {
        kind: "ok",
        snapshot: {
          fiveHour: { util: 30, resetsAt: NOW + HOUR },
          weekly: { util: 70, resetsAt: NOW + DAY },
          weeklyWindowSeconds: 2592000,
          planType: "pro",
        },
      },
      NOW
    );
    assert.equal(merged.fetchedAt, NOW);
    assert.equal(merged.fiveHour.util, 30);
    assert.equal(merged.weekly.util, 70);
    assert.equal(merged.weeklyWindowSeconds, 2592000);
    assert.equal(merged.planType, "pro");
    assert.equal(merged.error, "");
  });

  it("falls back to cached metadata on success", () => {
    const merged = mergeCodexSnapshot(
      prev,
      { kind: "ok", snapshot: { fiveHour: null, weekly: { util: 5 } } },
      NOW
    );
    assert.equal(merged.weeklyWindowSeconds, 604800);
    assert.equal(merged.planType, "plus");
  });

  it("keeps old numbers, window length and fetchedAt on a soft failure", () => {
    const merged = mergeCodexSnapshot(prev, { kind: "soft", reason: "parse" }, NOW);
    assert.equal(merged.fetchedAt, prev.fetchedAt);
    assert.equal(merged.weekly.util, 64);
    assert.equal(merged.weeklyWindowSeconds, 604800);
    assert.equal(merged.planType, "plus");
    assert.equal(merged.error, "parse");
  });

  it("sets authState on needs_login", () => {
    const merged = mergeCodexSnapshot(prev, { kind: "needs_login" }, NOW);
    assert.equal(merged.authState, "needs_login");
    assert.equal(merged.weekly.util, 64);
  });

  it("clamps and normalises merged windows", () => {
    const merged = mergeCodexSnapshot(
      prev,
      { kind: "ok", snapshot: { fiveHour: { util: 250, resetsAt: "not-a-time" }, weekly: null } },
      NOW
    );
    assert.equal(merged.fiveHour.util, 100);
    assert.equal(merged.fiveHour.resetsAt, 0);
    assert.equal(merged.weekly, null);
  });
});

// ---------------------------------------------------------------------------
// selectProviders
// ---------------------------------------------------------------------------

describe("selectProviders", () => {
  const both = ["claude", "codex"];

  it("selects an explicit provider", () => {
    assert.deepEqual(selectProviders("claude", both), ["claude"]);
    assert.deepEqual(selectProviders("codex", both), ["codex"]);
  });

  it("is case-insensitive and trims whitespace", () => {
    assert.deepEqual(selectProviders("  CLAUDE ", both), ["claude"]);
    assert.deepEqual(selectProviders("\tCoDeX\n", both), ["codex"]);
    assert.deepEqual(selectProviders("  both  ", both), ["claude", "codex"]);
  });

  it("defaults to the config providers filtered to signed-in ones", () => {
    assert.deepEqual(selectProviders("", both), ["claude", "codex"]);
    assert.deepEqual(selectProviders(null, both), ["claude", "codex"]);
    assert.deepEqual(selectProviders(undefined, ["codex"]), ["codex"]);
  });

  it("treats an invalid parameter as no preference", () => {
    assert.deepEqual(selectProviders("garbage", both), ["claude", "codex"]);
    assert.deepEqual(selectProviders("garbage", ["codex"]), ["codex"]);
  });

  it("honours an explicit parameter even when that provider is unavailable", () => {
    assert.deepEqual(selectProviders("claude", ["codex"]), ["claude"]);
    assert.deepEqual(selectProviders("both", ["claude"]), ["claude", "codex"]);
    assert.deepEqual(selectProviders("codex", []), ["codex"]);
  });

  it("ignores unknown signed-in ids", () => {
    assert.deepEqual(selectProviders("both", ["claude", "android"]), ["claude", "codex"]);
    assert.deepEqual(selectProviders("", ["claude", "android"]), ["claude"]);
  });
});

describe("providersToRefresh", () => {
  it("includes providers that are signed in", () => {
    assert.deepEqual(providersToRefresh(["claude", "codex"], {}), ["claude", "codex"]);
  });

  it("includes a provider with a cached snapshot but no harvested cookie", () => {
    const cache = {
      claude: { fetchedAt: NOW },
      codex: { fetchedAt: 0 },
    };
    assert.deepEqual(providersToRefresh([], cache), ["claude"]);
  });

  it("returns nothing when no provider is signed in or cached", () => {
    assert.deepEqual(providersToRefresh([], null), []);
    assert.deepEqual(providersToRefresh([], { claude: { fetchedAt: 0 } }), []);
  });

  it("keeps the signed-in provider even when the cache is empty", () => {
    assert.deepEqual(providersToRefresh(["codex"], { claude: { fetchedAt: 0 } }), ["codex"]);
  });
});

describe("isClaudeLink", () => {
  it("accepts HTTPS links on claude.ai and its subdomains", () => {
    assert.equal(isClaudeLink("https://claude.ai/login?code=abc"), true);
    assert.equal(isClaudeLink("https://www.claude.ai/magic-link#token"), true);
    assert.equal(isClaudeLink("https://foo.claude.ai/path"), true);
  });

  it("rejects insecure, foreign and credentialed links", () => {
    assert.equal(isClaudeLink("http://claude.ai/login"), false);
    assert.equal(isClaudeLink("https://evil.example/claude.ai"), false);
    assert.equal(isClaudeLink("https://user:pass@claude.ai/login"), false);
    assert.equal(isClaudeLink(""), false);
    assert.equal(isClaudeLink(null), false);
  });
});

// ---------------------------------------------------------------------------
// Cookie helpers
// ---------------------------------------------------------------------------

describe("parseCookieHeader", () => {
  it("splits a raw Cookie header into a name -> value map", () => {
    assert.deepEqual(parseCookieHeader("a=1; b=2; c=x=y"), { a: "1", b: "2", c: "x=y" });
  });

  it("skips empty/valueless segments and tolerates non-strings", () => {
    assert.deepEqual(parseCookieHeader("; ; bad; =empty; ok=yes"), { ok: "yes" });
    assert.deepEqual(parseCookieHeader(""), {});
    assert.deepEqual(parseCookieHeader(null), {});
  });
});

describe("hasSessionCookie", () => {
  it("finds a plain cookie", () => {
    assert.equal(hasSessionCookie("sessionKey=abc; other=1", "sessionKey"), true);
    assert.equal(hasSessionCookie("other=1; sessionKey=abc", "sessionKey"), true);
  });

  it("finds NextAuth's chunked .0 form", () => {
    assert.equal(
      hasSessionCookie("foo=1; __Secure-next-auth.session-token.0=abc", "__Secure-next-auth.session-token"),
      true
    );
  });

  it("does not match a prefix-only name", () => {
    assert.equal(hasSessionCookie("sessionKeyExtra=1", "sessionKey"), false);
    assert.equal(hasSessionCookie("", "sessionKey"), false);
  });
});

describe("hasProviderSession", () => {
  it("recognises a Claude sessionKey", () => {
    assert.equal(hasProviderSession("sessionKey=abc", "claude"), true);
    assert.equal(hasProviderSession("other=1", "claude"), false);
  });

  it("recognises all three Codex session cookie names", () => {
    const names = [
      "__Secure-next-auth.session-token",
      "__Secure-authjs.session-token",
      "__Host-next-auth.session-token",
    ];
    for (const name of names) {
      assert.equal(hasProviderSession(name + "=abc", "codex"), true, name);
      assert.equal(hasProviderSession(name + ".0=abc; " + name + ".1=def", "codex"), true, name + ".0");
    }
    assert.equal(hasProviderSession("sessionKey=abc", "codex"), false);
  });
});

describe("chunkedCookieValue", () => {
  it("returns the exact cookie when present", () => {
    assert.equal(chunkedCookieValue({ n: "exact", "n.0": "a" }, "n"), "exact");
  });

  it("joins chunks in numeric order", () => {
    assert.equal(chunkedCookieValue({ "n.1": "b", "n.0": "a", "n.2": "c" }, "n"), "abc");
    assert.equal(chunkedCookieValue({ "n.10": "c", "n.2": "b", "n.1": "a" }, "n"), "abc");
  });

  it("skips non-numeric suffixes and returns '' when absent", () => {
    assert.equal(chunkedCookieValue({ "n.foo": "x" }, "n"), "");
    assert.equal(chunkedCookieValue({}, "n"), "");
    assert.equal(chunkedCookieValue({ other: "1" }, "n"), "");
  });
});

// ---------------------------------------------------------------------------
// pickClaudeOrg
// ---------------------------------------------------------------------------

describe("pickClaudeOrg", () => {
  it("prefers the chat-capable org over an api-only org", () => {
    const orgs = [
      { id: "org-api", uuid: "uuid-api", capabilities: ["api"] },
      { id: "org-chat", uuid: "uuid-chat", capabilities: ["chat"] },
    ];
    assert.equal(pickClaudeOrg(orgs), "uuid-chat");
  });

  it("recognises the claude_ai capability", () => {
    assert.equal(
      pickClaudeOrg([{ id: "org-1", capabilities: ["claude_ai"] }]),
      "org-1"
    );
    assert.equal(
      pickClaudeOrg([{ uuid: "uuid-1", capabilities: ["Claude_AI", "other"] }]),
      "uuid-1"
    );
  });

  it("prefers uuid over id", () => {
    assert.equal(pickClaudeOrg([{ id: "id-1", uuid: "uuid-1", capabilities: ["chat"] }]), "uuid-1");
  });

  it("falls back to the first org when none is chat-capable", () => {
    assert.equal(pickClaudeOrg([{ id: "first" }, { id: "second" }]), "first");
    assert.equal(pickClaudeOrg([{ uuid: "uuid-first" }]), "uuid-first");
  });

  it("returns '' for empty or invalid input", () => {
    assert.equal(pickClaudeOrg([]), "");
    assert.equal(pickClaudeOrg(null), "");
    assert.equal(pickClaudeOrg({}), "");
    assert.equal(pickClaudeOrg([{}]), "");
    assert.equal(pickClaudeOrg([null, { id: "x", capabilities: ["chat"] }]), "x");
  });
});

// ---------------------------------------------------------------------------
// looksLikeHtml
// ---------------------------------------------------------------------------

describe("looksLikeHtml", () => {
  it("detects an HTML body", () => {
    assert.equal(looksLikeHtml("<html><body>hi</body></html>"), true);
    assert.equal(looksLikeHtml("   \n\t<!DOCTYPE html>"), true);
  });

  it("detects a Cloudflare challenge page", () => {
    assert.equal(looksLikeHtml("Checking your browser... Just a moment..."), true);
    assert.equal(looksLikeHtml("JUST A MOMENT"), true);
  });

  it("rejects JSON and empty bodies", () => {
    assert.equal(looksLikeHtml('{"five_hour":{}}'), false);
    assert.equal(looksLikeHtml(""), false);
    assert.equal(looksLikeHtml(null), false);
    assert.equal(looksLikeHtml(undefined), false);
  });
});
