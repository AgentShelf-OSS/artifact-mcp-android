// Emulator test for the viewer: edge-to-edge insets, geolocation, the native bridge and the IME.
// Run through test-viewer.sh (it checks the prerequisites). Needs Node 22+ on the Docker host.
//
// Evidence goes to $OUT (default /tmp/emulator-test): PNG screenshots, UI dumps, logcat and
// results.txt. Every assertion prints PASS or FAIL; the exit code is the number of failures.
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";
import { evaluate } from "./cdp.mjs";

const EMU = process.env.EMU_CONTAINER || "artifact-mcp-android-emulator";
const OUT = process.env.OUT || "/tmp/emulator-test";
const APK = process.env.APK || "/src/app/build/outputs/apk/debug/app-debug.apk"; // path inside the emulator container
const PKG = "ai.agentshelf.artifactmcp";
const env = Object.fromEntries(
  readFileSync(`${OUT}/artifacts.env`, "utf8").trim().split("\n").map((l) => l.split("=")),
);
const ORIGIN = env.ORIGIN;
const PORT = new URL(ORIGIN).port;
// Las Vegas Strip, as in the road-trip plan.
const FIX = { lon: -115.1761, lat: 36.0955 };
const LIGHT_CHROME = [0xee, 0xf0, 0xee];
const DARK_CHROME = [0x15, 0x18, 0x1b];

mkdirSync(OUT, { recursive: true });
for (const f of readdirSync(OUT)) if (/^\d\d-.*\.(png|xml)$/.test(f)) rmSync(`${OUT}/${f}`);
const results = [];
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function adb(args, { binary = false, check = true } = {}) {
  try {
    return execFileSync("docker", ["exec", "-i", EMU, "adb", ...args], {
      encoding: binary ? "buffer" : "utf8", maxBuffer: 64 * 1024 * 1024, stdio: ["ignore", "pipe", "pipe"],
    });
  } catch (error) {
    if (check) throw new Error(`adb ${args.join(" ")}: ${error.stderr || error.message}`);
    return binary ? Buffer.alloc(0) : String(error.stdout || "");
  }
}
const sh = (cmd, opts) => adb(["shell", cmd], opts);

function record(name, pass, detail) {
  results.push({ name, pass, detail });
  console.log(`${pass ? "PASS" : "FAIL"} ${name}: ${detail}`);
}
function log(line) { console.log(`.... ${line}`); }

async function waitFor(what, fn, { timeoutMs = 60000, intervalMs = 1500 } = {}) {
  const end = Date.now() + timeoutMs;
  let last;
  while (Date.now() < end) {
    try {
      last = await fn();
      if (last) return last;
    } catch (error) {
      last = error.message;
    }
    await sleep(intervalMs);
  }
  throw new Error(`timed out waiting for ${what} (last: ${JSON.stringify(last)?.slice(0, 200)})`);
}

// ---- screen evidence ---------------------------------------------------------------------

function shot(name) {
  const png = adb(["exec-out", "screencap", "-p"], { binary: true });
  writeFileSync(`${OUT}/${name}.png`, png);
  log(`screenshot ${OUT}/${name}.png`);
}

/** Raw RGBA screenshot for pixel checks (header: width, height, format[, colour space]). */
function pixels() {
  const raw = adb(["exec-out", "screencap"], { binary: true });
  const width = raw.readUInt32LE(0);
  const height = raw.readUInt32LE(4);
  const header = raw.length - width * height * 4;
  return { width, height, at: (x, y) => [...raw.subarray(header + (y * width + x) * 4, header + (y * width + x) * 4 + 3)] };
}

function uiDump(name) {
  for (let i = 0; i < 5; i++) {
    const out = sh("uiautomator dump /sdcard/ui.xml >/dev/null 2>&1 && cat /sdcard/ui.xml", { check: false });
    if (out.includes("<hierarchy")) {
      if (name) writeFileSync(`${OUT}/${name}.xml`, out);
      return parseNodes(out);
    }
  }
  throw new Error("uiautomator dump failed");
}

