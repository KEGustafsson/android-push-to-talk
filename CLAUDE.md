# Crew Radio — project guide for Claude Code

Android push-to-talk / intercom app for a boat crew (package `fi.crewradio`, app name
"Crew Radio"; the boat or crew name is the Channel name setting, shown in the header). Works over
WLAN multicast, Bluetooth Classic RFCOMM and Wi-Fi Aware, with an app-level
flooding relay so multiple transports and multi-hop topologies work.

## Stack
- Kotlin, Android Gradle Plugin 8.5, Kotlin 2.0, minSdk 29, targetSdk 34, JDK 17.
- No third-party audio libs. 16 kHz mono, 20 ms frames (see `audio/AudioConfig`); on the wire
  as Opus through the platform `MediaCodec` (`audio/opus`, AOSP software codec since API 29)
  or raw PCM16. The AOSP Opus decoder always outputs 48 kHz, hence `audio/Decimator`.
- Build: `./gradlew assembleDebug` (wrapper is committed; needs an Android SDK with
  platform 34 via `ANDROID_HOME` or `local.properties`). Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
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
  group/port/passphrase are constructor arguments of the transports, so they need a reconnect.
  Sample rate is deliberately not a setting: 16 kHz is baked into the Opus path and the decimator.
- `Packet`: 13-byte header `'P' 'T' | version=2 | codec | ttl | senderId int32 | seq int32 | payload`.
  Codec 0 = PCM16LE frame, 1 = Opus packet, 2 = `Hello` roster heartbeat (no audio; older
  builds drop it as unknown, so it needed no version bump). Receivers decode per packet, so codecs can mix.
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
- `PttEngine.onPacket`: dedupe by (senderId, seq) seen-cache, relay to other
  transports/links if `relay` is on and ttl > 1 (ttl clamped to our own `maxHops`, then
  decremented in place), then decode and play unless half-duplex and transmitting. Opus
  decoders are per sender, created on demand, at most 8 at once (quietest evicted), released
  after 30 s of silence. Encoder failure falls back to PCM and reports it.
- Wire format has no legacy mode: every phone must run the same build (README says so).
- Wi-Fi Aware: every node publishes and subscribes; lower senderId initiates the
  data path (one link per pair). Publisher uses accept-any on API 31+.

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
1. Hardware PTT: media/headset button or volume key to key the mic while the screen is off.
2. Opus packet loss concealment: feed the decoder a null packet for a missed seq instead of
   letting the jitter queue underrun.
