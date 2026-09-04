# Crew Radio — push-to-talk / intercom for Android over WLAN, Bluetooth or Wi-Fi Aware

Kotlin, minSdk 29. No third-party audio libs: 16 kHz mono in 20 ms frames, sent as
Opus (via the platform codec) or raw PCM16.

## Install
Every merge to `main` builds a signed APK and publishes it on the
[Releases](../../releases) page as `CrewRadio-<version>.apk`. On each phone open that page,
download the newest APK and allow the install (Android asks once to allow installs from the
browser). Every phone on the crew must run the same version; the Status screen (menu) shows
the version and the commit it was built from.

The version is `1.<number of commits on main>`, set by the build from git, so nobody bumps
numbers by hand. Pull requests get the same APK as a workflow artifact, unsigned for release.

## Build
Open the folder in Android Studio (Koala or newer, for Android Gradle Plugin 8.5) and build, or run
`./gradlew assembleDebug` with an Android SDK (platform 34) installed. Install on
two or more phones. Pure-Kotlin unit tests: `./gradlew testDebugUnitTest`.

A local build is signed with the debug key; a Release from GitHub with the crew's release key.
Android will not upgrade one with the other in place: uninstall first when switching between
them (the app keeps no data worth losing). To sign locally like the pipeline, put the keystore
outside the repository and export `CREWRADIO_KEYSTORE`, `CREWRADIO_KEYSTORE_PASSWORD`,
`CREWRADIO_KEY_ALIAS` and `CREWRADIO_KEY_PASSWORD` before `./gradlew assembleRelease`.

## Use
1. Tick one or more transports. A phone running several at once bridges
   them (e.g. boat Wi‑Fi on one side, Wi‑Fi Aware on the other).
2. LAN: all phones on the same network just press Connect — UDP multicast
   239.255.42.1:47474, no server. Every frame also goes to the subnet broadcast
   address, so it still works on routers that filter multicast; the receivers drop
   whichever copy arrives second. Works on a boat router or a phone hotspot. Guest
   or public Wi-Fi with client isolation blocks both copies — use Bluetooth or
   Wi-Fi Aware there. The status line shows "LAN: hearing <ip>" the first time a
   packet arrives, which is the quickest way to tell a network problem from an app one.
3. Bluetooth: pair the phones first (system settings). On one phone choose
   "Listen only", on the other pick that phone from the list, then Connect on both.
3b. Wi‑Fi Aware: nothing to set up — phones discover each other directly,
   no access point, no pairing. Range is Wi‑Fi class. Needs hardware support
   (most Pixel/Samsung flagships; check `WifiAwareManager.isAvailable`).
3c. **Relay** (on by default, Settings > Talking): each phone forwards audio it receives to all
   its other links, with a seen‑cache to stop loops. This turns Aware/BT links
   into an app‑level flooding mesh: A‑B‑C works when A and C are out of range.
4. **Half duplex** (default): hold the big button to talk; you don't hear others while holding.
5. **Full duplex** (Settings > Talking): the button becomes a mic toggle, everyone is
   heard at once and overlapping talkers are mixed. A headset (wired or BT)
   gives far better results than speakerphone, though AEC is enabled.
5b. **Headset** (Settings > Talking > Audio output): a Bluetooth headset gets the voice both
   ways over its hands-free (SCO) link as soon as it is connected, a wired or USB headset when
   plugged in, and the speakerphone otherwise; it follows the headset as it comes and goes
   while on channel, with an "Audio: ..." status line on each change. Without a headset the
   phone behaves like a phone call: lift it to your ear and the voice moves to the earpiece
   and your voice keys the mic (see 5c), put it down and it is back on the loudspeaker with
   the button. The proximity sensor decides, exactly as it darkens the screen in a call.
   "Always the loudspeaker" ignores headsets and the ear; "Earpiece" keeps the earpiece
   always, still with voice keying only at the ear. Away from the ear the phone never
   transmits by itself, and the button works everywhere. A hands-free headset's button is a media key to the app
   (press = talk on/off, see Talk button); if the headset instead treats the press as a
   hang-up, the phone drops the headset's audio link for a moment and the app re-opens it
   (a second of silence). For headsets whose button only ever hangs up, turn on Settings >
   Talking > "Headset button hangs up": the channel is then registered with the phone as an
   ongoing (self-managed) call while such a headset is in use, so the hang-up becomes the
   talk key. That shows on a car kit as a call, and an incoming phone call puts the channel
   on hold, muted both ways, until you hang up ("On hold: phone call" / "Back on channel").
   Note that in that mode the phone drops any media key the headset sends, so use it only
   for headsets that do not send one.
