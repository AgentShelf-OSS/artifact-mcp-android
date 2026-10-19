// Android Automotive OS (AAOS) test of the car design: the car media center renders the app's
// browse tree, covers, now-playing buttons and search from the media service. Run through
// test-car.sh. Every check prints PASS or FAIL; the exit code is the number of failures.
// Screenshots: $OUT/car/car-*.png (the main display only).
import { execFileSync } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";

const EMU = process.env.CAR_CONTAINER || "artifact-mcp-android-car";
const OUT = process.env.OUT || "/tmp/emulator-test";
const SHOTS = `${OUT}/car`;
const PKG = "ai.agentshelf.artifactmcp";
const SERVICE = `${PKG}/${PKG}.audio.PlaybackService`;
// The automotive debug variant: no launcher activity, so AAOS lists it as a media app.
const APK = "/src/app/build/outputs/apk/automotive/app-automotive.apk";
const TEST_APK = "/src/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk";
const env = Object.fromEntries(readFileSync(`${OUT}/artifacts.env`, "utf8").trim().split("\n").map((l) => l.split("=")));
const ORIGIN = env.ORIGIN;
const PORT = new URL(ORIGIN).port;
const SERVER_PORT = 3480; // without the identity proxy: signed out
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];

mkdirSync(SHOTS, { recursive: true });
for (const f of readdirSync(SHOTS)) rmSync(`${SHOTS}/${f}`);

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
function record(name, pass, detail = "") {
  results.push({ name, pass, detail });
  console.log(`${pass ? "PASS" : "FAIL"} ${name}${detail ? `: ${detail}` : ""}`);
}

// AAOS has a cluster display too; capture the main one.
const display = sh("dumpsys SurfaceFlinger --display-id").split("\n")[0].split(" ")[1];
function shot(name) {
  writeFileSync(`${SHOTS}/${name}.png`, adb(["exec-out", "screencap", "-p", "-d", display], { binary: true }));
  console.log(`.... screenshot ${SHOTS}/${name}.png`);
}

function dump() {
  sh("uiautomator dump /sdcard/car-ui.xml", { check: false });
  const xml = sh("cat /sdcard/car-ui.xml", { check: false });
  return [...xml.matchAll(/<node [^>]*>/g)].map(([n]) => {
    const attr = (k) => (n.match(new RegExp(` ${k}="([^"]*)"`)) || [])[1] || "";
    const [x1, y1, x2, y2] = attr("bounds").match(/\d+/g).map(Number);
    return { text: attr("text"), desc: attr("content-desc"), id: attr("resource-id").split("/").pop(), x: (x1 + x2) >> 1, y: (y1 + y2) >> 1 };
  });
}
async function waitFor(pred, timeoutMs = 20_000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const node = dump().find(pred);
    if (node) return node;
    await sleep(800);
  }
  return null;
}
const byText = (t) => (n) => n.text === t;
async function tap(pred, label, timeoutMs) {
  const node = await waitFor(pred, timeoutMs);
  if (!node) throw new Error(`not on screen: ${label}`);
  sh(`input tap ${node.x} ${node.y}`);
  await sleep(1500);
}
const tapText = (t, timeoutMs) => tap(byText(t), t, timeoutMs);
// A grid tile: its label sits under the cover and can be behind the playback bar, so tap the cover.
async function tapTile(t) {
  let node = await waitFor(byText(t), 8_000);
  for (let page = 0; !node && page < 4; page++) {
    const down = dump().find((n) => n.desc === "Scroll down");
    if (!down) break;
    sh(`input tap ${down.x} ${down.y}`);
    await sleep(1200);
    node = await waitFor(byText(t), 4_000);
  }
  // Too close to the bottom: the playback bar may cover the cover; scroll once more.
  if (node && node.y > 520) {
    const down = dump().find((n) => n.desc === "Scroll down");
    if (down) { sh(`input tap ${down.x} ${down.y}`); await sleep(1200); node = await waitFor(byText(t), 4_000); }
  }
  if (!node) throw new Error(`not on screen: ${t}`);
  sh(`input tap ${node.x} ${node.y - 120}`);
  await sleep(1500);
}
function session() {
  const all = sh("dumpsys media_session", { check: false });
  const at = all.indexOf(`package=${PKG}`);
  return at < 0 ? "" : all.slice(at, at + 2500);
}
async function waitSession(re, timeoutMs = 60_000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const s = session();
    if (re.test(s)) return s;
    await sleep(1000);
  }
  return session();
}

