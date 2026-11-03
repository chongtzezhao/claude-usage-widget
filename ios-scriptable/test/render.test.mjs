import { test } from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";

const require = createRequire(import.meta.url);

/*
 * Render smoke tests. Scriptable's UI classes are not available in Node, so
 * this file installs small mocks of the documented API surface and runs the
 * real layout code (`buildWidget`) for every widget family. It catches:
 *   - thrown errors / undefined methods on any layout branch
 *   - null/undefined window handling
 *   - bar widths that would overflow real iPhone/iPad widget geometry
 *   - missing text for sign-in / unavailable / weekly-only states
 */

class MockText {
  constructor(text) {
    this.text = String(text);
    this.font = null;
    this.textColor = null;
    this.lineLimit = 0;
    this.minimumScaleFactor = 1;
  }
  centerAlignText() { this.centered = true; }
}

class MockImage {
  constructor(image) {
    this.image = image;
    this.imageSize = null;
    this.tintColor = null;
  }
}

class MockStack {
  constructor(root) {
    this.root = root || this;
    this.children = [];
    this.layout = null;
    this.spacing = 0;
    this.size = null;
  }
  addStack() {
    const child = new MockStack(this.root);
    this.children.push(child);
    return child;
  }
  addText(value) {
    const text = new MockText(value);
    this.children.push(text);
    this.root.allTexts.push(text.text);
    return text;
  }
  addImage(image) {
    const img = new MockImage(image);
    this.children.push(img);
    this.root.allImages.push(img);
    return img;
  }
  addSpacer() { return { size: 0 }; }
  layoutHorizontally() { this.layout = "horizontal"; }
  layoutVertically() { this.layout = "vertical"; }
  centerAlignContent() { this.align = "center"; }
  setPadding() { this.padded = true; }
}

class MockListWidget extends MockStack {
  constructor() {
    super(null);
    this.root = this;
    this.allTexts = [];
    this.allImages = [];
    this.backgroundColor = null;
    this.url = null;
    this.refreshAfterDate = null;
    this.addAccessoryWidgetBackground = false;
  }
}

class MockColor {
  constructor(value) { this.value = value; }
  static dynamic(light, dark) { return new MockColor(["dynamic", light, dark]); }
}

class MockPath {
  addRoundedRect(rect, cornerWidth, cornerHeight) {
    if (cornerHeight === undefined) {
      throw new Error("Path.addRoundedRect needs cornerWidth and cornerHeight");
    }
  }
  addRect() {}
  addEllipse() {}
}

class MockDrawContext {
  constructor() {
    this.size = null;
    this.opaque = true;
    this.paths = 0;
  }
  addPath() { this.paths += 1; }
  setFillColor() {}
  fillPath() {}
  getImage() { return { drawn: true }; }
}

class MockRect {
  constructor(x, y, width, height) {
    Object.assign(this, { x, y, width, height });
  }
}

class MockSize {
  constructor(width, height) {
    Object.assign(this, { width, height });
  }
}

class MockKeychain {
  constructor() { this.values = new Map(); }
  contains(key) { return this.values.has(key); }
  get(key) { return this.values.get(key); }
  set(key, value) { this.values.set(key, value); }
  remove(key) { this.values.delete(key); }
  clear() { this.values.clear(); }
}

function installMocks() {
  const keychain = new MockKeychain();
  const files = new Map();
  const fm = {
    local() {
      return {
        documentsDirectory: () => "/mock/Documents",
        joinPath: (dir, name) => dir.replace(/\/$/, "") + "/" + name,
        fileExists: (path) => files.has(path),
        readString: (path) => files.get(path),
        writeString: (path, value) => files.set(path, value),
        remove: (path) => files.delete(path),
        move: (from, to) => { files.set(to, files.get(from)); files.delete(from); },
      };
    },
  };
  Object.assign(globalThis, {
    ListWidget: MockListWidget,
    Color: MockColor,
    Path: MockPath,
    DrawContext: MockDrawContext,
    Rect: MockRect,
    Size: MockSize,
    Keychain: keychain,
    FileManager: { local: fm.local },
    Device: { isUsingDarkAppearance: () => false },
    Font: {
      boldSystemFont: (size) => ({ size, bold: true }),
      mediumSystemFont: (size) => ({ size, medium: true }),
      regularSystemFont: (size) => ({ size }),
    },
    SFSymbol: { named: (name) => ({ image: { symbol: name } }) },
    Script: { name: () => "claude-usage-widget", complete() {}, setWidget() {} },
  });
  return { keychain, files };
}

installMocks();
const widgetScript = require("../claude-usage-widget.js");

test("runtime guard detects Scriptable by its globals, not by module being absent", () => {
  const source = readFileSync(new URL("../claude-usage-widget.js", import.meta.url), "utf8");
  assert.match(source, /const IS_SCRIPTABLE/);
  assert.match(source, /typeof Script !== "undefined"/);
  assert.match(source, /typeof config !== "undefined"/);
  assert.doesNotMatch(source, /if \(typeof module === "undefined"\)/);
});

const NOW = Date.parse("2026-11-04T12:00:00.000Z");
const HOUR = 3600 * 1000;
const DAY = 24 * HOUR;