5c. **Voice keys the mic** (Settings > Talking, VOX): many hands-free headsets (a Jabra
   Evolve2 65 was measured) do not transmit their main button at all while their voice link
   is up, so no app can use it, and their mute is invisible to apps too. With this on, the
   app keeps the headset mic open the whole time you are on channel and keys itself when
   you speak: on air within 40 ms (the first 100 ms are kept and sent, so nothing is
   clipped), off air 1.5 s after you stop. Mute the headset, or lift the boom, to be sure
   you are off. Meant for a headset with its own noise suppression, which is quiet between
   words. On the phone's own mic it is always on while the phone is at your ear (any output
   but "Always the loudspeaker"): the proximity sensor arms it, because on the phone's mic
   the level cannot tell your voice from a shipmate's a metre away. In heavy wind or engine
   noise it may stay on air; use the loudspeaker and the button there.
6. **Screen off / background**: while connected the app runs as a foreground
   service with a wake lock and a Wi‑Fi lock, so you keep hearing the crew with
   the screen off or another app in front. The ongoing notification shows the
   status line and has a Disconnect action. In full duplex the mic stays on in
   the background too; half duplex needs the screen to hold the button.
   On Android 13+ grant the notification permission; if you deny it the notification
   is hidden from the drawer, but the session still runs and stays visible in the
   system Task Manager. On Android 14+ the platform no longer lets an app keep Wi‑Fi
   out of power save with the screen off, so expect slightly higher latency there
   until the screen comes back on.

7. **Opus compression** (on by default, Settings > Talking): frames are encoded with the phone's built-in
   Opus codec at 24 kbit/s, roughly a tenth of raw PCM, which is what makes Bluetooth
   and marginal Wi‑Fi usable. Every packet says which codec it carries, so Opus and
   PCM phones can share a net. If a phone has no working Opus encoder it falls back to
   PCM by itself and says so in the status line. Changes take effect the next time
   the mic is keyed.
8. **Hop limit**: packets carry a hop count (4 by default) that each relay decrements,
   so a flood cannot circulate forever on a large mesh. A relay never forwards a packet
   further than its own budget, whatever the sender stamped.
9. **Same version everywhere**: the packet header changed with Opus support and there is
   no compatibility mode. Phones on an older build simply hear nothing from newer ones
   (and vice versa), so update the whole crew together.
10. **Reconnect**: a session keeps itself alive until you press Disconnect. A Bluetooth
   peer that drops out of range is re-dialled with a growing delay (1 s up to 15 s); a
   Wi‑Fi Aware peer is re-dialled while it is still discoverable and picked up again when
   it reappears; when Wi‑Fi itself goes away, Aware re-attaches and LAN re-joins the
   multicast group as soon as it is back. The status line says what it is waiting for.
   Whichever phone dialled a link is the one that restores it, so the "Listen only" side
   of a Bluetooth pair never has to do anything.
11. **Roster**: every phone announces itself once a second (a 40-byte hello carrying its
   Android device name, the transports it is on and its hop budget), relayed like audio.
   The list under the status line shows who is online, how you hear them (which transport,
   how many hops), what they are connected to, and who is talking; the notification title
   shows the head count. A phone silent for 4 s drops off. Rename a phone in
   Settings > About phone > Device name, or set My name in the app settings.
   Older builds simply ignore hellos and are listed by id from their audio.
