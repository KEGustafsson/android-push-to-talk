# signalk-crewradio

Lets the boat's Signal K server speak to the crew on the [Crew Radio](../README.md) channel. The
server becomes one more node on the crew's push-to-talk network, over the boat's LAN or WLAN, and
the phones relay it onward over Bluetooth and Wi‑Fi Aware like any other talker. The voice is
made inside the plugin: no cloud, no containers, no other plugin needed.

Three things:

1. **Say anything to the crew.** A text becomes speech and goes out on the channel. Three doors:
   a PUT to the Signal K path `communication.crewradio.say`, `POST /plugins/signalk-crewradio/say`,
   and an in-process `say()` for other plugins. Node-RED, dashboards and automations use the first
   two. An urgent announcement goes to the front of the queue and cuts a normal one short.
2. **Signal K notifications are announced.** A notification at or above a chosen state (default
   `alarm`) that asks for sound is said when it is raised, again every 30 s while it stays raised,
   and stops when it clears. `emergency` is said as urgent.
3. **The crew roster in Signal K.** `communication.crewradio.online`, `.nodes`, `.talking` and
   `.speaking` show who is on the channel, who is talking and whether the server is announcing.

Everything on the wire is the app's own format: AES‑256‑GCM under the crew's channel key, the
same packets, the same roster hellos. The plugin's tests and the app's unit tests check the same
byte vector, so the two cannot drift apart unnoticed.

