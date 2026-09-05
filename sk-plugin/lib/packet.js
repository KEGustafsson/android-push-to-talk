// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Crew Radio wire format, version 3, exactly as the app's Packet.kt and Hello.kt define it.
 *
 *   'P' 'T' | version u8 = 3 | codec u8 | ttl u8 | hops u8 | senderId int32 BE | seq int32 BE
 *   then: nonce (12) | ciphertext | tag (16)            (see crypto.js)
 *
 * The header is the AES-GCM associated data with the ttl byte zeroed, since relays rewrite it.
 * codec 0 = one 20 ms frame of PCM16LE 16 kHz mono, 1 = one Opus packet, 2 = a hello.
 */

const HEADER = 14;
const VERSION = 3;
const MAX_SIZE = 1024;

const Codec = Object.freeze({ PCM: 0, OPUS: 1, HELLO: 2 });

/** Builds the 14-byte header. `hops` is the sender's original budget and defaults to the ttl. */
function encodeHeader({ senderId, seq, codec, ttl, hops = ttl }) {
  const h = Buffer.alloc(HEADER);
  h[0] = 0x50; // 'P'
  h[1] = 0x54; // 'T'
  h[2] = VERSION;
  h[3] = codec & 0xff;
  h[4] = clampByte(ttl);
  h[5] = clampByte(hops);
  h.writeInt32BE(senderId | 0, 6);
  h.writeInt32BE(seq | 0, 10);
  return h;
}

/** Parses a packet's header; null for anything that is not a well-formed v3 packet with a payload. */
function parseHeader(p) {
  if (!Buffer.isBuffer(p) || p.length <= HEADER || p.length > MAX_SIZE) return null;
  if (p[0] !== 0x50 || p[1] !== 0x54 || p[2] !== VERSION) return null;
  const codec = p[3];
  if (codec !== Codec.PCM && codec !== Codec.OPUS && codec !== Codec.HELLO) return null;
  return { codec, ttl: p[4], hops: p[5], senderId: p.readInt32BE(6), seq: p.readInt32BE(10) };
}

/** The header as authenticated: the first 14 bytes with the ttl zeroed. */
function aadOf(p) {
  const a = Buffer.from(p.subarray(0, HEADER));
  a[4] = 0;
  return a;
}

function clampByte(n) {
  return Math.max(0, Math.min(255, n | 0));
}

// ---- Hello: ver u8 = 1 | transports u8 | ttl u8 | nameLen u8 | name UTF-8 (max 32 bytes) ----

const HELLO_VERSION = 1;
const HELLO_MAX_NAME = 32;
const Transports = Object.freeze({ LAN: 1, BT: 2, AWARE: 4 });

/** Encodes a hello; the name is cut to 32 UTF-8 bytes on a character boundary, as the app does. */
function encodeHello({ name, transports, ttl }) {
  const bytes = utf8Prefix(name, HELLO_MAX_NAME);
  const h = Buffer.alloc(4 + bytes.length);
  h[0] = HELLO_VERSION;
  h[1] = transports & 0xff;
  h[2] = clampByte(ttl);
  h[3] = bytes.length;
  bytes.copy(h, 4);
  return h;
}

// Control characters (C0, DEL, C1) are stripped from a decoded name so it stays one line on screen.
const isControl = (c) => { const n = c.codePointAt(0); return n < 0x20 || (n >= 0x7f && n <= 0x9f); };

/** Decodes a hello payload; null for anything off the contract. */
function decodeHello(p) {
  if (!Buffer.isBuffer(p) || p.length < 4 || p[0] !== HELLO_VERSION) return null;
  const nameLen = p[3];
  if (nameLen > HELLO_MAX_NAME || p.length !== 4 + nameLen) return null;
  let name;
  try {
    name = new TextDecoder("utf-8", { fatal: true }).decode(p.subarray(4));
  } catch {
    return null;
  }
  return { name: Array.from(name).filter((c) => !isControl(c)).join(""), transports: p[1], ttl: p[2] };
}

function utf8Prefix(s, max) {
  let str = String(s);
  let b = Buffer.from(str, "utf8");
  while (b.length > max) {
    str = str.slice(0, -1);
    b = Buffer.from(str, "utf8");
  }
  return b;
}

module.exports = {
  HEADER, VERSION, MAX_SIZE, Codec, Transports, HELLO_MAX_NAME,
  encodeHeader, parseHeader, aadOf, encodeHello, decodeHello,
};
