# Crew Radio — project guide for Claude Code

Android push-to-talk / intercom app for a boat crew (package `fi.crewradio`, app name
"Crew Radio"; the boat or crew name is the Channel name setting, shown in the header). Works over
WLAN multicast, Bluetooth Classic RFCOMM and Wi-Fi Aware, with an app-level
flooding relay so multiple transports and multi-hop topologies work.

## Stack
- Android Gradle Plugin 9.4 with its built-in Kotlin (the Kotlin Android plugin is applied nowhere; the root
  build puts Kotlin 2.4 on the build classpath, which is how the built-in compiler is moved past AGP's
  default), Gradle 9.7, compileSdk 37, minSdk 29, targetSdk 34, JDK 17.
  Dependabot keeps AndroidX current; the toolchain itself (AGP, Kotlin, Gradle majors, compileSdk)
  is moved by hand, since a new AndroidX generation often needs a newer compileSdk or AGP (core 1.19
  needed AGP 9.1). AGP 10 will make the new Variant API mandatory; nothing here uses the old one.
- No third-party audio libs. 16 kHz mono, 20 ms frames (see `audio/AudioConfig`); on the wire
  as Opus through the platform `MediaCodec` (`audio/opus`, AOSP software codec since API 29)
  or raw PCM16. The AOSP Opus decoder always outputs 48 kHz, hence `audio/Decimator`.
- Build: `./gradlew assembleDebug` (wrapper is committed; needs an Android SDK with
  platform 37 via `ANDROID_HOME` or `local.properties`). Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- Versions come from git in `app/build.gradle.kts`: `versionCode` = commit count, `versionName` =
  `1.<count>`, `BuildConfig.GIT_SHA` on the Status screen. Never edit version numbers by hand.
- Release pipeline: `.github/workflows/build.yml`. The `build` job (every push/PR; read-only
  token, no secrets, no persisted credentials) runs the unit tests and a debug-signed
  `assembleRelease`, uploaded as an artifact. The `release` job (push to `main` only) builds again
  with the `CREWRADIO_*` secrets (keystore base64 + passwords) and publishes a GitHub Release
  `v<version>` with `CrewRadio-<version>.apk`. Without the keystore `assembleRelease` falls back to
  the debug key; with it, a shallow clone is refused (the commit count would be wrong). The
  keystore lives outside the repo (`*.keystore` ignored); the maintainer keeps it in `~/.crewradio/`.
- Unit tests (JUnit 4, pure Kotlin only, no Android runtime): `./gradlew testDebugUnitTest`.
  Real testing needs two or more physical phones; the emulator has no Bluetooth or Wi-Fi Aware,
  and MediaCodec behaviour can only be checked on a device.

## Architecture
```
MainActivity -(bind)-> PttService -> PttEngine -> Transport (LanTransport | BluetoothTransport | WifiAwareTransport)
                                         |-> AudioCapture (mic, AEC/NS) -> OpusEncoder? -> Packet.encode -> transports
                                         |-> OpusDecoder per sender -> Mixer (per-sender jitter queue + sum) -> AudioPlayback
```
- `PttService`: bound while the activity is visible, promoted to a started foreground
  service (types microphone|connectedDevice) with a partial wake lock and a low-latency
  Wi-Fi lock for the duration of a session. Owns the engine; the notification mirrors the
  status line and has a Disconnect action. The activity never disconnects on its own
  lifecycle, only on the button or the notification action.
- Settings: `SettingsActivity` is a stock `PreferenceFragmentCompat` over the default
  SharedPreferences; `Prefs` reads them with validated fallbacks and `SettingsRules` holds the
  pure, unit-tested validation. Duplex mode, relay, codec, name and hop limit are pushed into
  the engine on every bind and resume (the settings, not the engine, are the source of truth);
  group/port/channel key are constructor arguments of the transports, so they need a reconnect.
  Sample rate is deliberately not a setting: 16 kHz is baked into the Opus path and the decimator.
