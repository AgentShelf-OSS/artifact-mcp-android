// Emulator test of the car library and the Listen media mirror. Run through test-listen.sh.
//
// 1. GET /api/library through the identity proxy (signed in) and directly (signed out).
// 2. The instrumentation test CarLibraryTest browses the media library service with the legacy
//    MediaBrowser API and plays the story artifact: first in the hidden reader, then in the
//    visible viewer with the screen off. On each `MARK` line this script screenshots the
//    expanded notification shade and saves `dumpsys media_session`.
// 3. Version 0.4.0 (ADR-0022): chapters queue, progress and Forward 30 s; playback resumption
//    (recent root, play with nothing loaded, and a media button with the app stopped); a pick
//    without a network fails at once; a missing artifact fails as not found; speech errors
//    (fake-tts mode 500) and buffering (mode slow). The progress checks need a shell with
//    `media.progress` (ARTIFACT_MCP_SRC of server.sh).
// Every assertion prints PASS or FAIL; the exit code is the number of failures.
import { execFileSync, spawn } from "node:child_process";
import { mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from "node:fs";

const EMU = process.env.EMU_CONTAINER || "artifact-mcp-android-emulator";
const OUT = process.env.OUT || "/tmp/emulator-test";
const PKG = "ai.agentshelf.artifactmcp";
const APK = "/src/app/build/outputs/apk/debug/app-debug.apk";
const TEST_APK = "/src/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk";
const env = Object.fromEntries(readFileSync(`${OUT}/artifacts.env`, "utf8").trim().split("\n").map((l) => l.split("=")));
const ORIGIN = env.ORIGIN;
const PORT = new URL(ORIGIN).port;
const SERVER_PORT = 3480; // the dev server without the identity proxy: signed out
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const results = [];
const TTS = "http://127.0.0.1:3490";
const TTS_TOKEN = readFileSync(`${OUT}/fake-tts.token`, "utf8").trim();
async function ttsMode(mode) {
  const res = await fetch(`${TTS}/mode`, { method: "POST", headers: { authorization: `Bearer ${TTS_TOKEN}` }, body: mode });
  console.log(`.... fake-tts mode ${(await res.text()).trim()}`);
}

mkdirSync(OUT, { recursive: true });
for (const f of readdirSync(OUT)) if (/^listen-/.test(f)) rmSync(`${OUT}/${f}`);

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
function shot(name) {
  writeFileSync(`${OUT}/${name}.png`, adb(["exec-out", "screencap", "-p"], { binary: true }));
  console.log(`.... screenshot ${OUT}/${name}.png`);
}

// ---- 1. library endpoint -------------------------------------------------------------------

const signedIn = await fetch(`http://127.0.0.1:${PORT}/api/library`);
const library = await signedIn.json();
writeFileSync(`${OUT}/listen-library.json`, JSON.stringify(library, null, 2));
const ids = (library.orgs || []).flatMap((o) => o.artifacts.map((a) => a.id));
record("library: signed-in viewer gets the published artifacts grouped by organization",
  signedIn.status === 200 && [env.PROBE_ID, env.PLANNER_ID, env.STORY_ID].every((id) => ids.includes(id)),
  `HTTP ${signedIn.status}, orgs ${JSON.stringify((library.orgs || []).map((o) => [o.org, o.artifacts.length]))}`);
const anonymous = await fetch(`http://127.0.0.1:${SERVER_PORT}/api/library`);
const anonymousBody = await anonymous.text();
record("library: without a session the server answers 403 not_signed_in", anonymous.status === 403 && anonymousBody.includes("not_signed_in"),
  `HTTP ${anonymous.status} ${anonymousBody.slice(0, 80)}`);

// ---- 2. instrumentation --------------------------------------------------------------------

adb(["uninstall", PKG], { check: false });
adb(["uninstall", `${PKG}.test`], { check: false });
adb(["install", APK]);
adb(["install", TEST_APK]);
adb(["reverse", `tcp:${PORT}`, `tcp:${PORT}`]);
adb(["reverse", `tcp:${SERVER_PORT}`, `tcp:${SERVER_PORT}`]);
sh("input keyevent KEYCODE_WAKEUP");
sh("wm dismiss-keyguard", { check: false });
// Notifications are visible without asking (POST_NOTIFICATIONS on Android 13+).
sh(`pm grant ${PKG} android.permission.POST_NOTIFICATIONS`, { check: false });

async function instrument(method) {
  adb(["logcat", "-c"], { check: false });
  const child = spawn("docker", ["exec", "-i", EMU, "adb", "shell", "am", "instrument", "-w", "-r",
    "-e", "class", `${PKG}.CarLibraryTest#${method}`,
    "-e", "origin", ORIGIN, "-e", "artifactId", env.STORY_ID, "-e", "signedOutOrigin", `http://localhost:${SERVER_PORT}`,
    ...(env.BOOK_ID ? ["-e", "bookId", env.BOOK_ID] : []),
    `${PKG}.test/androidx.test.runner.AndroidJUnitRunner`], { stdio: ["ignore", "pipe", "pipe"] });
  let output = "";
  child.stdout.on("data", (d) => { output += d; });
  child.stderr.on("data", (d) => { output += d; });
  const exited = new Promise((resolve) => child.on("exit", resolve));
  const seen = new Set();
  let done = false;
  exited.then(() => { done = true; });
  const poll = async () => {
    const lines = adb(["logcat", "-d", "-s", "CarLibraryTest:I"], { check: false }).split("\n");
    for (const line of lines) {
      const m = line.match(/CarLibraryTest: (MARK (\S+) .*|PASS .*|FAIL .*|INFO .*|children .*)$/);
      if (!m || seen.has(m[1])) continue;
      seen.add(m[1]);
      console.log(`.... ${m[1]}`);
      if (m[2] && !done) {
        await sleep(1500);
        writeFileSync(`${OUT}/listen-${m[2]}-media_session.txt`, sh("dumpsys media_session", { check: false }));
        sh("cmd statusbar expand-notifications", { check: false });
        await sleep(2500);
        shot(`listen-${m[2]}-notification`);
        sh("cmd statusbar collapse", { check: false });
      }
    }
  };
  while (!done) {
    await poll();
    await sleep(700);
  }
  await exited;
  await poll();
  writeFileSync(`${OUT}/listen-${method}-instrument.txt`, output);
  writeFileSync(`${OUT}/listen-${method}-logcat.txt`, adb(["logcat", "-d"], { check: false }));
  const ok = /OK \(1 test\)/.test(output);
  for (const line of seen) {
    if (!/^(PASS|FAIL) /.test(line)) continue;
    const [, verdict, name, state] = line.match(/^(PASS|FAIL) (.*?)\s*(state=.*)?$/);
    record(`${method}: ${name}`, verdict === "PASS", state || "-");
  }
  record(`${method}: instrumentation passed`, ok, (output.match(/(OK \(.*\)|FAILURES!!!.*|Tests run.*)/) || ["no result"])[0]);
}

await ttsMode("ok");
await instrument("signedOutLibraryShowsTheSignInItem");
await instrument("readerPlaysFromTheCarLibrary");
await instrument("visibleViewerPlaysFromTheCarLibraryAndKeepsRunningWithTheScreenOff");
sh("input keyevent KEYCODE_HOME", { check: false });

// ---- 3. version 0.4.0 ----------------------------------------------------------------------

await instrument("chaptersQueueProgressAndForward");
// Resumption plays the newest recent item: play the story again so it is first.
await instrument("readerPlaysFromTheCarLibrary");
await instrument("resumptionPlaysTheNewestRecent");

// A media button with the app stopped: the system sends it to the app's media button receiver,
// which starts the media service in the foreground; the session resumes the newest recent item.
sh(`am force-stop ${PKG}`, { check: false });
await sleep(2000);
adb(["logcat", "-c"], { check: false });
sh("cmd media_session dispatch play", { check: false });
let resumed = "";
for (let i = 0; i < 90 && !/state=PLAYING\(3\)/.test(resumed); i++) {
  await sleep(1000);
  const all = sh("dumpsys media_session", { check: false });
  const at = all.indexOf(`package=${PKG}`);
  resumed = at < 0 ? "" : all.slice(at, at + 2500);
}
const resumeLog = adb(["logcat", "-d"], { check: false });
writeFileSync(`${OUT}/listen-media-button-logcat.txt`, resumeLog);
writeFileSync(`${OUT}/listen-media-button-media_session.txt`, resumed);
record("resume: a media button with the app stopped resumes the newest recent item",
  /state=PLAYING\(3\)/.test(resumed) && /playback resumption \(forPlayback=true\): newest recent/.test(resumeLog),
  ((resumed.match(/description=[^\n]*/) || ["no session"])[0]) + (/ForegroundServiceDidNotStart|FATAL EXCEPTION/.test(resumeLog) ? " CRASH" : ""));
record("resume: no crash on the media button path", !/FATAL EXCEPTION[\s\S]{0,400}ai\.agentshelf/.test(resumeLog), "logcat checked");
sh("cmd media_session dispatch stop", { check: false });
await sleep(3000);

await instrument("offlinePickFailsFastAndPlayRetries");
await instrument("missingArtifactFailsFastAsNotFound");
await ttsMode("500");
await instrument("speechFailureShowsThePageError");
await ttsMode("slow");
await instrument("bufferingWhileSpeechIsSlow");
await ttsMode("ok");
await instrument("resumptionWithoutRecentsIsAClearError");
const viewerLog = readFileSync(`${OUT}/listen-visibleViewerPlaysFromTheCarLibraryAndKeepsRunningWithTheScreenOff-logcat.txt`, "utf8");
const pauses = viewerLog.split("\n").filter((l) => /ArtifactViewer: (viewer WebView|WebView timers)/.test(l)).map((l) => l.replace(/^.*ArtifactViewer: /, ""));
record("viewer: WebView paused only after Listen paused with the screen off, resumed for the car's play",
  pauses.includes("viewer WebView paused in the background") && pauses.includes("viewer WebView resumed") &&
  pauses.indexOf("viewer WebView paused in the background") < pauses.lastIndexOf("viewer WebView resumed"), JSON.stringify(pauses));

// Leave the device awake and unlocked for the next test run.
sh("input keyevent KEYCODE_WAKEUP", { check: false });
sh("wm dismiss-keyguard", { check: false });
sh("input keyevent KEYCODE_HOME", { check: false });

const failed = results.filter((r) => !r.pass).length;
writeFileSync(`${OUT}/listen-results.txt`, results.map((r) => `${r.pass ? "PASS" : "FAIL"} ${r.name}: ${r.detail}`).join("\n") + "\n");
console.log(`\n${results.length - failed}/${results.length} passed; evidence in ${OUT}/listen-*`);
process.exit(failed);