function parseNodes(xml) {
  return [...xml.matchAll(/<node [^>]*>/g)].map(([n]) => {
    const get = (k) => (n.match(new RegExp(` ${k}="([^"]*)"`)) || [])[1] || "";
    const b = get("bounds").match(/\[(\d+),(\d+)\]\[(\d+),(\d+)\]/).slice(1).map(Number);
    return { cls: get("class"), text: get("text"), id: get("resource-id"), pkg: get("package"), left: b[0], top: b[1], right: b[2], bottom: b[3] };
  });
}
const center = (n) => [Math.round((n.left + n.right) / 2), Math.round((n.top + n.bottom) / 2)];
const tap = ([x, y]) => sh(`input tap ${x} ${y}`);

/** Visible system bar and IME frames from the window manager. */
function insets() {
  const dump = sh("dumpsys window");
  const frames = (type) => [...dump.matchAll(new RegExp(`InsetsSource id=\\S+ type=${type} frame=\\[(\\d+),(\\d+)\\]\\[(\\d+),(\\d+)\\] (?:visibleFrame=\\S+ )?visible=true`, "g"))]
    .map((m) => m.slice(1).map(Number));
  const top = Math.max(0, ...frames("statusBars").map((f) => f[3]), ...frames("displayCutout").filter((f) => f[1] === 0).map((f) => f[3]));
  const navTop = Math.min(...frames("navigationBars").map((f) => f[1]));
  const ime = frames("ime").filter((f) => f[1] > 0 && f[3] > f[1]);
  const appearance = (dump.match(/mLastAppearance=([^\n]*)/) || [])[1] || "";
  return { top, navTop, imeTop: ime.length ? Math.min(...ime.map((f) => f[1])) : null, appearance: appearance.trim() };
}

function webView(nodes) {
  const views = nodes.filter((n) => n.cls === "android.webkit.WebView" && n.pkg === PKG);
  if (!views.length) throw new Error("no WebView in the UI dump");
  return views[views.length - 1];
}

// ---- app and WebView -----------------------------------------------------------------------

function forwardDevtools() {
  const pid = sh(`pidof ${PKG}`, { check: false }).trim();
  if (!pid) throw new Error("app is not running");
  adb(["forward", "tcp:9222", `localabstract:webview_devtools_remote_${pid}`]);
  return pid;
}

const top = (expr) => evaluate(expr);
const inFrame = (expr) => evaluate(expr, { frame: true });

function openViewer(url) {
  sh(`am start -a android.intent.action.VIEW -d '${url}' -n ${PKG}/.ui.MainActivity`, { check: false });
}

async function waitForPage(url) {
  await waitFor(`page ${url}`, async () => {
    forwardDevtools();
    return (await top("location.href + '|' + document.readyState")) === `${url}|complete`;
  }, { timeoutMs: 90000 });
}

/** Device pixel centre of an element in the artifact frame (scrolled into view first). */
async function frameElementCenter(selector) {
  const wv = webView(uiDump());
  const [left, topCss, dpr] = await top("(()=>{const r=document.querySelector('iframe').getBoundingClientRect();return [r.left,r.top,devicePixelRatio]})()");
  const [x, y] = await inFrame(`(()=>{const e=document.querySelector(${JSON.stringify(selector)});e.scrollIntoView({block:'center'});const r=e.getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()`);
  await sleep(800);
  const [x2, y2] = await inFrame(`(()=>{const r=document.querySelector(${JSON.stringify(selector)}).getBoundingClientRect();return [r.left+r.width/2,r.top+r.height/2]})()`);
  return [Math.round(wv.left + (left + (x2 ?? x)) * dpr), Math.round(wv.top + (topCss + (y2 ?? y)) * dpr)];
}

