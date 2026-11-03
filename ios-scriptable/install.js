/**
 * Claude + Codex Usage Widget — installer for Scriptable.
 *
 * This is a tiny bootstrap: copy/paste THIS file into a new Scriptable script
 * (name it anything, e.g. "install"), run it, and it downloads the full widget
 * into Scriptable's script folder. You do not need to copy the 2,000-line
 * widget by hand.
 *
 * It tries master first and then the feature branch, so it works both before
 * and after the pull request is merged.
 *
 * Security: this downloads JavaScript from this repository and saves it as a
 * Scriptable script. Only run it if you trust the repository. No credentials
 * are touched; the widget stores sign-in cookies in Scriptable's Keychain.
 */

const SOURCES = [
  "https://raw.githubusercontent.com/chongtzezhao/claude-usage-widget/master/ios-scriptable/claude-usage-widget.js",
  "https://raw.githubusercontent.com/chongtzezhao/claude-usage-widget/feat/scriptable-ios-widget/ios-scriptable/claude-usage-widget.js",
];

const TARGET_NAME = "claude-usage-widget.js";

function looksLikeWidget(code) {
  return (
    typeof code === "string" &&
    code.indexOf("function parseClaudeUsage") !== -1 &&
    code.indexOf("function parseCodexUsage") !== -1 &&
    code.indexOf("Script.setWidget(widget)") !== -1
  );
}

async function fetchSource(url) {
  const req = new Request(url);
  req.timeoutInterval = 30;
  req.headers = { "Cache-Control": "no-cache" };
  const body = await req.loadString();
  if (!looksLikeWidget(body)) {
    const status = req.response ? req.response.statusCode : "?";
    throw new Error("unexpected content from " + url + " (HTTP " + status + ")");
  }
  return body;
}

/** Scriptable stores scripts in documentsDirectory (iCloud when enabled). */
function scriptFolder() {
  try {
    const fm = FileManager.iCloud();
    return { fm: fm, dir: fm.documentsDirectory(), cloud: true };
  } catch (e) {
    const fm = FileManager.local();
    return { fm: fm, dir: fm.documentsDirectory(), cloud: false };
  }
}

async function main() {
  let code = "";
  let source = "";
  let lastError = null;
  for (let i = 0; i < SOURCES.length && !code; i++) {
    try {
      code = await fetchSource(SOURCES[i]);
      source = SOURCES[i];
    } catch (e) {
      lastError = e;
    }
  }
  if (!code) {
    throw new Error(
      "Could not download the widget. " +
        (lastError ? lastError.message : "unknown error") +
        "\n\nOpen the widget file in Safari and use Share → Save to Files → iCloud Drive → Scriptable instead."
    );
  }

  const folder = scriptFolder();
  let target = folder.fm.joinPath(folder.dir, TARGET_NAME);
  let cloud = folder.cloud;
  try {
    folder.fm.writeString(target, code);
  } catch (e) {
    // FileManager.iCloud() can exist while its container is unavailable
    // (iCloud disabled / not signed in). Fall back to the local script folder.
    const local = FileManager.local();
    target = local.joinPath(local.documentsDirectory(), TARGET_NAME);
    local.writeString(target, code);
    cloud = false;
  }

  const alert = new Alert();
  alert.title = "Installed";
  alert.message =
    TARGET_NAME +
    " was saved to Scriptable" +
    (cloud ? " (iCloud folder)" : " (local folder)") +
    ".\n\nClose this alert, open the script list, and run " +
    TARGET_NAME.replace(/\.js$/, "") +
    " to sign in.\n\nSource: " +
    source;
  alert.addAction("OK");
  await alert.present();
}

main().catch(async function (err) {
  try {
    const alert = new Alert();
    alert.title = "Install failed";
    alert.message = String((err && err.message) || err);
    alert.addAction("OK");
    await alert.present();
  } catch (e) {
    // If alerts are unavailable (e.g. Shortcuts), the error is intentionally
    // silent; the message above is enough for the app path.
  }
});