function richCache() {
  return {
    version: 1,
    claude: {
      fetchedAt: NOW - 5 * 60 * 1000,
      fiveHour: { util: 42, resetsAt: NOW + 2 * HOUR },
      sevenDay: { util: 78, resetsAt: NOW + 3 * DAY },
      orgId: "org-1",
      authState: "ok",
      error: "",
    },
    codex: {
      fetchedAt: NOW - 40 * 60 * 1000, // stale
      fiveHour: null,
      weekly: { util: 88, resetsAt: NOW + 4 * DAY },
      weeklyWindowSeconds: 604800,
      planType: "plus",
      authState: "ok",
      error: "",
    },
  };
}

function signInBoth() {
  globalThis.Keychain.set("cuw.claude.sessionKey", "sk-test");
  globalThis.Keychain.set("cuw.codex.cookie", "__Secure-next-auth.session-token=abc");
}

const FAMILIES = [
  "small",
  "medium",
  "large",
  "extraLarge",
  "accessoryInline",
  "accessoryCircular",
  "accessoryRectangular",
];

test("renders every family with both providers without throwing", () => {
  signInBoth();
  for (const family of FAMILIES) {
    const widget = widgetScript.buildWidget(family, richCache(), ["claude", "codex"], NOW);
    assert.ok(widget, family);
    assert.ok(widget.allTexts.length > 0 || family === "accessoryCircular", family);
    if (family.startsWith("accessory")) {
      assert.equal(widget.backgroundColor, null, family + " should keep a transparent background");
    } else {
      assert.ok(widget.backgroundColor, family + " should set a background");
    }
  }
});

test("medium with both providers shows percentages and reset countdowns", () => {
  signInBoth();
  const widget = widgetScript.buildWidget("medium", richCache(), ["claude", "codex"], NOW);
  const text = widget.allTexts.join(" | ");
  assert.match(text, /42%/);
  assert.match(text, /78%/);
  assert.match(text, /88%/);
  // Reset countdown, not just a percentage-only compact row.
  assert.match(text, /2h 0m/);
  assert.equal(widget.children[0].layout, "horizontal");
});

test("large with one provider renders full rows and a stale indicator", () => {
  signInBoth();
  const cache = richCache();
  cache.claude.fetchedAt = NOW - 40 * 60 * 1000;
  const widget = widgetScript.buildWidget("large", cache, ["claude"], NOW);
  const text = widget.allTexts.join(" | ");
  assert.match(text, /Claude usage/);
  assert.match(text, /updated 40m ago/);
  assert.match(text, /5H/);
  assert.match(text, /1W/);
});

test("extraLarge with both providers keeps bars inside the column width", () => {
  signInBoth();
  const widget = widgetScript.buildWidget("extraLarge", richCache(), ["claude", "codex"], NOW);
  const bars = widget.allImages.filter((img) => img.imageSize && img.imageSize.height <= 6);
  assert.ok(bars.length >= 3, "expected three drawn bars");
  for (const bar of bars) {
    assert.ok(bar.imageSize.width <= 300, "extraLarge column bar too wide: " + bar.imageSize.width);
  }
  assert.equal(widget.children[0].layout, "horizontal");
});

test("small shows only one provider and omits countdowns", () => {
  signInBoth();
  const widget = widgetScript.buildWidget("small", richCache(), ["claude", "codex"], NOW);
  const text = widget.allTexts.join(" | ");
  assert.match(text, /42%/);
  assert.doesNotMatch(text, /88%/);
  assert.doesNotMatch(text, /2h 0m/);
});

test("accessory rectangular shows both windows for one provider and both providers otherwise", () => {
  signInBoth();
  const single = widgetScript.buildWidget(
    "accessoryRectangular", richCache(), ["claude"], NOW
  );
  const singleText = single.allTexts.join(" | ");
  assert.match(singleText, /5H 42%/);
  assert.match(singleText, /1W 78%/);
  assert.match(singleText, /2h 0m/);

  const both = widgetScript.buildWidget(
    "accessoryRectangular", richCache(), ["claude", "codex"], NOW
  );
  const bothText = both.allTexts.join(" | ");
  assert.match(bothText, /Claude 5H 42% · 1W 78%/);
  assert.match(bothText, /Codex 1W 88%/);
});

test("a failed first fetch shows 'Open the app' instead of 'No usage limits'", () => {
  signInBoth();
  const cache = richCache();
  cache.claude = {
    fetchedAt: 0,
    fiveHour: null,
    sevenDay: null,
    orgId: "",
    authState: "ok",
    error: "cloudflare",
  };
  const widget = widgetScript.buildWidget("large", cache, ["claude"], NOW);
  const text = widget.allTexts.join(" | ");
  assert.match(text, /Open the app to refresh/);
  assert.doesNotMatch(text, /No usage limits/);
  assert.doesNotMatch(text, /0%/);
});

test("no credentials and no cache shows the sign-in placeholder", () => {
  globalThis.Keychain.clear();
  const empty = {
    version: 1,
    claude: { fetchedAt: 0, fiveHour: null, sevenDay: null, orgId: "", authState: "ok", error: "" },
    codex: { fetchedAt: 0, fiveHour: null, weekly: null, weeklyWindowSeconds: 604800, planType: "", authState: "ok", error: "" },
  };
  const widget = widgetScript.buildWidget("medium", empty, [], NOW);
  const text = widget.allTexts.join(" | ");
  assert.match(text, /Tap to sign in/);
});

test("weekly-only Codex renders a weekly row and a caption", () => {
  signInBoth();
  const cache = richCache();
  const widget = widgetScript.buildWidget("large", cache, ["codex"], NOW);
  const text = widget.allTexts.join(" | ");
  assert.match(text, /1W/);
  assert.match(text, /Weekly-only plan/);
});
