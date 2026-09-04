# PTT — project guide for Claude Code

Android push-to-talk / intercom app for a boat crew (vessel Arabella). Works over
WLAN multicast, Bluetooth Classic RFCOMM and Wi-Fi Aware, with an app-level
flooding relay so multiple transports and multi-hop topologies work.

## Stack
- Kotlin, Android Gradle Plugin 8.5, Kotlin 2.0, minSdk 29, targetSdk 34, JDK 17.
- No third-party audio libs. Raw 16 kHz mono PCM16, 20 ms frames (see `audio/AudioConfig`).
- Build: `./gradlew assembleDebug` (generate the wrapper jar first with
  `gradle wrapper` or by opening in Android Studio). Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
- No unit tests yet. Real testing needs two or more physical phones; the emulator
  has no Bluetooth or Wi-Fi Aware.

## Architecture
```
MainActivity -(bind)-> PttService -> PttEngine -> Transport (LanTransport | BluetoothTransport | WifiAwareTransport)
                                         |-> AudioCapture (mic, AEC/NS)  -> Packet.encode -> transports
                                         |-> Mixer (per-sender jitter queue + sum) -> AudioPlayback
```
- `PttService`: bound while the activity is visible, promoted to a started foreground
  service (types microphone|connectedDevice) with a partial wake lock and a low-latency
  Wi-Fi lock for the duration of a session. Owns the engine; the notification mirrors the
  status line and has a Disconnect action. The activity never disconnects on its own
  lifecycle, only on the button or the notification action.
- `Packet`: 10-byte header `'P' 'T' | senderId int32 | seq int32 | PCM16LE`.
- `Transport` interface: `start(onPacket, onStatus)`, `send(packet, except)`, `stop()`,
  `relayWithin` (false for multicast). Stream transports frame packets with
  `StreamLink` (uint16 BE length prefix).
- `PttEngine.onPacket`: dedupe by (senderId, seq) seen-cache, relay to other
  transports/links if `relay` is on, then play unless half-duplex and transmitting.
- Wi-Fi Aware: every node publishes and subscribes; lower senderId initiates the
  data path (one link per pair). Publisher uses accept-any on API 31+.

## Conventions
- Keep transports symmetrical (every phone is both server and client) — no
  designated master, the app must survive any phone dropping out.
- Anything blocking (sockets, AudioTrack.write) lives on its own named thread
  (`ptt-*`); never on the main thread.
- `onStatus` strings are shown verbatim in the UI status line; keep them short.
- Status/errors are reported, never swallowed silently, except transient send failures.

## Known gaps / roadmap
1. Opus encoding (libopus JNI or media3) to cut bandwidth ~10x — matters for BT and weak Wi-Fi.
2. TTL / hop-count byte in the header to bound relay depth on larger networks.
3. Roster and heartbeat packets so the UI can show who is online and via which transport.
4. Reconnect logic for BT and Aware links after a drop (currently manual reconnect).
5. Settings screen: multicast group/port, Aware passphrase, sample rate.
6. Hardware PTT: media/headset button or volume key to key the mic while the screen is off.
