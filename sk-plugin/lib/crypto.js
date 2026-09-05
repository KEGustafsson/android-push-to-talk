// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The channel's AEAD, byte-compatible with the app's ChannelCrypto.kt: AES-256-GCM under a key
 * derived from the crew's channel key by PBKDF2-HMAC-SHA256 with a fixed application salt, a
 * random 96-bit nonce per packet prepended to the ciphertext, and a 128-bit tag after it.
 */

const crypto = require("node:crypto");

const NONCE_BYTES = 12;
const TAG_BYTES = 16;
const OVERHEAD = NONCE_BYTES + TAG_BYTES;
const ITERATIONS = 64_000;
const SALT = Buffer.from("CrewRadio channel key v3", "utf8");

/** The AES-256 key for a channel key; deterministic, so every phone with the same key agrees. */
function deriveKey(channelKey) {
  return crypto.pbkdf2Sync(Buffer.from(String(channelKey), "utf8"), SALT, ITERATIONS, 32, "sha256");
}

class ChannelCrypto {
  /** @param {Buffer} key 32 bytes, from deriveKey */
  constructor(key) {
    if (!Buffer.isBuffer(key) || key.length !== 32) throw new Error("ChannelCrypto: key must be 32 bytes");
    this.key = key;
  }

  static forChannelKey(channelKey) {
    return new ChannelCrypto(deriveKey(channelKey));
  }

  /** nonce | ciphertext | tag for `plain` under `aad`. `nonce` is only for tests; production draws a fresh one. */
  seal(aad, plain, nonce = crypto.randomBytes(NONCE_BYTES)) {
    const c = crypto.createCipheriv("aes-256-gcm", this.key, nonce, { authTagLength: TAG_BYTES });
    c.setAAD(aad);
    const body = Buffer.concat([c.update(plain), c.final()]);
    return Buffer.concat([nonce, body, c.getAuthTag()]);
  }

  /** The plaintext, or null when the packet is not authentic under `aad` (never throws). */
  open(aad, sealed) {
    if (!Buffer.isBuffer(sealed) || sealed.length < OVERHEAD) return null;
    try {
      const d = crypto.createDecipheriv("aes-256-gcm", this.key, sealed.subarray(0, NONCE_BYTES), { authTagLength: TAG_BYTES });
      d.setAAD(aad);
      d.setAuthTag(sealed.subarray(sealed.length - TAG_BYTES));
      return Buffer.concat([d.update(sealed.subarray(NONCE_BYTES, sealed.length - TAG_BYTES)), d.final()]);
    } catch {
      return null;
    }
  }
}

module.exports = { ChannelCrypto, deriveKey, NONCE_BYTES, TAG_BYTES, OVERHEAD, ITERATIONS, SALT };
