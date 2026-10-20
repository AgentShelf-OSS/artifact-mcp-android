# Artifact MCP for Android

An Android app for [Artifact MCP](https://github.com/AgentShelf-OSS/artifact-mcp). It shows the
normal viewer of an Artifact MCP server in a WebView. It also gives artifacts native capabilities
that a web page cannot have on Android: location with the screen off, location-triggered
narration, a media queue that Android Auto can control, and local notifications.

The design is recorded in
[ADR-0018: Native Android app and car surfaces](https://github.com/AgentShelf-OSS/artifact-mcp/blob/master/docs/adr/0018-native-android-app-and-car-surfaces.md)
(in `docs/adr/` of the server repository). This repository is **Phase 1**: viewer, login and the
native bridges. Phase 2 (car screens built from Car App Library templates) is not built yet.

## Status

Version 0.4.0 is a pre-release (release notes: [docs/releases/0.4.0.md](docs/releases/0.4.0.md)). All JVM unit tests pass. The viewer (insets, geolocation, the
bridge handshake, the keyboard) is tested on a headless emulator; see "Emulator test". The
foreground services, playback and Android Auto are not tested on a device yet. Treat the first
install as a field test.

## Install (sideload)

1. Download `artifact-mcp-android-<version>.apk` from the
   [releases page](https://github.com/AgentShelf-OSS/artifact-mcp-android/releases).
2. Check the SHA-256 against the value in the release notes:
   `sha256sum artifact-mcp-android-0.1.0.apk`.
3. On the phone, open the APK. Allow "Install unknown apps" for the browser or file manager when
   Android asks.
4. Optional: verify the signing certificate. Its SHA-256 fingerprint is
   `56:50:3B:BD:10:16:83:CF:4D:86:AE:F2:55:A6:A8:E3:8C:37:7F:8F:22:16:2F:64:AC:49:88:44:AB:13:0F:7C`
   (`apksigner verify --print-certs artifact-mcp-android-0.1.0.apk`). Updates must be signed with the
   same certificate.

Requirements: Android 10 (API 29) or later. The app targets API 37.

## First run

The app asks for the server origin, for example `https://artifact.example.com`. Only `https://`
origins without a path are accepted. Nothing is hard-coded. Change the server later in
**Settings > Change server**. A change of server signs you out and clears local data.

The viewer has no app bar. To open **Settings**, long-press the app icon and choose **Settings**
(a launcher shortcut).

## Signing in

The app signs in with the deployment's own login page in the WebView, and the session cookie
stays in the WebView's cookie jar.

For deployments behind **Cloudflare Access**:

- **Email one-time PIN works.** Enter your email, choose "Send login code", then type the code.
  The Access policy must allow your email with the One-time PIN login method.
- **Google sign-in does not work in an embedded WebView.** Google rejects it with
  `disallowed_useragent`. The app does not spoof the browser user agent. It stops the Google
  page and tells you to use the email code.
- Other identity providers that allow embedded browsers (for example GitHub) continue inside the
  WebView. While a sign-in is in progress, pages on other hosts stay in the app until you return to
  the server origin.

A sign-in in a Custom Tab is not used. The Custom Tab has a different cookie jar, so the session
would have to be handed back to the app as a bearer token in a link. Any app could intercept that
link. See "Limitations" for the options.

## Permissions

| Permission | Why | When it is asked |
| --- | --- | --- |
| `INTERNET` | Load the viewer and speech clips. | Install time |
| `ACCESS_NETWORK_STATE` | A car pick fails at once with "No connection" when there is no network, instead of waiting for the page. | Install time |
| `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | `geo.watch` and `audio.geotrigger`. "Approximate" is accepted; geotriggers then fire only if the accuracy is within the trigger radius. | First location request from an artifact, after the in-app consent dialog |
| `POST_NOTIFICATIONS` (Android 13+) | The persistent location notification, artifact notifications, the media notification. | With the first location or notification request |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_LOCATION` | Location keeps working with the screen off, in a foreground service with a visible notification and a Stop button. | Install time |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | The Media3 playback service for the queue and Android Auto. | Install time |
| `WAKE_LOCK` | Keeps the CPU awake while a clip plays with the screen off. | Install time |

**No background location permission.** The location service starts only while the viewer is
visible, in response to the artifact's request. A foreground service started like this keeps
"while in use" location access with the screen off. The service is not restarted by the system
after the process dies (`START_NOT_STICKY`), because a background restart could not hold
location. After a reboot or process death, open the artifact again.

Each artifact also needs the app's own consent, per capability (location, notifications). The
viewer shell asks first (ADR-0016). The app then shows a native dialog the first time. You can
revoke consent per artifact in **Settings > Artifact permissions**. A revoke stops the artifact's
watches and geotriggers, and the shell gets `geo.error` with `reason: "denied"` at the next
`hello`.

## Android Auto

The app is an Android Auto **media source** (`automotive_app_desc.xml` declares `media`). On
Android Automotive OS the car media center lists a media app only when it has no launcher
activity, so the phone APK is not listed there; the debug-only `automotive` build type
(`build/build.sh assembleAutomotive`, `src/automotive/AndroidManifest.xml`) drops the launcher
entry and declares `com.android.automotive` for the emulator test (see "Car test"). A released
AAOS app would need its own module and Play track. Android Auto and AAOS draw every screen themselves; the app supplies
the browse tree, layout hints, text, artwork and now-playing buttons.

**Tabs.** The browse root has up to three tabs, each with a monochrome icon:

| Tab | Shows | Layout |
| --- | --- | --- |
| Continue | Up to 20 artifacts you listened to, newest first, as partly played. Empty: "Pick something in Library to start listening". | Grid |
| Library | One folder per organization (organization-colored cover) -> its artifacts, newest first, under category headings ("Other" last). | List, then grid |
| Road stories | "Now queued" and "Geotrigger stops", only while there are any. | List |

The library comes from `GET <origin>/api/library` with the WebView's cookies (4 s timeout) and is
cached on disk. Offline with a cached copy, Library starts with "Can't reach your library right
now. Showing your last copy."; with no copy it shows "Can't reach your library right now. Check
your connection." Without a session (403) it shows "Sign in on your phone to see your library".
These messages are empty folders, not inert entries: the AAOS media center hides entries that are
neither browsable nor playable. Titles longer than 40 characters are shortened at a word break.

**Covers.** Each artifact gets a generated 512 x 512 cover: the title in Atkinson Hyperlegible Bold
on navy `#142235` (largest size that fits 4 lines, ellipsis when it does not), a band along the
bottom in the organization color with the category in white or navy (whichever has more contrast,
at least 4.5:1 for the fallback palette), and the gold "A" mark. The organization color is
`orgs[].color` from `/api/library`; without it, one of 8 fixed hues chosen by a hash of the name.
Organization folders get the color as the field with the name on it. Message entries (sign in,
offline, empty) show the gold mark on navy. Covers are served by
`CoverProvider` as `content://<package>.covers/...` URIs. The URI holds the whole cover (kind,
color, text), so the provider draws it without reading app data; that is why it is exported
without a permission (the car loads browse artwork in its own process). PNGs are cached in
`cache/covers` (at most 400) and removed by "Sign out and clear data". The font is under the SIL
Open Font License ([licenses/AtkinsonHyperlegible-OFL.txt](licenses/AtkinsonHyperlegible-OFL.txt)).

**Now playing.** Artwork is the artifact's cover, the title is the current chapter or section,
then the artifact and the voice. Next to previous, play/pause and next there are up to three
buttons: "Back 15 s" (`media.command` `rewind`), "Forward 30 s" (`forward`, ADR-0022) and "Speed"
with the current rate as its icon (sends `speed`; the page cycles and reports
`media.session.rate`). They show when the page offers them: `rewind`/`speed` in `actions` or any
`rate` (a shell that sends `rate` handles both), `forward` in `actions`. With Forward shown, Speed
moves to the overflow menu; on the Android Automotive media center only the first button fits
next to previous, so Forward and Speed are both behind the overflow button there. For the native
queue the app seeks back 15 s or forward 30 s and cycles the playback speed itself.

**Progress and chapters (ADR-0022, `media.progress`).** A shell that knows the app has
`media.progress` reports a position and duration (seconds, at 1x) for the current chapter or
reading scope, a `buffering` state, a failure reason, and the chapter list (`media.chapters`).
The car then shows a progress bar (the app interpolates the position between updates as position
+ elapsed time x rate; it cannot seek), a buffering spinner while Listen loads or reconnects, and
a queue with one item per chapter: picking an item, or next/previous, sends `media.command`
`chapter` with the index (previous more than 3 s into a chapter restarts it). Without chapters,
next and previous stay the page's own `next`/`previous`. Continue shows how much is played:
chapter index (plus the share of the current chapter) over the chapter count, or position over
duration.

**Errors.** Each failure has its own error code and message: no network ("No connection", the
pick fails at once and opens no page), the artifact is gone (HTTP 404/410), a server error, no
Access session ("Open the app on your phone to sign in", with an "Open on phone" resolution
action), the page's own reason (`media.session.error`, for example "Speech is unavailable"), and
"Listen did not start for this artifact" when the page does not start within 45 s. Play after a
failure tries the pick again. While the device is offline, Continue starts with "No connection.
Listening needs the internet." and every Continue and Library artifact says "Needs a
connection". A search with no match, no library or no session gives a clear message instead of
failing silently.

**Resume.** A "play" with nothing loaded (a headset or Bluetooth button, the car's play button,
the system's resume card) resumes the newest Continue item: it opens
`<origin>/<id>?listen=autoplay` like a pick and the page resumes its saved place. With the app
stopped, the media button receiver (`ResumeButtonReceiver`) starts the media service only when
resuming can play (an origin is set, Continue has an item, the library is not signed out, a
network is up), so a foreground service is never started without playback. Otherwise, and for
the "recent" browse root that Android Auto asks for (`EXTRA_RECENT`, answered with the newest
Continue item), the car gets a clear error ("Nothing to resume yet. Pick something in Library.",
"Set up Artifact MCP on your phone first", sign in, or "No connection").

**Search and voice.** Search matches artifact titles, categories and organization names in the
cached library (case and accents ignored, every word must match, title matches first). "Play
<title> on Artifact MCP" arrives as a search request and plays the best match; "Play Artifact MCP"
resumes the most recent Continue item.

**Listen in the car.** Picking an artifact opens `<origin>/<id>?listen=autoplay`: in the viewer if
it is on screen, otherwise in one hidden reader WebView owned by the media service. The shell
starts Listen and reports its state with `media.session`; the media session (car, notification,
Bluetooth) then shows the section title and voice, and play, pause, next, previous and stop are
sent back as `media.command`. Listen started from the phone appears the same way. The native
queue and Listen share the session: the last one that starts playing wins and the other pauses.
While Listen plays, the viewer WebView is not paused, so narration continues with the screen off.
Otherwise the viewer WebView is paused 15 s after the app leaves the screen (not while an artifact
uses location). The reader is removed 30 s after Listen stops or 15 minutes after it pauses.

A sideloaded app appears in Android Auto only in developer mode:

1. On the phone, open **Settings > Apps > Android Auto > Additional settings in the app** (or open
   Android Auto directly).
2. Scroll to **Version** and tap it about 10 times until developer mode is enabled.
3. Open the menu (three dots) > **Developer settings** and enable **Unknown sources**.
4. Restart Android Auto (disconnect and reconnect the car, or force-stop Android Auto).
5. In the car's app launcher, choose **Customize launcher** if Artifact MCP is not shown.

Audio uses `USAGE_MEDIA` with `CONTENT_TYPE_SPEECH` and handles audio focus. Narration pauses
during navigation prompts, then resumes.

## Bridge protocol

The viewer shell detects the app through the `ArtifactNative` host object. The protocol is
"Bridge protocol (version 1)" in ADR-0018. Summary:

- The object is registered with `WebViewCompat.addWebMessageListener` and an allowed-origin rule of
  exactly the configured origin. Only top-level pages of that origin get it. The sandboxed artifact
  iframe has an opaque origin and never gets it. `addJavascriptInterface` is not used.
- Messages are JSON strings with `"v": 1`. Shell to app: `hello`, `geo.watch`, `geo.unwatch`,
  `audio.queue`, `audio.geotrigger`, `audio.stop`, `notify.show`, `played.ack`, `media.session`,
  `media.chapters`. App to shell: `ready`, `geo.position`, `geo.error`, `audio.state`,
  `audio.played`, `media.command`, `error`. `ready.capabilities` is `geo.watch`, `audio.queue`,
  `audio.geotrigger`, `notify`, `media.mirror`, `library`, `media.rate`, `media.progress`.
- `media.session {artifactId, state: playing|paused|none, title, artist, album, actions, rate?}`:
  text up to 200 characters (control characters become spaces), `actions` a subset of `play`,
  `pause`, `next`, `previous`, `stop`, `rewind`, `speed`. `rate` (ADR-0021) is optional, a string
  such as `"1"`, `"1.25"`, `"0.8"`; the shell sends it only to apps with the `media.rate`
  capability. Unknown fields and actions are still rejected. `media.command {artifactId, action}`
  goes only to the page that sent the session.
- `media.progress` (ADR-0022): `media.session` may also have `state: "buffering"`, `position` and
  `duration` (numbers, seconds at 1x, 0 to 86400, at most one decimal), `chapterIndex` (integer
  >= 0), `error` (1 to 120 characters, only with `paused` or `none`), and the actions `forward` and
  `chapter` (at most 12 actions). With `none`, position, duration and chapter index are dropped.
  `media.chapters {artifactId, chapters}`: 0 to 500 titles of 1 to 200 characters. The app sends
  `media.command` `forward`, and `chapter` with an integer `index` (0 to 499).
- The hidden Listen reader may only load `<origin>/<id>` and `<origin>/<id>?listen=autoplay`; all
  other navigation is blocked (a redirect to the login page shows the sign-in message in the car).
  A network or HTTP error of the page itself ends the pick at once with its own error. Its bridge
  accepts only `hello`, `media.session` and `media.chapters`. It is the only WebView where media may play
  without a tap, except the visible viewer during one `?listen=autoplay` load from the car.
- Validation is strict: exact key sets, JSON types, `artifactId` matches `^[A-Za-z0-9_-]{1,64}$`,
  ids up to 64 characters, titles up to 120, text up to 1500, audio URLs same-origin only,
  `intervalMs` at least 15000, `radius_m` 500 to 10000, at most 50 geotrigger items. An invalid
  message gets `{"v":1,"type":"error","artifactId","requestId","reason":"invalid"}`.
- A page can drive capabilities only for the artifact it shows (`<origin>/<artifactId>`).
  Requests from other pages get `reason: "denied"`.
- Speech: for a `text` item the app POSTs `{"text","voice"}` to `<origin>/<artifactId>/speech`
  with the WebView's cookies, `Origin: <origin>` and `X-Artifact-Mutation: 1` (the server's
  same-origin gate for cookie-authenticated POSTs). Without `voice`, the app uses the first voice
  from `/<artifactId>/speech/voices`. Clips are cached per item. Geotrigger clips are synthesized
  when the set arrives, while the viewer is open and the session is fresh.
- Geotriggers fire once per item id: the first fix within `radius_m`, with accuracy no larger than
  the radius. Played ids are stored per artifact and survive process death. `audio.played` events
  are re-sent on each `hello` from that artifact until `played.ack`.

## Build

Builds run in a pinned container (`build/Dockerfile`: Temurin 21 by digest, Android command-line
tools by SHA-256, platform `android-37.0`, build-tools 37.0.0). On a Docker host:

```sh
build/build.sh                      # ./gradlew lint testDebugUnitTest assembleDebug
build/build.sh assembleDebug        # any Gradle tasks
```

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Without Docker, use JDK 21 and an
Android SDK with the same packages, then run `./gradlew lint testDebugUnitTest assembleDebug`.

CI (GitHub Actions) runs lint, JVM unit tests and a debug build. It has no signing secrets.

### Signed release

Run `build/release.sh` on the operator host (pve1). It reads `/ARTIFACT_ANDROID_KEYSTORE_B64` and
`/ARTIFACT_ANDROID_KEYSTORE_PASSWORD` from the homelab secrets manager with `homelab-secrets exec`.
It streams them over SSH stdin into the build container on the Docker host. The keystore is
decoded into a container tmpfs, the build runs on a tmpfs copy of the sources, and only
`dist/artifact-mcp-android-<version>.apk` and its `.sha256` file are written back. The key alias
is `artifact-mcp-android`. The keystore is PKCS12, RSA 4096, valid until 2054.

## Tests

JVM unit tests (JUnit 4, Truth, Robolectric) cover protocol parsing and validation, origin and
navigation rules, geotrigger distance and first-entry logic, persistence of played state and
notices, consent grants and revoke, speech request construction, notification intent targets,
and the car: cover layout (fit, ellipsis, contrast choice, palette), cover rendering and URIs, the
browse tree and its extras, offline and signed-out entries, search, recent items and their played
share, the now-playing button mapping, the `media.progress` messages (accept and reject cases),
the mirror player's chapter queue, buffering state, interpolated position and error codes, the
resume and search error mapping, and reader load failures. Run them with `build/build.sh testDebugUnitTest`.

Not tested by the JVM tests: WebView behaviour, the foreground services, ExoPlayer and Media3 on a
device, and Android Auto. The emulator test below covers the viewer part.

### Emulator test

`build/emulator/` runs the debug app on a headless Android emulator in a container and checks the
viewer against a local Artifact MCP server. It needs a Docker host with `/dev/kvm`, Node 22 or later
on that host, and a checkout of the server repository.

```sh
build/build.sh                                   # debug APK
build/emulator/run.sh up                         # emulator; downloads the SDK parts once
ARTIFACT_MCP_SRC=../artifact-mcp build/emulator/server.sh up
build/emulator/test-viewer.sh                    # evidence in /tmp/emulator-test/
build/emulator/server.sh down && build/emulator/run.sh down
```

- **Emulator.** `run.sh` builds `build/emulator/Dockerfile` on top of the build image and keeps the
  emulator, platform-tools and the `android-37.0` `google_apis` x86_64 system image in the named
  volume `artifact-mcp-android-emulator-sdk`. The AVD is Pixel 7 sized (1080 x 2400, 420 dpi). It
  runs with `-no-window -no-audio -gpu swiftshader_indirect -no-snapshot -no-boot-anim`, a
  hole-punch cutout overlay and three-button navigation, so the insets are realistic. The container
  uses `--network host`; adb and the emulator console listen on loopback only.
- **Server and sign-in.** Cloudflare Access email login cannot run in the emulator. `server.sh`
  starts the server from `ARTIFACT_MCP_SRC` with `TRUST_ACCESS_HEADERS=1` on `127.0.0.1:3480`, and
  `identity-proxy.mjs` on `127.0.0.1:8480` sets `Cf-Access-Authenticated-User-Email:
  tester@emulator.test` on every request. It publishes `geo-probe.html` (prints the shell's
  `geo:*` and `native:*` replies) and the road-trip planner (`PLANNER_HTML`, without data bindings).
  The data directory is a tmpfs; the publish key is random per run.
- **Origin.** The emulator reaches the proxy as `http://localhost:8480` through `adb reverse`.
  Only a **debug** build accepts an `http://localhost:<port>` or `http://127.0.0.1:<port>` origin
  (`OriginPolicy.normalize(..., allowLoopbackHttp = BuildConfig.DEBUG)` and a cleartext exception
  in `src/debug/res/xml/network_security_config.xml`). Release builds accept https only.
- **Test.** `test-viewer.sh` installs the APK fresh, enters the origin on the setup screen and
  drives the app with `adb input`, UI Automator dumps and the WebView DevTools protocol
  (`cdp.mjs`; debug builds enable WebView debugging). It checks the WebView bounds against the
  status bar, cutout, navigation bar and keyboard frames from `dumpsys window`, the bar colours
  and icon contrast in light and dark mode, the shell consent sheet, the Android location prompt
  (deny, then allow), `pm grant`, the probe position against `adb emu geo fix`, the planner's
  "Center on me" and chat input, and `native:ready`. Without data bindings the planner hides its
  map and chat, so the test posts the shell's `data:ready` message for a bound concierge to the
  planner first. Each assertion prints PASS or FAIL; screenshots, UI dumps, logcat and
  `results.txt` go to `/tmp/emulator-test/`.
- **Listen and the car library.** `server.sh` also runs `fake-tts.mjs` (a stand-in speech worker
  that returns a quiet tone) and publishes `story.html`. Build with
  `build/build.sh assembleDebug assembleDebugAndroidTest`, then run `build/emulator/test-listen.sh`.
  It checks `/api/library` signed in and signed out, then runs the instrumentation test
  `CarLibraryTest`, which browses the media service with the legacy `MediaBrowser` API (as Android
  Auto does) and plays the story: in the hidden reader (next, pause, play, stop) and in the visible
  viewer with the screen off. It screenshots the media notification (`listen-*-notification.png`)
  and saves `dumpsys media_session`. Version 0.4.0 adds: the chapter queue, progress and Forward
  30 s on `book.html` (a small e-reader page with four chapters); playback resumption from the
  recent root, from play with nothing loaded, and from a media button with the app stopped
  (`cmd media_session dispatch play`); a pick with Wi-Fi and data off (fails in under 5 s, play
  retries once back online); a missing artifact (not found); and the fake speech worker's failure
  modes (`POST /mode` with `500`, `offline` or `slow`): the page's "Speech is unavailable" and the
  buffering state. The progress checks need a server whose shell has `media.progress`; start a
  fresh server before each run (the shell resumes the saved place).

### Car test (Android Automotive OS)

```sh
build/build.sh assembleAutomotive assembleDebugAndroidTest   # one run: the same debug key
EMU_PROFILE=car build/emulator/run.sh up         # AAOS 15 (android-35-ext15 android-automotive x86_64)
CAR_SEED=1 ARTIFACT_MCP_SRC=../artifact-mcp build/emulator/server.sh up
build/emulator/test-car.sh                       # screenshots in /tmp/emulator-test/car/
```

`EMU_PROFILE=car` boots a 1408 x 792 AAOS emulator in its own container
(`artifact-mcp-android-car`, adb serial `emulator-5556`). `CAR_SEED=1` makes the test identity an
administrator and publishes `car-seed.mjs`: organizations "homelab" (gallery color) and "bookclub"
(no color) with categorized stories. `test-car.sh` installs the `automotive` APK, sets the origin
(`CarSetup`), opens the car media center on the app and drives it with UI Automator: tabs,
Library, an organization grid, now playing with its buttons (speed round trip through the
overflow menu, Forward 30 s), the chapter queue of "Rack dashboard tour" (an e-reader page:
progress bar, queue, picking a chapter), Continue, search, a voice-style `playFromSearch`, a pick
with no network (error state "No connection" and the marked Continue rows), offline with a
cached library and signed out. The AAOS media center gives its control buttons no labels in the
UI dump, so the overflow and queue rows are tapped by position on the 1408 x 792 layout.

## Limitations

- No test yet in a real car or the Desktop Head Unit. The emulators cover the viewer, the media
  library through the legacy browser API, the notification, screen-off Listen playback, and the
  car design in the Android Automotive media center (not Android Auto's own renderer). Android
  Auto's resume card, its error screen with the "Open on phone" button, its queue view and where
  it places the third custom button are not tested.
- Legacy controllers (Android Auto) get distinct error messages, but the legacy error codes are
  coarse: only "sign in" has its own code (`AUTHENTICATION_EXPIRED`); network, not found, server
  and page errors arrive as `UNKNOWN_ERROR` with their message. Media3 controllers get the full
  codes.
- Continue shows "partly played" without a percentage for pages whose shell has no
  `media.progress`.
- Phase 2 car surfaces (Car App Library templates) are not built.
- Google sign-in through Cloudflare Access cannot work in the WebView. Use the email one-time PIN.
  Alternatives for later: an Access service token or a dedicated Access application for the app.
- When the Access session expires, speech synthesis returns to the login page and the clip fails
  (`audio.state` `error`). Open the viewer to sign in again. Geotrigger clips are prefetched to
  reduce this.
- A geotrigger can fire with the screen off. Android 12+ can refuse to promote the media service
  to the foreground from the background. Playback continues while the location service keeps the
  process alive; the media notification can be missing in that case.
- After process death or a reboot, watches and geotriggers stop until the artifact is opened
  again.

## License

Apache License 2.0. See [LICENSE](LICENSE). Contributions need a DCO sign-off (`git commit -s`).
