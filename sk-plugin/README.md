# signalk-crewradio

Puts the boat's Signal K server on the [Crew Radio](../README.md) channel. The server becomes
one more node on the crew's push-to-talk network, over the boat WLAN, and the phones relay it
onward over Bluetooth and Wi‑Fi Aware like any other talker. What it says comes from the boat's
voice assistant, [signalk-wyoming](https://github.com/hoeken/signalk-wyoming), for which this
plugin is a speaker: a Wyoming satellite without a microphone.

Three things:

1. **The assistant speaks to the crew.** Anything that makes the boat speak through
   signalk-wyoming, a PUT to `voice.say`, its REST call, or another plugin's `say()`, reaches the
   crew's phones as speech on the channel when it targets the satellite `crewradio` (or no target,
   which means every speaker). Urgent announcements jump the queue there, and here they cut in
   after at most a short wait for a gap in the talk.
2. **Signal K notifications are announced.** signalk-wyoming does not announce notifications by
   itself; this plugin does. A notification at or above a chosen state (default `alarm`) that asks
   for sound is said when it is raised, again every 30 s while it stays raised, and stops when it
   clears. `emergency` is said as urgent.
3. **The crew roster in Signal K.** `communication.crewradio.online`, `.nodes` and `.talking`
   show who is on the channel and who is talking, for dashboards and automations.

Everything on the wire is the app's own format: AES‑256‑GCM under the crew's channel key, the
same packets, the same roster hellos. The plugin's tests and the app's unit tests check the same
byte vector, so the two cannot drift apart unnoticed.

## Requirements

- Signal K server 2.x on Node **24 or newer** (signalk-wyoming needs 24; the server itself 22).
- [signalk-wyoming](https://github.com/hoeken/signalk-wyoming) with its Piper text-to-speech
  service (signalk-piper). Without it the roster still works and the satellite waits, but nothing
  can be spoken.
- The server on the same WLAN as the phones. The boat's router or one phone's hotspot both do.
- The crew's channel key (on any phone: Settings › Channel key).

## Install

Until it is on npm, install from a clone of this repository (npm cannot install a
subdirectory of a Git URL):

```sh
git clone https://github.com/KEGustafsson/android-push-to-talk.git
cd ~/.signalk
npm install /path/to/android-push-to-talk/sk-plugin
```

and restart the server. Then, in the Signal K admin UI:

1. **Server › Plugin Config › Crew Radio**: paste the channel key, check the multicast group and
   port match the phones (defaults do), pick the interface on the boat WLAN, enable.
2. **Server › Plugin Config › signalk-wyoming › Satellites**: add one with
   `id: crewradio`, `host: 127.0.0.1`, `port: 10701`, no wake words. That makes it a
   speaker-only satellite; signalk-wyoming connects to it and shows it under `voice.satellites.crewradio.state`.
3. Test from a shell on the server:
   ```sh
   curl -X POST http://localhost:3000/plugins/signalk-wyoming/api/say \
        -H 'Content-Type: application/json' \
        -d '{"text":"Crew radio check","targets":["crewradio"]}'
   ```
   The phones on the channel play the chime and the sentence, and list the server on their roster.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| Channel key | | The crew's key, exactly as on the phones. |
| Name on the roster | vessel name | How the phones list the server. |
| Multicast group, UDP port | 239.255.42.1, 47474 | Must match the phones' WLAN settings. |
| Network interface | auto | wlan first, then eth/en, then anything with an IPv4 address. |
| Hop budget | 4 | How far phones may relay the server's packets. |
| Wyoming satellite port, bind address | 10701, 127.0.0.1 | What signalk-wyoming connects to. The protocol has no authentication, so the satellite listens on loopback unless you widen it for an orchestrator on another host. |
| Satellite id | crewradio | The id given in signalk-wyoming; the bridge targets it. |
| Chime | on | Two notes before each announcement. |
| Wait for a gap in talk | 2000 ms | An announcement waits this long at most before cutting in. |
| Announce from state | alarm | alert, warn, alarm or emergency. |
| Only notifications that ask for sound | on | Signal K notifications carry `method: [visual, sound]`. |
| Repeat every | 30 s | 0 says it once. |
| Urgent states | emergency | Said with priority urgent in signalk-wyoming. |
| Only / never these paths | | Globs under `notifications.`, e.g. `navigation.anchor`, `mob`, `navigation.**`. |
| Also on the boat's own speakers | off | On: the bridge targets every satellite, not only the crew. |

## Notes

- **Half duplex.** A phone in half-duplex mode mutes playback while its own mic is keyed, so a
  crew member who is talking misses the announcement. The plugin waits for a gap first; a
  future app change will let urgent announcements through regardless.
- **Two nodes named alike** are fine; the phones list nodes by id.
- **Bandwidth.** Speech goes out as PCM, 34 kB/s while talking, nothing otherwise but one small
  hello a second. Fine on WLAN; over a Bluetooth relay hop it is the same as a phone talking in PCM.
- **Security.** The channel key sits in the server's plugin config like any other secret there.
  Anyone who can read the Signal K configuration can read it; treat the server as a crew member.

## Development

```sh
cd sk-plugin
npm test        # Node's test runner, no dependencies
```

`test/vector.json` is the cross-language vector; regenerate it only when the wire format
changes, and change the app's `CrossLanguageVectorTest` with it.

`tools/cli.js` runs the plugin's pieces from a shell on any machine on the WLAN, no Signal K
needed, which is how the plugin was verified against real phones:

```sh
node tools/cli.js roster --key KEY                      # join, list who is on the channel, leave
node tools/cli.js say    --key KEY --wav clip.wav        # speak a WAV on the channel
node tools/cli.js serve  --key KEY --port 10701          # join and run the Wyoming satellite
node tools/cli.js send   --to 127.0.0.1:10701 --wav clip.wav   # play the orchestrator's part against it
```

Phones list the machine under `--name` (default "Laptop"). On Windows the WLAN adapter may need
`--iface WiFi`. The phones must have the WLAN transport on (Settings › Links).

Licence: EUPL‑1.2, like the app.
