# PTT — push-to-talk / intercom for Android over WLAN or Bluetooth

Kotlin, minSdk 29. No third-party audio libs: raw 16 kHz mono PCM16 in 20 ms frames.

## Build
Open the folder in Android Studio (Hedgehog or newer), let it generate the Gradle
wrapper, build, install on two or more phones.

## Use
1. Tick one or more transports. A phone running several at once bridges
   them (e.g. boat Wi‑Fi on one side, Wi‑Fi Aware on the other).
2. LAN: all phones on the same network just press Connect — UDP multicast
   239.255.42.1:47474, no server. Works on a boat router or a phone hotspot;
   often blocked on guest/public Wi-Fi.
3. Bluetooth: pair the phones first (system settings). On one phone choose
   "Listen only", on the other pick that phone from the list, then Connect on both.
3b. Wi‑Fi Aware: nothing to set up — phones discover each other directly,
   no access point, no pairing. Range is Wi‑Fi class. Needs hardware support
   (most Pixel/Samsung flagships; check `WifiAwareManager.isAvailable`).
3c. **Relay** (on by default): each phone forwards audio it receives to all
   its other links, with a seen‑cache to stop loops. This turns Aware/BT links
   into an app‑level flooding mesh: A‑B‑C works when A and C are out of range.
4. **Half duplex** (default): hold the big button to talk; you don't hear others while holding.
5. **Full duplex**: flip the switch; the button becomes a mic toggle, everyone is
   heard at once and overlapping talkers are mixed. A headset (wired or BT)
   gives far better results than speakerphone, though AEC is enabled.
6. **Screen off / background**: while connected the app runs as a foreground
   service with a wake lock and a Wi‑Fi lock, so you keep hearing the crew with
   the screen off or another app in front. The ongoing notification shows the
   status line and has a Disconnect action. In full duplex the mic stays on in
   the background too; half duplex needs the screen to hold the button.
   On Android 13+ grant the notification permission or the notification is hidden
   (the session still runs).

## Layout
- `audio/AudioCapture` — AudioRecord + AEC/NS, 20 ms frames
- `audio/AudioPlayback` — AudioTrack, streaming
- `audio/Mixer` — per-sender jitter queue + summing mixer
- `transport/LanTransport` — UDP multicast
- `transport/BluetoothTransport` — RFCOMM, server + client
- `transport/WifiAwareTransport` — NAN publish/subscribe, TCP data paths, lower‑id‑initiates tie‑break
- `transport/StreamLink` — uint16 length‑prefixed framing shared by BT and Aware
- `Packet` — 10-byte header: 'PT', senderId, seq
- `PttEngine` — modes, multi‑transport, relay with (senderId, seq) seen‑cache
- `PttService` — foreground service owning the engine, wake/Wi‑Fi locks, notification
- `MainActivity` — UI, permissions; binds to the service while visible

## Next steps if you take it further
- Opus (libopus via JNI or `androidx.media3`) to cut bandwidth ~10×, which
  matters most on Bluetooth and marginal Wi-Fi.
- Hop count in the header if you want to cap relay depth on big networks.
- Roster/heartbeat packets so the UI can show who is online.