/** The shell's own location consent sheet: wait for it, then tap the named button. */
async function answerShellConsent(label, screenshot) {
  const button = await waitFor("shell consent sheet", () => top(`(()=>{
    const b=[...document.querySelectorAll('button')].find(b=>b.innerText.trim()===${JSON.stringify(label)}&&b.getClientRects().length);
    if(!b)return null;const r=b.getBoundingClientRect();let box=b;
    while(box.parentElement&&!/location/i.test(box.innerText))box=box.parentElement;
    return [r.left+r.width/2,r.top+r.height/2,devicePixelRatio,box.innerText.replace(/\\s+/g,' ').slice(0,160)];})()`), { timeoutMs: 30000 });
  if (screenshot) shot(screenshot);
  const wv = webView(uiDump());
  tap([Math.round(wv.left + button[0] * button[2]), Math.round(wv.top + button[1] * button[2])]);
  return button[3];
}

async function runtimePrompt() {
  return waitFor("Android location permission prompt", () => {
    const nodes = uiDump();
    return nodes.some((n) => /permission_deny\w*_button$/.test(n.id)) ? nodes : null;
  }, { timeoutMs: 90000, intervalMs: 1000 });
}

function setGeoFix() {
  adb(["emu", "geo", "fix", String(FIX.lon), String(FIX.lat)]);
}

async function probeStatus() {
  return inFrame("document.getElementById('status').textContent");
}

function checkInsets(label, { night }) {
  const nodes = uiDump(`${label}-ui`);
  const wv = webView(nodes);
  const bars = insets();
  record(`${label}: WebView starts below the status bar and cutout`, wv.top >= bars.top && bars.top > 0,
    `WebView top ${wv.top}px, status bar/cutout bottom ${bars.top}px`);
  record(`${label}: WebView ends above the navigation bar`, wv.bottom <= bars.navTop,
    `WebView bottom ${wv.bottom}px, navigation bar top ${bars.navTop}px`);
  const px = pixels();
  const strip = px.at(Math.round(px.width / 2), Math.max(2, Math.round(bars.top / 2)));
  const want = night ? DARK_CHROME : LIGHT_CHROME;
  const close = strip.every((v, i) => Math.abs(v - want[i]) <= 6);
  record(`${label}: status-bar strip is the ${night ? "dark" : "light"} viewer chrome`, close,
    `pixel ${JSON.stringify(strip)}, expected ${JSON.stringify(want)}`);
  const nav = px.at(24, Math.round((bars.navTop + px.height) / 2));
  record(`${label}: navigation-bar area is the ${night ? "dark" : "light"} viewer chrome`,
    nav.every((v, i) => Math.abs(v - want[i]) <= 6), `pixel ${JSON.stringify(nav)}, expected ${JSON.stringify(want)}`);
  const lightIcons = !bars.appearance.includes("LIGHT_STATUS_BARS");
  // Contrast of the clock/icons against the strip: some pixel in the bar must differ strongly.
  let maxDelta = 0;
  for (let y = 4; y < bars.top - 4; y += 2) {
    for (let x = 0; x < px.width; x += 3) {
      const p = px.at(x, y);
      maxDelta = Math.max(maxDelta, Math.abs(p[0] - strip[0]) + Math.abs(p[1] - strip[1]) + Math.abs(p[2] - strip[2]));
    }
  }
  record(`${label}: status icons are ${night ? "light" : "dark"} and visible`, lightIcons === night && maxDelta > 300,
    `appearance "${bars.appearance}", strongest icon contrast ${maxDelta}/765`);
  return { wv, bars };
}

// ---- the test ------------------------------------------------------------------------------