function instrument(method, extra = []) {
  return adb(["shell", "am", "instrument", "-w", "-e", "class", `${PKG}.CarSetup#${method}`, ...extra,
    `${PKG}.test/androidx.test.runner.AndroidJUnitRunner`], { check: false });
}
function setup(origin, keepRecents = false) {
  const out = instrument("configure", ["-e", "origin", origin, "-e", "keepRecents", keepRecents ? "1" : "0"]);
  if (!/OK \(1 test\)/.test(out)) throw new Error(`CarSetup failed: ${out}`);
}
async function openMedia() {
  sh("am force-stop com.android.car.media", { check: false });
  sh(`am start -a android.car.intent.action.MEDIA_TEMPLATE --es android.car.intent.extra.MEDIA_COMPONENT ${SERVICE}`);
  await waitFor(byText("Library"), 30_000);
  await sleep(1500);
}
async function back() {
  await tap((n) => n.id === "car_ui_toolbar_nav_icon_container", "back");
}

// ---- install --------------------------------------------------------------------------------

adb(["uninstall", PKG], { check: false });
adb(["uninstall", `${PKG}.test`], { check: false });
adb(["install", APK]);
adb(["install", TEST_APK]);
adb(["reverse", `tcp:${PORT}`, `tcp:${PORT}`]);
adb(["reverse", `tcp:${SERVER_PORT}`, `tcp:${SERVER_PORT}`]);
sh("input keyevent KEYCODE_WAKEUP", { check: false });

// ---- browse ---------------------------------------------------------------------------------

setup(ORIGIN);
await openMedia();
const tabs = dump().filter((n) => n.id === "car_ui_toolbar_tab_item_text").map((n) => n.text);
record("tabs: Continue and Library", JSON.stringify(tabs) === JSON.stringify(["Continue", "Library"]), JSON.stringify(tabs));
record("continue: empty state message", !!(await waitFor(byText("Pick something in Library to start listening"))));
shot("car-01-continue-empty");

await tapText("Library");
const orgs = await waitFor(byText("homelab"));
record("library: organization folders", !!orgs && !!dump().find(byText("bookclub")));
shot("car-02-library");

await tapText("homelab");
record("org grid: category groups", !!(await waitFor(byText("Infrastructure"))) && !!dump().find(byText("Family")));
shot("car-03-org-grid");

// ---- now playing ----------------------------------------------------------------------------

