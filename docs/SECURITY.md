# Crew Radio — security design

What can go wrong, what the app does about it, and how that maps to the EU Cyber Resilience
Act's essential requirements. The disclosure policy is in the repository's
[SECURITY.md](../SECURITY.md); the code is described in [ARCHITECTURE.md](ARCHITECTURE.md).

## What the app is, security-wise

A voice intercom between a few phones over WLAN, Bluetooth and Wi‑Fi Aware, with each phone
repeating packets for the others. No server, no account, no internet traffic, no stored
recordings. The assets are the crew's conversation (confidentiality), the crew's ability to talk
(availability) and the certainty that a voice on the channel is a crew member (authenticity).

## Threats and what is done about them

| Threat | Where | What the app does |
| --- | --- | --- |
| Eavesdropping on the conversation | Anyone on the same WLAN; Bluetooth or Aware only after joining the link | Every packet is encrypted with AES‑256‑GCM under a key derived from the crew's **channel key**. Bluetooth links are additionally link-encrypted (bonded RFCOMM), Aware links by their passphrase, which is the same channel key. |
| Injecting audio, spoofing a crew member, feeding junk into the relay | Same | Every packet is authenticated (GCM tag over the payload and the header). A packet without the key fails the tag and is dropped before it reaches the relay, the roster or a decoder. |
| Replaying captured packets | Same | Each packet carries a per-sender sequence number; the seen-cache drops repeats and the sequence tracker drops late packets. A replay after a rejoin would carry the old sender id and sequence and be dropped as seen or late. |
| Flooding a phone or the mesh | Any peer with the key, or a bug | Per-sender rate limit (75 packets/s, burst 150) before any processing; a hop limit (default 4, clamped to the receiver's own) so nothing circulates; at most 64 roster entries, 8 decoders, bounded queues and caches; oversized packets (over 1024 bytes) dropped unread. |
| Malformed packets crashing the app | Any peer | Fixed-size header validated first; hello payload decoded strictly (length, UTF‑8, control characters); audio only reaches the platform Opus decoder, itself hardened, and a decoder failure is contained to that sender. Transport threads catch everything and report instead of dying. |
| A joined phone reading other phones' data | Crew member | There is nothing else to read: the wire carries voice and hellos (name, transports, hop budget). |
| Someone with the key joining unnoticed | Anyone who learned the key | Not prevented: possession of the key is membership. The roster shows every member and where they arrive from; change the channel key to evict. |
| Loss or theft of a phone | Physical | The channel key is in the app's private preferences on a device-encrypted phone. Change the key on the rest of the crew. |
| A malicious update | Supply chain | Releases are built by GitHub Actions from `main`, signed with the crew's release key held only as a repository secret, with a signed build-provenance attestation, an SBOM and a SHA‑256 per release. Dependencies are AndroidX and Material only, updated by Dependabot; CodeQL scans the code. |
| Weak default configuration | First use | There is no default key: each phone generates a random one on first start and the crew shares it. Relay, Opus and the rate limit need no configuration. |

## What it does not do

- It does not hide *that* phones are talking: packet timing and sizes are visible on the WLAN.
- It does not authenticate individual people: the key is shared by the crew, as on a VHF channel.
- It does not protect against a crew member's phone that is itself compromised.
- The Bluetooth and Aware links, the phone's audio stack and headsets are the platform's; see
  SECURITY.md for what is in scope.

## The wire format

```
'P' 'T' | version = 3 | codec | ttl | senderId int32 | seq int32 | nonce (12) | ciphertext | tag (16)
```

- Key: PBKDF2‑HMAC‑SHA256 over the channel key with a fixed application salt, 64 000
  iterations, 256 bits. Deterministic, so every phone with the same channel key derives the
  same key; cached for the session.
- AEAD: AES‑256‑GCM, a fresh 96‑bit random nonce per packet (`SecureRandom`), 128‑bit tag.
- Associated data: the 13‑byte header with the ttl byte zeroed, because relays decrement the ttl
  in place. Sender id, sequence and codec are therefore authenticated; a relay cannot change them.
- Cost: 28 bytes per packet, about 1.4 kB/s at 50 packets a second.

## CRA Annex I mapping (informative)

The app is open source and not placed on the market commercially, so the Cyber Resilience Act's
manufacturer obligations do not apply to it; its essential requirements are still a good
checklist, and this is where the app stands against each:

| Requirement (Annex I, Part I) | Status |
| --- | --- |
| (1) Appropriate level of cybersecurity based on the risks | Threat model above; risks are eavesdropping, injection and flooding on a shared radio medium. |
| (2)(a) No known exploitable vulnerabilities at release | CodeQL on every change; Dependabot alerts; only the latest release supported. |
| (2)(b) Secure by default configuration | Random channel key generated on first start; no default passphrase; relay bounded by default. |
| (2)(c) Security updates | A signed release per merge; the crew updates together (the wire format enforces a single version). No automatic update: the app has no network access to a server by design. |
| (2)(d) Protection from unauthorised access | AEAD on every packet: no key, no access. |
| (2)(e) Confidentiality of data | AES‑256‑GCM on the wire; nothing stored except settings, in app-private storage. |
| (2)(f) Integrity of data, commands and configuration | GCM tag over payload and header; settings are local to each phone. |
| (2)(g) Data minimisation | The wire carries voice frames and a name; nothing else is collected or kept. |
| (2)(h) Availability of essential functions, resilience to DoS | Rate limit, hop limit, bounded caches, reconnect in every transport, loss concealment. |
| (2)(i) Minimising impact on other services | Packets are small and rate-limited; Wi‑Fi multicast plus broadcast is the only "noisy" behaviour and is confined to the WLAN. |
| (2)(j) Limited attack surface | No server, no internet, no third-party libraries, permissions only for the links in use. |
| (2)(k) Reduced impact of incidents | A compromised key is changed on the crew's phones; nothing else to leak. |
| (2)(l) Security-relevant logging | The Status screen keeps the last 40 status lines (route changes, rejected packets counted); nothing leaves the phone. |
| (2)(m) Secure deletion | Uninstalling the app removes its private storage; there is no other data. |

Vulnerability handling (Annex I, Part II): SBOM per release, private vulnerability reporting
enabled, fixes shipped as releases, this document and SECURITY.md as the public description.