![The plugin's page in the Signal K admin UI](./docs/screenshots/plugin-config.png)

![The roster in the Data Browser](./docs/screenshots/data-browser.png)

![An alarm on its way from Signal K to the crew's phones](./docs/screenshots/announcement.png)

![How the plugin fits between Signal K and the phones](./docs/screenshots/how-it-fits.png)

The admin UI pictures are captures of a Signal K server 2.31 with the plugin installed; the two
others are drawn. All carry example names only.

## The voice

[Flite](https://github.com/festvox/flite), CMU's small speech synthesizer, compiled to
WebAssembly (`@echogarden/flite-wasi`, the plugin's only dependency, 21 MB, no native code, no
download at run time). English, four voices: **slt** (female, the default), kal16, rms and awb.
It speaks at 16 kHz, the channel's own rate, and a sentence takes a fraction of a second on a
laptop and about a second on a Raspberry Pi; a sentence said before comes from a cache at once.
Numbers and the units in alarm texts are spelled out: "25 m" is read as "25 metres", "12.2 V"
as "12.2 volts". Texts are capped at 500 characters.

## Requirements

- Signal K server 2.x on Node **24 or newer** (the server itself needs 22; the plugin uses
  Node's WASI, which prints one "experimental" notice in the server log at first use).
- The server on the same network as the phones: wired to the boat's router (LAN) or on its
  WLAN, either way one network with the WLAN the phones use. One phone's hotspot works too if
  the server can join it.
- The crew's channel key (on any phone: Settings › Channel key), and the WLAN link ticked on at
  least one phone; that phone relays the server to the rest.

## Install

Until it is on npm, install from a clone of this repository (npm cannot install a
subdirectory of a Git URL):

```sh
git clone https://github.com/KEGustafsson/android-push-to-talk.git
cd ~/.signalk
npm install /path/to/android-push-to-talk/sk-plugin
```

and restart the server. Then, in the Signal K admin UI, **Server › Plugin Config › Crew Radio**:
paste the channel key, check the multicast group and port match the phones (the defaults do),
pick the server's interface on the boat network (eth0 or wlan0), enable. Test from a shell on
the server:

```sh
curl -X POST http://localhost:3000/plugins/signalk-crewradio/say \
     -H 'Content-Type: application/json' \
     -d '{"text":"Crew radio check. This is the boat.","priority":"normal"}'
```

The phones on the channel play the chime and the sentence, and list the server on their roster.
The same works as a PUT to `vessels.self.communication.crewradio.say` with a string or
`{"text": "...", "priority": "urgent"}`, and from another plugin through
`app.onPropertyValues("signalk-crewradio.api", values => values.at(-1)?.value?.say({ text }))`.
`say()` resolves when the announcement is queued: `{ok, queued: <items ahead>, priority, seconds}`.

## Settings

| Setting | Default | Meaning |
|---|---|---|
| Channel key | | The crew's key, exactly as on the phones. |
| Name on the roster | vessel name | How the phones list the server. |
| Voice | slt | slt (female), kal16, rms, awb (male). |
| Speaking rate | 1 | 0.7 slow to 1.3 fast. |
| Chime | on | Two notes before an announcement, three quick ones before an urgent one. |
| Wait for a gap in talk | 2000 ms | An announcement waits this long at most before cutting in. |
| Multicast group, UDP port | 239.255.42.1, 47474 | Must match the phones' WLAN settings. |
| Network interface | auto | The server's interface on the boat network, wired (eth0) or WLAN (wlan0). auto: wlan first, then eth/en, then anything with an IPv4 address. |
| Hop budget | 4 | How far phones may relay the server's packets. |
| Announce from state | alarm | alert, warn, alarm or emergency. |
| Only notifications that ask for sound | on | Signal K notifications carry `method: [visual, sound]`. |
| Repeat every | 30 s | 0 says it once. |
| Urgent states | emergency | Said first, interrupting a normal announcement. |
| Only / never these paths | | Globs under `notifications.`, e.g. `navigation.anchor`, `mob`, `navigation.**`. |

## Notes

- **Half duplex.** A phone in half-duplex mode mutes playback while its own mic is keyed, so a
  crew member who is talking misses the announcement. The plugin waits for a gap first; a
  future app change will let urgent announcements through regardless.
- **Every phone gets its own copy.** Besides multicast and broadcast, each frame is sent unicast
  to every phone the plugin has heard from directly. Access points send multicast at their lowest
  rate without acknowledgement, and phones lose a few percent of it even in the same cabin, which
  was audible as gaps; unicast is retried and rate-adapted, and the phones drop the copies they
  get twice. Frames also leave 100 ms ahead of time, which keeps a late timer tick on the server
  (the usual 15 ms) from running a phone's queue dry; a longer stall is still audible.
- **Bandwidth.** Speech goes out as PCM, 34 kB/s per copy while talking, nothing otherwise but one
  small hello a second; over a Bluetooth relay hop it is the same as a phone talking in PCM.
- **Two nodes named alike** are fine; the phones list nodes by id.
- **Security.** The channel key sits in the server's plugin config like any other secret there.
  Anyone who can read the Signal K configuration can read it, and anyone who can reach the
  server's REST API or PUT paths can make it speak; treat the server as a crew member.

## Development

```sh
cd sk-plugin
npm test          # Node's test runner
npm run coverage  # the same, gated at 80 % lines
```

`test/vector.json` is the cross-language vector; regenerate it only when the wire format
changes, and change the app's `CrossLanguageVectorTest` with it.

`tools/cli.js` runs the plugin's pieces from a shell on any machine on the boat network, no
Signal K needed, which is how the plugin was verified against real phones:

```sh
node tools/cli.js roster --key KEY                                # join, list who is on the channel, leave
node tools/cli.js say    --key KEY --text "Anchor is dragging"    # speak a text on the channel
node tools/cli.js say    --key KEY --wav clip.wav                 # speak a WAV on the channel
node tools/cli.js tts    --text "Hello" --out hello.wav --voice rms   # speech to a file, no network
```

Phones list the machine under `--name` (default "Laptop"). On Windows the adapter may need
`--iface WiFi` or `--iface Ethernet`. The phones must have the WLAN transport on (Settings › Links).

Licence: EUPL‑1.2, like the app. Flite is under CMU's BSD-style licence.
