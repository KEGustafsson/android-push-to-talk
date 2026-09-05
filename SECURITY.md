# Security policy

Crew Radio is a voice intercom for a small group of phones. It has no server, no account and no
internet traffic; everything stays on the phones and the links between them. The threat model,
what the app does about each threat, and how that maps to the EU Cyber Resilience Act's
essential requirements are in [docs/SECURITY.md](docs/SECURITY.md).

## Supported versions

Only the newest release on the [Releases](../../releases) page is supported. Every phone on a
crew must run the same version anyway; updating the crew is the fix for anything older.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's private reporting:
**Security › Report a vulnerability** on this repository. Include the app version (Status screen),
the phone model and Android version, the links in use (WLAN, Bluetooth, Wi‑Fi Aware) and what
you observed.

You will get an acknowledgement within 7 days and, for a confirmed problem, a fix or a mitigation
in a release within 30 days where it is in the app's hands (platform bugs are reported onward).
Reporters are credited in the release notes unless they prefer not to be.

## What is in scope

- The app's code and the wire protocol between phones.
- The release pipeline and the published APKs.

Out of scope: the Android platform's own Bluetooth, Wi‑Fi Aware and audio stacks, the phones'
Wi‑Fi router, and headsets; report those to their vendors, but tell us too if the app can work
around them.