- `Packet`: 14-byte header `'P' 'T' | version=3 | codec | ttl | hops | senderId int32 | seq int32`,
  followed by `nonce(12) | ciphertext | tag(16)` (`ChannelCrypto.seal` prepends the nonce). The
  payload is AES-256-GCM under the channel key (`ChannelCrypto`, key by PBKDF2, random nonce per
  packet, the header with the ttl byte zeroed as AAD). `hops` is the sender's original budget,
  authenticated: a relay clamps ttl to min(ttl, hops, own limit). The engine charges the global
  rate budget, opens the packet, then charges the sender's budget and goes on to the seen-cache;
  anything that fails is `rejected`. `Prefs.channelKey` is generated at
  random on first use (no default) and doubles as the Aware passphrase; `docs/SECURITY.md` has the
  threat model.
  Codec 0 = PCM16LE frame, 1 = Opus packet, 2 = `Hello` roster heartbeat (no audio; older
  builds drop it as unknown, so it needed no version bump). Receivers decode per packet, so codecs can mix.
  `seq` is per sender and per kind: audio frames count in one sequence, hellos in another.
- `Transport` interface: `start(onPacket, onStatus)`, `send(packet, except)`, `stop()`,
  `relayWithin` (false for multicast). Stream transports frame packets with
  `StreamLink` (uint16 BE length prefix). A transport whose `start` throws is reported
  and dropped from the engine, never left in place silently swallowing frames.
- Reconnect lives inside each transport, never in the engine: BT re-dials its chosen peer
  from the reader's `finally`; Aware wraps each peer link in a `Dial` (a `NetworkCallback`
  that ends exactly once and schedules its successor while discovery still sees the peer)
  and re-attaches the whole session when Aware goes away; LAN's receive thread owns the
  socket and re-opens it when it breaks or Wi-Fi changes interface/address. All of them
  wait with `transport/Backoff` (1 s doubling to 15 s). The side that dialled restores.
- `LanTransport` sends every frame twice — to the multicast group and to the interface's
  IPv4 broadcast address — because plenty of APs filter multicast. One wildcard-bound
  socket receives both; the seen-cache drops the duplicate.
- Roster: while connected a `ptt-heartbeat` thread sends a hello every second; every hello or
  audio packet refreshes the sender's `Node` (name, transports, via, hops, talking). Silent for
  4 s = dropped. `onRoster` fires only when the rendered list changes; the service mirrors the
  head count into the notification title. Display name = Android device name, else `Build.MODEL`.
- The main screen shows only what matters while talking (head count, one status line, who is
  talking); `StatusActivity` (menu > Status) polls `engine.rosterNow`, `engine.stats()` and
  `service.statusLog` once a second for the detail. Keep diagnostics there, not on the main screen.
- Loss concealment lives in `audio/Mixer` + `audio/Conceal`, not the codec: MediaCodec cannot
  ask the AOSP Opus decoder for PLC (an empty buffer yields empty output). Audio frames and hellos
  number themselves independently (two seen-caches), so a gap in a sender's audio sequence is lost
  audio: `SeqTracker` (pure, wrap-aware, tested) admits each frame and reports the gap, the engine
  reserves that many slots in the mixer atomically with the admission, and the mixer fills them,
  and any queue that runs dry mid-talk, with the last frame fading over at most three slots.
- Audio routing: `audio/AudioRoute` owns `AudioManager.mode` for the session and picks the
  communication device: Bluetooth SCO headset, else wired/USB headset, else speakerphone
  (`setCommunicationDevice` on API 31+, `startBluetoothSco`/`isSpeakerphoneOn` below). It
  re-applies on every `AudioDeviceCallback` event and on a policy change; setting `audio_route`
  (auto | speaker | earpiece) is pushed into the engine with the other live settings. A headset that
  joins mid-session is listed among the outputs before it can be the communication device, so a
  refused switch falls back to the speaker and retries (700 ms, up to 6 times). `headset` and
  `bluetoothHeadset` describe the route in use, not the wanted one (`bluetoothPresent`, which drives
  the Telecom call); `onHeadsetChanged` fires when the route in use changes and `syncMonitor` then
  restarts the voice monitor when its mic (headset vs phone: different gate tuning and proximity
  arming) no longer matches.
