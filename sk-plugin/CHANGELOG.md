# Changelog

All notable changes to signalk-crewradio. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semver.

## 0.1.0 - 2026-09-06

First release.

### Added

- The Signal K server as a node on the Crew Radio channel over the boat's LAN or WLAN: the app's
  wire format and AES-256-GCM channel crypto, hellos and roster, duplicate suppression, packets
  paced at 20 ms. Byte-compatible with the Android app; the two test suites share one packet vector.
- Text to speech inside the plugin: Flite in WebAssembly, English, four voices (slt by default),
  16 kHz output, a chime in front, a cache for repeated sentences, units and numbers spelled out.
- say() through a PUT on `communication.crewradio.say`, `POST /plugins/signalk-crewradio/say`,
  and the in-process PropertyValue `signalk-crewradio.api`; an announcement queue where urgent
  announcements go first and cut a normal one short.
- Notification bridge: Signal K notifications at or above a chosen state that ask for sound are
  announced, urgent for `emergency`, repeated until they clear; include and exclude globs.
- The roster in Signal K: `communication.crewradio.online`, `.nodes`, `.talking`, `.speaking`.
- `tools/cli.js` for testing from any machine on the boat network without Signal K.
- Delivery: frames go out 100 ms ahead of real time, and each one is also sent unicast to every
  phone heard from directly, because access points drop a few percent of multicast even in the
  same cabin; found with the phones' concealed-frame counter and the CLI.

### Security

- Packets are authenticated before duplicate suppression, so a forged header cannot displace an
  authentic packet. Texts are capped at 500 characters and the queue at 20 waiting announcements.
