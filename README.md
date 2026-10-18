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

Version 0.1.0 is a pre-release. All JVM unit tests pass. **No emulator or device test was run
during development**: no emulator is available in the build environment. Treat the first install
as a field test.

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

The app is an Android Auto **media source** (`automotive_app_desc.xml` declares `media`). The car
screen shows the playback queue with play, pause and skip, and the current title. The browse root
has two folders: "Now queued" and "Geotrigger stops".

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
  `audio.queue`, `audio.geotrigger`, `audio.stop`, `notify.show`, `played.ack`. App to shell:
  `ready`, `geo.position`, `geo.error`, `audio.state`, `audio.played`, `error`.
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
notices, consent grants and revoke, speech request construction, and notification intent
targets. Run them with `build/build.sh testDebugUnitTest`.

Not tested: WebView behaviour, the foreground services, ExoPlayer and Media3 on a device, and
Android Auto. These need a device or an emulator.

## Limitations

- No emulator or device testing was possible during development.
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