- Bluetooth headsets: measured on a Jabra Evolve2 65 + S25. With SCO up and no call, a tap is
  an AVRCP PLAY that reaches our `MediaSession` (good); the headset sometimes sends AT+CHUP
  (hang-up) instead, and with no call to hang up Android drops the SCO link and does not
  re-open it, so `AudioRoute` watches the communication device (API 31+) / SCO state
  (below) and re-applies the route 700 ms later. While *any* Telecom call exists Android
  refuses AVRCP media keys system-wide ("Only the system can dispatch media key event to the
  global priority session"), so a self-managed call is NOT the default. It is the opt-in
  setting `headset_call` (`PttEngine.headsetAsCall`), for headsets whose button only hangs
  up: then `CallService` + `CallBridge` (`MANAGE_OWN_CALLS`) place a self-managed call while a
  Bluetooth headset is the route, Telecom routes the audio (`AudioRoute.passive`), the hang-up
  lands in `ChannelConnection.onDisconnect` as a talk toggle (`onAbort` really ends it), and a
  phone call holds the channel (mic off, mixer muted) until `onUnhold`.
- VOX with a headset (`headset_vox`, `PttEngine.headsetVox`): the Jabra sends nothing for its
  button while SCO is up, and its mute arrives as HFP mic gain 0/9, which the phone only stores;
  by level, muted and quiet are the same ~2 RMS (its own noise suppression) with ~20 RMS spikes
  once a second, speech 200-1900. So the engine runs an always-on `monitor` capture while a
  Bluetooth headset is the route and `audio/MicGate` (pure, tested) keys the mic after 2 frames
  above 80 RMS and un-keys after 75 frames below 40 (300/120 on the phone's own mic:
  `MicGate.tune`); a 5-frame pre-roll is sent on open. On the phone's mic the voice-call path
  levels close talk (400-1150 peaks) and a talker a metre away (1500-1900) to the same range,
  so on the phone the gate is armed only while the proximity sensor reads near (`atEar`); in
  `AudioRoute.Policy.AUTO` that same flag also moves the route to the earpiece (`AudioRoute.atEar`),
  loudspeaker when away, like a phone call. The phone-mic monitor runs on any route but
  SPEAKER when no headset is in use; a manually keyed mic sends even away from the ear.
  `startTalking` skips opening its own capture while the monitor runs and `sendFrame` is fed
  from it. Off by default, except that the `earpiece` route always runs it (the phone is at
  the ear, the screen out of reach). Cue tones (`cue_tones`) are off by default too.
- Hardware talk button: `PttService` holds a `MediaSession` while on channel; headset/media
  buttons arrive as media button events, the volume keys through a remote `VolumeProvider`
  (the only way to get them with the screen off). Headset presses carry press and release, so
  they need no debounce: a press toggles, a release after a 400 ms hold un-keys (push-to-talk).
  The volume provider reports adjustments only and autorepeats, so a quiet gap of the platform
  key-repeat timeout makes a hold one press. Every hardware key change plays `audio/Tones`
  through the mixer's cue queue, on top of whatever is sounding. Setting `hw_button`.
- `PttEngine.onPacket`: dedupe by (senderId, seq) seen-cache, relay to other
  transports/links if `relay` is on and ttl > 1 (ttl clamped to our own `maxHops`, then
  decremented in place), then decode and play unless half-duplex and transmitting. Opus
  decoders are per sender, created on demand, at most 8 at once (quietest evicted), released
  after 30 s of silence. Encoder failure falls back to PCM and reports it.
- Wire format has no legacy mode: every phone must run the same build (README says so).
- Wi-Fi Aware: every node publishes and subscribes; lower senderId initiates the
  data path (one link per pair). Publisher uses accept-any on API 31+.

## Signal K plugin (`sk-plugin/`)
- `signalk-crewradio`: the boat's Signal K server as a node on the channel. Pure Node 24+, no
  runtime dependencies, CommonJS, tests with `node --test` (`npm test`; `npm run coverage` enforces
  80 % lines and functions, 75 % branches, excluding `tools/`). CI: `.github/workflows/signalk-ci.yml`
  calls the Signal K project's reusable `plugin-ci.yml@master` with `working-directory: sk-plugin`
  (that canonical reference is what the App Store's Indicators tab looks for, so it is not pinned to
  a SHA). The App Store score (registry `test-harness/score.ts`) wants: installs with
  `--ignore-scripts`, constructor returns an object, `start()` completes with schema defaults and
  without `setPluginError` (so a missing channel key is a status, not an error), a schema, own tests
  that pass within 60 s, a clean `npm audit`, a `CHANGELOG.md` in the tarball and
  `signalk.screenshots` in package.json. The admin UI pictures (`plugin-config.png`,
  `data-browser.png`) are real captures of a scratch signalk-server with the plugin installed
  (recipe: `sk-plugin/docs/capture_admin_ui.md`; a headless Edge driven over the DevTools
  protocol, since its `--screenshot` flag is unreliable on Windows); the two illustrations and
  the icon are drawn by `docs/make_screenshots.py` (draw.io export, like the app's docs).
  Never pass off a drawing as a screenshot of the admin UI. `lib/packet.js`,
  `lib/crypto.js` and `lib/node.js` mirror `Packet.kt`, `ChannelCrypto.kt`, `Hello.kt` and the
  engine's roster rules byte for byte; `sk-plugin/test/vector.json` and the app's
  `CrossLanguageVectorTest` check the same packet, so change both when the wire changes.
- It is a speaker-only Wyoming satellite for `signalk-wyoming` (`lib/wyoming.js`: describe/info,
  ping/pong, pause-satellite, audio-start/chunk/stop, then `played`): signalk-wyoming does the
  text-to-speech (Piper, 22 050 Hz) and the announcement queue with its `urgent` priority; the
  plugin resamples to 16 kHz (`lib/resample.js`), prefixes a chime, waits for a gap in the talk
  and keys the channel with PCM frames paced at 20 ms. `lib/bridge.js` announces Signal K
  notifications through signalk-wyoming's in-process `say()` (PropertyValues
  `signalk-wyoming.api`), urgent for `emergency`, repeated until cleared. Roster goes to
  `communication.crewradio.*`.
- Phase 2 (app changes, same wire version): an urgent announcement should play through a
  half-duplex phone that is transmitting; acknowledgement from a phone.

## Licence
- EUPL-1.2 (`LICENSE`, SPDX `EUPL-1.2`), declared in the README and in the SBOM's metadata. Keep
  the licence when adding files; the AndroidX/Material dependencies are Apache-2.0, compatible.

## Security hygiene
- `SECURITY.md` (disclosure route: GitHub private vulnerability reporting, enabled) and
  `docs/SECURITY.md` (threat model, CRA Annex I mapping). Keep both current when the wire format
  or the transports change.
- CI: actions pinned to commit SHAs (Dependabot bumps them), CodeQL on push/PR/weekly, the
  `release` job attaches an SBOM (`gradlew sbom`, CycloneDX 1.5 from the release runtime
  classpath, no plugin) plus a SHA-256 and a build-provenance attestation to every Release.
- Engine: `Packet.MAX_SIZE` drops oversized packets unread; `RateLimiter` (pure, tested) charges a
  global bucket (400/s, burst 800) and then a per-sender one (75/s, burst 150, at most 128 senders,
  idle ones swept) before the packet is opened, relayed, decoded or mixed; the AEAD check comes right
  after. All of it counts as `rejected` on the Status screen.
- Release job: fails closed without the keystore secrets and verifies the signer certificate against
  the `CREWRADIO_CERT_SHA256` repository variable before attesting or publishing.

## Documentation
- `README.md` is written for the crew (install, quick start, talk keys, headsets, settings);
  `docs/ARCHITECTURE.md` for developers. The diagrams and the screen mock-ups are generated:
  `python docs/diagrams/make_diagrams.py` writes the `.drawio` files, draw.io desktop exports the
  PNGs (command at the top of the script). No real screenshots in the repo: they carry device names.

## Conventions
- Keep transports symmetrical (every phone is both server and client) — no
  designated master, the app must survive any phone dropping out.
- Anything blocking (sockets, AudioTrack.write) lives on its own named thread
  (`ptt-*`); never on the main thread.
- Transport threads go through `transport/transportThread`: an uncaught throwable on a
  plain thread kills the whole app, and the Bluetooth/Aware stacks throw `SecurityException`
  for a missing runtime permission, not just `IOException`. Catch broadly, report, stay up.
- Careful with `apply`/`with` on a socket: `port` inside such a block is
  `DatagramSocket.getPort()` (-1 unconnected), not the transport's own `port`.
- `onStatus` strings are shown verbatim in the UI status line; keep them short.
- Status/errors are reported, never swallowed silently, except transient send failures.

## Known gaps / roadmap
Nothing planned. Field testing is continuous on the Nokia 7.2, S20 FE and S25 (with a Jabra
Evolve2 65 on the S25): voice-keying thresholds in wind and engine noise, real Aware/Bluetooth
ranges, multi-hop under way, a full day's battery. Transports are WLAN, Bluetooth Classic and
Wi-Fi Aware, and that list is final.