async function main() {
  log(`origin ${ORIGIN}, probe ${env.PROBE_ID}, planner ${env.PLANNER_ID}`);
  const release = sh("getprop ro.build.version.release").trim();
  const sdk = sh("getprop ro.build.version.sdk").trim();
  const webview = (sh("dumpsys webviewupdate").match(/Current WebView package \(name, version\): \(([^)]*)\)/) || [])[1];
  log(`Android ${release} (API ${sdk}), WebView ${webview}`);
  writeFileSync(`${OUT}/versions.txt`, `Android ${release} (API ${sdk})\nWebView ${webview}\n`);

  sh("cmd uimode night no");
  adb(["uninstall", PKG], { check: false });
  adb(["install", APK]);
  adb(["reverse", `tcp:${PORT}`, `tcp:${PORT}`]);
  adb(["logcat", "-c"], { check: false });
  setGeoFix();

  // First run: the setup screen, then the local origin.
  sh(`am start -W -n ${PKG}/.ui.MainActivity`);
  const setup = await waitFor("setup screen", () => {
    const nodes = uiDump("01-setup-ui");
    return nodes.some((n) => n.cls.endsWith("EditText")) ? nodes : null;
  });
  shot("01-setup");
  const bar = setup.find((n) => n.id.endsWith("action_bar_container"));
  const intro = setup.find((n) => n.text.startsWith("Enter the address"));
  record("setup: content starts below the action bar", Boolean(bar && intro && intro.top >= bar.bottom),
    `action bar bottom ${bar?.bottom}px, intro top ${intro?.top}px`);
  tap(center(setup.find((n) => n.cls.endsWith("EditText"))));
  await sleep(1000);
  sh("input keycombination KEYCODE_CTRL_LEFT KEYCODE_A");
  sh("input keyevent KEYCODE_DEL");
  sh(`input text '${ORIGIN}'`);
  sh("input keyevent KEYCODE_ENTER");

  // Gallery, light mode.
  await waitForPage(`${ORIGIN}/`);
  await waitFor("gallery cards", () => top("document.body.innerText.includes('Geo probe')"), { timeoutMs: 60000 });
  await sleep(3000);
  shot("02-gallery-light");
  checkInsets("gallery light", { night: false });

  // Probe: bridge.
  openViewer(`${ORIGIN}/${env.PROBE_ID}`);
  await waitForPage(`${ORIGIN}/${env.PROBE_ID}`);
  const native = await waitFor("native:ready in the probe", async () => {
    const text = await inFrame("document.getElementById('native').textContent");
    return text.startsWith("native: available") ? text : null;
  });
  shot("03-probe-ready");
  record("bridge: native:ready reports the app with capabilities",
    native.includes("available=true") && native.includes("geo.watch"), native);
  checkInsets("probe light", { night: false });

  // Probe: location with the Android permission not granted yet (fresh install).
  const granted = () => /ACCESS_FINE_LOCATION: granted=true/.test(sh(`dumpsys package ${PKG}`));
  record("geo: fresh install has no location permission", !granted(), "ACCESS_FINE_LOCATION not granted");
  tap(await frameElementCenter("#locate"));
  const consentText = await answerShellConsent("Allow once", "04-shell-consent");
  record("geo: the shell consent sheet appears", /Share your location/i.test(consentText || ""), JSON.stringify(consentText));
  let prompt = await runtimePrompt();
  shot("05-runtime-prompt");
  record("geo: Android runtime prompt appears after shell consent", true,
    prompt.find((n) => n.id.endsWith("permission_message"))?.text || "prompt shown");
  tap(center(prompt.find((n) => /permission_deny\w*_button$/.test(n.id))));
  const denied = await waitFor("probe error", async () => {
    const s = await probeStatus();
    return s.startsWith("ERROR") ? s : null;
  });
  shot("06-probe-denied");
  record("geo: denying the Android prompt reports denied to the artifact", denied === "ERROR denied", denied);

  await sleep(2500); // the shell rate-limits requests to one per 2 s
  tap(await frameElementCenter("#locate"));
  await answerShellConsent("Allow once");
  prompt = await runtimePrompt();
  tap(center(prompt.find((n) => n.id.endsWith("permission_allow_foreground_only_button"))));
  await sleep(1500);
  setGeoFix();
  const position = await waitFor("probe position", async () => {
    const s = await probeStatus();
    return s.startsWith("POSITION") || s.startsWith("ERROR") ? s : null;
  });
  shot("07-probe-position");
  record("geo: granting the prompt delivers the emulator fix to the artifact",
    position === `POSITION lat=${FIX.lat.toFixed(4)} lon=${FIX.lon.toFixed(4)}`, position);

  // pm revoke / pm grant: a revoke kills the process; a grant needs no runtime prompt.
  sh(`pm revoke ${PKG} android.permission.ACCESS_FINE_LOCATION`);
  sh(`pm revoke ${PKG} android.permission.ACCESS_COARSE_LOCATION`);
  sh(`pm grant ${PKG} android.permission.ACCESS_FINE_LOCATION`);
  sh(`pm grant ${PKG} android.permission.ACCESS_COARSE_LOCATION`);
  openViewer(`${ORIGIN}/${env.PROBE_ID}`);
  await waitForPage(`${ORIGIN}/${env.PROBE_ID}`);
  await waitFor("probe ready", async () => (await inFrame("document.getElementById('native').textContent")).startsWith("native: available"));
  tap(await frameElementCenter("#locate"));
  await answerShellConsent("Allow once");
  await sleep(1500);
  setGeoFix();
  const afterGrant = await waitFor("probe position after pm grant", async () => {
    const s = await probeStatus();
    return s.startsWith("POSITION") || s.startsWith("ERROR") ? s : null;
  });
  const promptAgain = uiDump().some((n) => /permission_deny\w*_button$/.test(n.id));
  record("geo: with pm grant the position arrives without an Android prompt",
    afterGrant.startsWith("POSITION") && !promptAgain, `${afterGrant}, runtime prompt shown: ${promptAgain}`);

  // Planner: published without data bindings, so the shell reports no concierge and the
  // planner hides the map and chat. The test sends the planner the data:ready message the shell
  // sends when the concierge is bound (from the shell's own window, so event.source matches),
  // which shows "Center on me" and the chat box. Location still goes through geo:request.
  openViewer(`${ORIGIN}/${env.PLANNER_ID}`);
  await waitForPage(`${ORIGIN}/${env.PLANNER_ID}`);
  // Day 1's text ("Drive the whole day") shows only on the trip's first day; after that the
  // planner opens on today. "Add a stop" is on every day.
  await waitFor("planner rendered", () => inFrame("document.body.innerText.includes('Add a stop')"));
  await sleep(3000);
  shot("08-planner-light");
  checkInsets("planner light", { night: false });
  await top(`document.querySelector('iframe').contentWindow.postMessage({type:'data:ready',enabled:true,bindings:{concierge:{operations:['tiles','chat']}}},'*'),true`);
  await waitFor("Center on me", () => inFrame("!document.getElementById('centerMe').hidden && !document.getElementById('chat').hidden"));
  await inFrame("window.__emu=[];window.addEventListener('message',e=>{if(e.data&&/^(geo|native):/.test(e.data.type))window.__emu.push(e.data)});true");
  tap(await frameElementCenter("#centerMe"));
  await answerShellConsent("Allow once");
  await sleep(1500);
  setGeoFix();
  const planner = await waitFor("planner geo reply", async () => {
    const [msgs, toast] = await inFrame("[window.__emu.filter(m=>m.type==='geo:position'||m.type==='geo:error'), document.getElementById('toast').textContent]");
    return msgs.length ? { msg: msgs[0], toast } : null;
  });
  await sleep(1000);
  shot("09-planner-center-on-me");
  const toastNow = await inFrame("document.getElementById('toast').textContent");
  record("planner: Center on me gets a position and no 'denied' toast",
    planner.msg.type === "geo:position" && Math.abs(planner.msg.lat - FIX.lat) < 1e-4 && !/denied|Could not/.test(toastNow),
    `${JSON.stringify(planner.msg)}; toast "${toastNow}"`);

  // IME: the chat input stays visible above the keyboard.
  tap(await frameElementCenter("#askInput"));
  const ime = await waitFor("keyboard", () => {
    const i = insets();
    return i.imeTop ? i : null;
  }, { timeoutMs: 30000 });
  await sleep(2500);
  shot("10-planner-ime");
  const wvIme = webView(uiDump("10-planner-ime-ui"));
  const [focused, inputBottom, frameHeight] = await inFrame("(()=>{const r=document.getElementById('askInput').getBoundingClientRect();return [document.activeElement&&document.activeElement.id,r.bottom,innerHeight]})()");
  record("ime: the WebView ends at the top of the keyboard", Math.abs(wvIme.bottom - ime.imeTop) <= 2,
    `WebView bottom ${wvIme.bottom}px, keyboard top ${ime.imeTop}px`);
  record("ime: the focused chat input is inside the visible viewport", focused === "askInput" && inputBottom <= frameHeight,
    `focused #${focused}, input bottom ${Math.round(inputBottom)} of ${frameHeight} CSS px`);
  sh("input keyevent KEYCODE_BACK");
  await sleep(1500);

  // Dark mode: the activity handles uiMode itself, so the strip must follow without a restart.
  sh("cmd uimode night yes");
  await sleep(8000);
  await inFrame("window.scrollTo(0,0),true");
  await sleep(2000);
  shot("11-planner-dark");
  checkInsets("planner dark", { night: true });
  await top(`location.href=${JSON.stringify(`${ORIGIN}/`)},true`);
  await waitForPage(`${ORIGIN}/`);
  await sleep(4000);
  shot("12-gallery-dark");
  checkInsets("gallery dark", { night: true });

  // Settings (reached with the menu key here; on a phone through the launcher shortcut).
  sh("input keyevent KEYCODE_MENU");
  const menu = await waitFor("options menu", () => uiDump().find((n) => n.text === "Settings"));
  tap(center(menu));
  const settings = await waitFor("settings screen", () => {
    const nodes = uiDump("13-settings-dark-ui");
    return nodes.some((n) => n.text === "Server") ? nodes : null;
  });
  shot("13-settings-dark");
  const sBar = settings.find((n) => n.id.endsWith("action_bar_container"));
  const sFirst = settings.find((n) => n.text === "Server");
  record("settings: content starts below the action bar", Boolean(sBar && sFirst && sFirst.top >= sBar.bottom),
    `action bar bottom ${sBar?.bottom}px, "Server" top ${sFirst?.top}px`);
  const shortcuts = sh(`dumpsys shortcut`, { check: false });
  const pkgSection = shortcuts.split(/Package: /).find((s) => s.startsWith(PKG)) || "";
  record("settings: launcher shortcut is published", /id=settings/.test(pkgSection), /id=settings/.test(pkgSection) ? "static shortcut 'settings'" : "missing");
  sh("input keyevent KEYCODE_BACK");
  sh("cmd uimode night no");

  writeFileSync(`${OUT}/logcat.txt`, adb(["logcat", "-d"], { check: false }));
  const consoleLines = readFileSync(`${OUT}/logcat.txt`, "utf8").split("\n").filter((l) => /chromium|\[probe\]/.test(l));
  writeFileSync(`${OUT}/webview-console.txt`, consoleLines.join("\n"));
}

try {
  await main();
} catch (error) {
  record("test run", false, error.message);
  try { shot("99-failure"); } catch { /* ignore */ }
}
const failed = results.filter((r) => !r.pass).length;
writeFileSync(`${OUT}/results.txt`, results.map((r) => `${r.pass ? "PASS" : "FAIL"} ${r.name}: ${r.detail}`).join("\n") + "\n");
console.log(`\n${results.length - failed} passed, ${failed} failed. Evidence in ${OUT}`);
process.exit(failed);
