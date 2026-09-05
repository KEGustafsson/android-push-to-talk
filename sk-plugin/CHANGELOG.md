# Changelog

All notable changes to signalk-crewradio. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/); versions follow semver.

## 0.1.0 - 2026-09-05

First release.

### Added

- The Signal K server as a node on the Crew Radio channel over the boat's LAN or WLAN: the app's wire
  format and AES-256-GCM channel crypto, hellos and roster, duplicate suppression, packets paced
  at 20 ms. Byte-compatible with the Android app; the two test suites share one packet vector.
- A speaker-only Wyoming satellite for signalk-wyoming: `describe`/`info`, `ping`/`pong`,
  `pause-satellite`/`run-satellite`, `audio-start`/`audio-chunk`/`audio-stop`, `played`.
  Announcements are resampled from Piper's 22 050 Hz to 16 kHz, prefixed with a chime, and wait
  briefly for a gap in the talk. Listens on loopback unless configured otherwise.
- Notification bridge: Signal K notifications at or above a chosen state that ask for sound are
  announced through signalk-wyoming's in-process `say()`, urgent for `emergency`, repeated until
  they clear; include and exclude globs; optional fan-out to the boat's own speakers.
- The roster in Signal K: `communication.crewradio.online`, `.nodes`, `.talking`.
- `tools/cli.js` for testing from any machine on the boat network without Signal K.

### Security

- Packets are authenticated before duplicate suppression, so a forged header cannot displace an
  authentic packet. The Wyoming decoder caps header, data and payload sizes; an announcement is
  refused outside 8-48 kHz or two channels, and dropped beyond 60 s. The satellite accepts
  loopback clients only unless an allowlist of orchestrator addresses is configured, and a
  non-loopback bind without one falls back to loopback.
