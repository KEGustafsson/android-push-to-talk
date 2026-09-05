// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const P = require("../lib/packet");

test("header round-trips with big-endian ids and the codec, ttl and hops bytes", () => {
  const h = P.encodeHeader({ senderId: -123456789, seq: 42, codec: P.Codec.OPUS, ttl: 3 });
  assert.equal(h.length, P.HEADER);
  assert.deepEqual([...h.subarray(0, 6)], [0x50, 0x54, 3, 1, 3, 3]);
  const parsed = P.parseHeader(Buffer.concat([h, Buffer.alloc(1)]));
  assert.deepEqual(parsed, { codec: 1, ttl: 3, hops: 3, senderId: -123456789, seq: 42 });
});

test("hops defaults to the ttl and both are clamped to a byte", () => {
  const h = P.encodeHeader({ senderId: 1, seq: 1, codec: 0, ttl: 300 });
  assert.equal(h[4], 255);
  assert.equal(h[5], 255);
  const h2 = P.encodeHeader({ senderId: 1, seq: 1, codec: 0, ttl: 2, hops: 6 });
  assert.equal(h2[4], 2);
  assert.equal(h2[5], 6);
});

test("parse rejects the wrong magic, version, codec, an empty payload and oversize", () => {
  const ok = Buffer.concat([P.encodeHeader({ senderId: 1, seq: 1, codec: 0, ttl: 1 }), Buffer.alloc(1)]);
  assert.ok(P.parseHeader(ok));
  assert.equal(P.parseHeader(ok.subarray(0, P.HEADER)), null);
  for (const [i, v] of [[0, 0x51], [2, 2], [3, 9]]) {
    const bad = Buffer.from(ok);
    bad[i] = v;
    assert.equal(P.parseHeader(bad), null, `byte ${i}`);
  }
  assert.equal(P.parseHeader(Buffer.alloc(P.MAX_SIZE + 1, 0x50)), null);
});

test("the AAD is the header with the ttl zeroed and nothing else", () => {
  const p = Buffer.concat([P.encodeHeader({ senderId: 7, seq: 9, codec: 0, ttl: 5, hops: 6 }), Buffer.alloc(3, 0xaa)]);
  const aad = P.aadOf(p);
  assert.equal(aad.length, P.HEADER);
  assert.equal(aad[4], 0);
  assert.equal(aad[5], 6);
  assert.equal(p[4], 5, "the packet itself is untouched");
});

test("hello encodes and decodes, cutting the name to 32 UTF-8 bytes on a character boundary", () => {
  const h = P.encodeHello({ name: "Arabella", transports: P.Transports.LAN, ttl: 4 });
  assert.deepEqual(P.decodeHello(h), { name: "Arabella", transports: 1, ttl: 4 });
  const aUmlaut = String.fromCharCode(0xe4); // two UTF-8 bytes
  const long = P.encodeHello({ name: aUmlaut.repeat(40), transports: 7, ttl: 1 });
  assert.equal(long.length, 4 + 32);
  assert.equal(P.decodeHello(long).name, aUmlaut.repeat(16));
  const euro = String.fromCharCode(0x20ac); // three UTF-8 bytes: 32 is not a multiple, so 30 bytes
  assert.equal(P.encodeHello({ name: euro.repeat(20), transports: 1, ttl: 1 }).length, 4 + 30);
});

test("hello rejects a bad version, a name over 32 bytes, trailing bytes and invalid UTF-8; strips controls", () => {
  const good = P.encodeHello({ name: "x", transports: 1, ttl: 1 });
  assert.equal(P.decodeHello(Buffer.concat([good, Buffer.alloc(1)])), null);
  assert.equal(P.decodeHello(Buffer.from([2, 1, 1, 1, 0x78])), null);
  assert.equal(P.decodeHello(Buffer.from([1, 1, 1, 33, ...Buffer.alloc(33, 0x78)])), null);
  assert.equal(P.decodeHello(Buffer.from([1, 1, 1, 1, 0xff])), null);
  const ctl = P.decodeHello(Buffer.from([1, 1, 1, 3, 0x61, 0x07, 0x62]));
  assert.equal(ctl.name, "ab");
});