12. **Settings** (menu, top right): My name (what the others see), Channel name (the big word at the
   top, the boat or the crew); full duplex, relay and Opus; the multicast group and
   port, the Wi‑Fi Aware passphrase (phones only link when it matches, so it doubles as a
   crew name) and the hop limit. Name and hop limit apply at once; the network ones on the
   next Connect. The main screen keeps only what changes per trip - transports and the
   Bluetooth peer - and remembers them, so a normal day is open the app, flip the channel
   switch on.
13. **Talk button** (Settings > Talking): while on the channel, a headset or media button and
   the volume keys key the mic. Press once: mic on and a short buzz. Press again: mic off and
   a double buzz (Settings > Talking > Talk key tones adds a tone in the ear for each, off by
   default). Holding a headset button
   talks for as long as you hold it, like a radio, on headsets that report the release; a
   Bluetooth headset that only clicks toggles. Works with the screen off, which is the point;
   turn off Keep screen on in the same section and the phone can live in a pocket. Volume keys
   do nothing else while on channel. Defaults to both; set to Off to get the volume keys back.
14. **Packet loss**: a missed 20 ms frame is filled with a fading repeat of the previous one
   (up to three in a row, then silence) instead of a click of silence, for Opus and PCM alike.
   The Status screen counts concealed frames, which is a good measure of how a link is doing.
15. **Status** (menu): everything the main screen leaves out - each crew member with id,
   transport, hops, what they are on and when last heard; where the audio is going; this phone's addresses (Wi-Fi,
   the Aware link), the multicast group and port, Bluetooth name and hop limit in use;
   packet counters (received, sent, relayed, duplicates dropped, hellos); and the last 40
   status lines with time stamps. Refreshes every second while open.

## Layout
- `audio/AudioRoute` — headset (Bluetooth SCO, wired, USB) or speakerphone, following headsets as they come and go
- `audio/AudioCapture` — AudioRecord + AEC/NS, 20 ms frames
- `audio/AudioPlayback` — AudioTrack, streaming
- `audio/Mixer` — per-sender jitter queue + summing mixer
- `audio/OpusEncoder`, `audio/OpusDecoder` — MediaCodec `audio/opus`, one decoder per sender
- `audio/Decimator` — 48 kHz decoder output back down to 16 kHz
- `audio/Conceal` — packet-loss concealment: the last frame, fading, for up to three missing slots
- `audio/Tones` — the cue tones a talk key plays in the ear, as mixer frames
- `audio/MicGate` — voice-operated keying: 40 ms of speech opens, 1.5 s of quiet closes
- `transport/LanTransport` — UDP multicast + subnet broadcast
- `transport/BluetoothTransport` — RFCOMM, server + client
- `transport/WifiAwareTransport` — NAN publish/subscribe, TCP data paths, lower‑id‑initiates tie‑break
- `transport/StreamLink` — uint16 length‑prefixed framing shared by BT and Aware
- `transport/Threads` — `transportThread`, so a transport thread reports instead of killing the app
- `transport/Backoff` — 1 s → 15 s retry schedule shared by every reconnect path
- `Packet` — 13-byte header: 'PT', version, codec, ttl, senderId, seq
- `Hello` — roster heartbeat payload: name, transport flags, hop budget
- `PttEngine` — modes, codec, multi‑transport, relay with (senderId, seq) seen‑cache and ttl, roster + heartbeat
- `Prefs`, `SettingsActivity` — validated settings (`SettingsRules`) and remembered main-screen choices
- `StatusActivity` — crew detail, addresses, counters and the status log, one tap from the main screen
- `PttService` — foreground service owning the engine, wake/Wi‑Fi locks, notification
- `MainActivity` — UI, permissions; binds to the service while visible

## Next steps if you take it further
- Roster/heartbeat packets so the UI can show who is online.