await tapTile("Rack dashboard tour");
let s = await waitSession(/state=PLAYING\(3\)/, 120_000);
record("now playing: Listen plays the picked artifact", /state=PLAYING\(3\)/.test(s), (s.match(/description=[^\n]*/) || [""])[0]);
s = await waitSession(/Speed /, 20_000);
record("now playing: Back 15 s and Speed buttons", /mName='Back 15 s/.test(s) && /mName='Speed 1×/.test(s), (s.match(/custom actions=\[[^\n]*/) || [""])[0].slice(0, 200));
await waitFor(byText("Now Playing"), 15_000);
// Past the artifact's own heading, so the title is a section and the artifact is below it.
sh("input keyevent KEYCODE_MEDIA_NEXT", { check: false });
s = await waitSession(/description=(?!Rack dashboard tour,)[^\n]*, Rack dashboard tour/, 30_000);
record("now playing: section title above the artifact title", /description=(?!Rack dashboard tour,)[^\n]*, Rack dashboard tour/.test(s),
  (s.match(/description=[^\n]*/) || [""])[0]);
await sleep(2500);
shot("car-04-now-playing");

const speed = (await waitFor((n) => n.desc.startsWith("Speed"), 5_000)) || null;
if (speed) {
  sh(`input tap ${speed.x} ${speed.y}`);
} else {
  sh("input tap 1166 620"); // the last control slot on the 1408x792 layout
}
s = await waitSession(/mName='Speed 1\.25×/, 20_000);
record("now playing: Speed cycles to 1.25x (media.command speed, media.session rate)", /mName='Speed 1\.25×/.test(s));
await sleep(2000);
shot("car-05-now-playing-speed");

const rewind = await waitFor((n) => n.desc.startsWith("Back 15"), 5_000);
if (rewind) sh(`input tap ${rewind.x} ${rewind.y}`); else sh("input tap 241 620");
await sleep(2000);
record("now playing: Back 15 s keeps Listen playing", /state=PLAYING\(3\)/.test(session()));

// A second artifact for Continue, then pause.
await back(); // collapse now playing
await back(); // homelab -> root
await tapText("Library");
await tapText("bookclub");
await tapTile("Frankenstein, read aloud");
s = await waitSession(/description=[^\n]*Frankenstein/, 120_000);
record("now playing: second artifact", /Frankenstein/.test(s));
await sleep(3000);
sh("input keyevent KEYCODE_MEDIA_PAUSE", { check: false });
await back();
await back();
await tapText("Continue");
const first = await waitFor(byText("Frankenstein, read aloud"));
record("continue: recently played, newest first", !!first && !!dump().find(byText("Rack dashboard tour")) && first.y <= dump().find(byText("Rack dashboard tour")).y + 1);
shot("car-06-continue");

// ---- search ---------------------------------------------------------------------------------

await tap((n) => n.desc === "Search", "search");
sh("input text family");
sh("input keyevent 66");
record("search: category match", !!(await waitFor(byText("Baby log: the first month"))) && !!dump().find(byText("Road trip to Luxor")));
shot("car-07-search");
sh("input keyevent KEYCODE_BACK", { check: false });

// Voice: "Play 1984 on Artifact MCP" arrives as playFromSearch.
// adb shell joins its arguments into one command line: quote values with spaces.
const voice = instrument("playFromSearch", ["-e", "query", "'Play 1984 on Artifact MCP'", "-e", "expect", "'1984: Part one'"]);
record("voice: playFromSearch plays the best match", /OK \(1 test\)/.test(voice), (voice.match(/(OK \(.*\)|FAILURES!!!)/) || ["?"])[0]);
sh("input keyevent KEYCODE_MEDIA_PAUSE", { check: false });

// ---- offline and signed out -----------------------------------------------------------------

adb(["reverse", "--remove", `tcp:${PORT}`], { check: false });
await openMedia();
await tapText("Library");
record("offline with a cache: notice first, folders kept",
  !!(await waitFor(byText("Can't reach your library right now."), 30_000)) && !!dump().find(byText("Showing your last copy."))
    && !!dump().find(byText("homelab")));
shot("car-08-offline-cached");
adb(["reverse", `tcp:${PORT}`, `tcp:${PORT}`]);

setup(`http://localhost:${SERVER_PORT}`);
await openMedia();
await tapText("Library");
record("signed out: one sign-in entry", !!(await waitFor(byText("Sign in on your phone to see your library"), 30_000)));
shot("car-09-signed-out");

setup(ORIGIN);
sh("am force-stop com.android.car.media", { check: false });
const failed = results.filter((r) => !r.pass).length;
writeFileSync(`${SHOTS}/results.txt`, results.map((r) => `${r.pass ? "PASS" : "FAIL"} ${r.name}: ${r.detail}`).join("\n") + "\n");
console.log(`\n${results.length - failed}/${results.length} passed; screenshots in ${SHOTS}`);
process.exit(failed);
