// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { ChannelCrypto, deriveKey, OVERHEAD, NONCE_BYTES } = require("../lib/crypto");
const P = require("../lib/packet");

const crypto = ChannelCrypto.forChannelKey("north-star-2026");
const aad = P.encodeHeader({ senderId: 7, seq: 42, codec: P.Codec.OPUS, ttl: 0 });
const plain = Buffer.from(Array.from({ length: 60 }, (_, i) => (i * 7) & 0xff));

test("seals and opens, with the documented overhead", () => {
  const sealed = crypto.seal(aad, plain);
  assert.equal(sealed.length, plain.length + OVERHEAD);
  assert.deepEqual(crypto.open(aad, sealed), plain);
});

test("every packet gets its own nonce and ciphertext", () => {
  const a = crypto.seal(aad, plain);
  const b = crypto.seal(aad, plain);
  assert.notDeepEqual(a, b);
  assert.notDeepEqual(a.subarray(0, NONCE_BYTES), b.subarray(0, NONCE_BYTES));
});

test("a flipped bit anywhere, a different header or a different key fails, and nothing throws", () => {
  const sealed = crypto.seal(aad, plain);
  for (const i of [0, NONCE_BYTES, sealed.length >> 1, sealed.length - 1]) {
    const bad = Buffer.from(sealed);
    bad[i] ^= 1;
    assert.equal(crypto.open(aad, bad), null, `byte ${i}`);
  }
  const otherSender = Buffer.from(aad);
  otherSender[6] ^= 1;
  assert.equal(crypto.open(otherSender, sealed), null);
  assert.equal(ChannelCrypto.forChannelKey("north-star-2027").open(aad, sealed), null);
  assert.equal(crypto.open(aad, sealed.subarray(0, OVERHEAD)), null);
  assert.equal(crypto.open(aad, Buffer.alloc(0)), null);
});

test("key derivation is deterministic and keyed", () => {
  assert.deepEqual(deriveKey("abcd-efgh-jkmn"), deriveKey("abcd-efgh-jkmn"));
  assert.notDeepEqual(deriveKey("abcd-efgh-jkmn"), deriveKey("abcd-efgh-jkmp"));
});

// The vector below is checked by the Android app's own unit tests as well
// (app/src/test/java/fi/crewradio/CrossLanguageVectorTest.kt), so the two implementations
// are held to the same bytes: same PBKDF2 parameters, same AAD rule, same AES-GCM layout.
test("cross-language vector: a packet sealed here opens on the phone, and vice versa", () => {
  const vector = require("./vector.json");
  const key = ChannelCrypto.forChannelKey(vector.channelKey);
  assert.equal(deriveKey(vector.channelKey).toString("hex"), vector.derivedKeyHex);
  const packet = Buffer.from(vector.packetHex, "hex");
  const header = P.parseHeader(packet);
  assert.deepEqual(header, vector.header);
  const opened = key.open(P.aadOf(packet), packet.subarray(P.HEADER));
  assert.equal(opened.toString("hex"), vector.plainHex);
  // and sealing with the vector's nonce reproduces the packet byte for byte
  const resealed = key.seal(P.aadOf(packet), opened, Buffer.from(vector.nonceHex, "hex"));
  assert.equal(Buffer.concat([packet.subarray(0, P.HEADER), resealed]).toString("hex"), vector.packetHex);
});
