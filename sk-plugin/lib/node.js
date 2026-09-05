// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * One node on the crew channel, as a phone is: a random sender id, a hello every second, a
 * roster kept from everyone else's hellos and audio, duplicates dropped by (sender, seq), and
 * the ability to key the channel with 20 ms PCM frames, paced in real time so the phones'
 * jitter queues see a talker, not a burst.
 *
 * The link is anything with `send(buf)` and a 'packet' event (see lan.js). Events out:
 * 'roster' (when the rendered list changes), 'talking' (someone else started or stopped).
 */

const crypto = require("node:crypto");
const { EventEmitter } = require("node:events");
const P = require("./packet");

const FRAME_BYTES = 640; // 16 kHz * 20 ms * 2 bytes
const FRAME_MS = 20;
const SEEN_CAPACITY = 4096;

class ChannelNode extends EventEmitter {
  /**
   * @param {object} opts
   * @param {string} opts.name        shown on the phones' roster (32 UTF-8 bytes at most)
   * @param {import('./crypto').ChannelCrypto} opts.crypto
   * @param {{send(buf:Buffer):boolean, on(ev:string, fn:Function):any}} opts.link
   * @param {number} [opts.ttl=4]     hop budget stamped on our packets
   * @param {number} [opts.heartbeatMs=1000]
   * @param {number} [opts.silenceMs=4000]  a node silent this long is dropped from the roster
   * @param {number} [opts.talkingMs=500]   audio within this long ago means "talking"
   * @param {() => number} [opts.now]
   */
  constructor(opts) {
    super();
    this.name = opts.name;
    this.crypto = opts.crypto;
    this.link = opts.link;
    this.ttl = opts.ttl ?? 4;
    this.heartbeatMs = opts.heartbeatMs ?? 1000;
    this.silenceMs = opts.silenceMs ?? 4000;
    this.talkingMs = opts.talkingMs ?? 500;
    this.now = opts.now ?? Date.now;
    this.senderId = randomSenderId();
    this.audioSeq = 0;
    this.helloSeq = 0;
    this.seen = new Map(); // key -> true, insertion ordered, bounded
    this.nodes = new Map(); // senderId -> {name, transports, hops, lastSeen, lastAudio}
    this.timer = null;
    this.speaking = null; // {cancel, done} of the announcement going out right now
    this.chain = Promise.resolve(); // announcements go out one after another, in call order
    this.lastRosterKey = "";
    this.stats = { rx: 0, rejected: 0, tx: 0 };
    this.onPacket = (buf) => this.receive(buf);
  }

  start() {
    this.link.on("packet", this.onPacket);
    this.tick();
    this.timer = setInterval(() => this.tick(), this.heartbeatMs);
    if (this.timer.unref) this.timer.unref();
  }

  stop() {
    if (this.timer) clearInterval(this.timer);
    this.timer = null;
    this.cancel();
    if (typeof this.link.off === "function") this.link.off("packet", this.onPacket);
    this.nodes.clear();
    this.seen.clear();
  }

  /** Hello out, stale nodes swept, roster republished if it changed. */
  tick() {
    this.broadcast(P.Codec.HELLO, P.encodeHello({ name: this.name, transports: P.Transports.LAN, ttl: this.ttl }));
    const now = this.now();
    let changed = false;
    for (const [id, n] of this.nodes) {
      if (now - n.lastSeen > this.silenceMs) {
        this.nodes.delete(id);
        changed = true;
      }
    }
    this.publishRoster(changed);
  }

  /** The roster as the phones would list it. */
  roster() {
    const now = this.now();
    return [...this.nodes.entries()]
      .map(([id, n]) => ({
        id,
        name: n.name ?? `#${(id >>> 0).toString(16)}`,
        transports: n.transports,
        hops: n.hops,
        talking: now - n.lastAudio < this.talkingMs,
        ageMs: now - n.lastSeen,
      }))
      .sort((a, b) => a.name.localeCompare(b.name));
  }

  /** True while any other node is sending audio. */
  get someoneTalking() {
    const now = this.now();
    for (const n of this.nodes.values()) if (now - n.lastAudio < this.talkingMs) return true;
    return false;
  }

  /** Resolves when nobody has talked for `gapMs`, or after `maxWaitMs` regardless. */
  waitForSilence(gapMs = 300, maxWaitMs = 3000) {
    const deadline = this.now() + maxWaitMs;
    return new Promise((resolve) => {
      const check = () => {
        const now = this.now();
        let last = 0;
        for (const n of this.nodes.values()) last = Math.max(last, n.lastAudio);
        if (last === 0 || now - last >= gapMs) resolve(true);      // nobody has talked, or not lately
        else if (now >= deadline) resolve(false);
        else setTimeout(check, 50);
      };
      check();
    });
  }

  /**
   * Keys the channel with 16 kHz mono PCM16 (a Buffer of little-endian bytes), one frame every
   * 20 ms. Resolves when the last frame has gone out. Calls queue behind each other in call
   * order (a promise chain, so two waiters can never both start). cancel() stops the current one.
   */
  speak(pcmBytes) {
    const turn = this.chain.then(() => this.sendFrames(pcmBytes));
    this.chain = turn.catch(() => {});
    return turn;
  }

  async sendFrames(pcmBytes) {
    const frames = [];
    for (let off = 0; off < pcmBytes.length; off += FRAME_BYTES) {
      const f = Buffer.alloc(FRAME_BYTES); // the last frame is padded with silence
      pcmBytes.copy(f, 0, off, Math.min(off + FRAME_BYTES, pcmBytes.length));
      frames.push(f);
    }
    if (frames.length === 0) return;
    let cancelled = false;
    let resolveDone;
    const done = new Promise((r) => (resolveDone = r));
    this.speaking = { cancel: () => (cancelled = true), done };
    this.emit("speaking", true);
    try {
      const t0 = this.now();
      for (let i = 0; i < frames.length && !cancelled; i++) {
        this.broadcast(P.Codec.PCM, frames[i]);
        const due = t0 + (i + 1) * FRAME_MS;
        const wait = due - this.now();
        if (wait > 0) await sleep(wait);
      }
    } finally {
      this.speaking = null;
      this.emit("speaking", false);
      resolveDone();
    }
  }

  cancel() {
    this.speaking?.cancel();
  }

  /** Stamps, seals and sends one of our own packets. */
  broadcast(codec, payload) {
    const seq = codec === P.Codec.HELLO ? this.helloSeq++ : this.audioSeq++;
    const header = P.encodeHeader({ senderId: this.senderId, seq, codec, ttl: this.ttl });
    this.markSeen(this.senderId, seq, codec);
    const packet = Buffer.concat([header, this.crypto.seal(P.aadOf(header), payload)]);
    this.stats.tx++;
    this.link.send(packet);
  }

  /** The receive path: parse, drop our own and duplicates, authenticate, then roster or talking. */
  receive(buf) {
    const h = P.parseHeader(buf);
    if (!h) { this.stats.rejected++; return; }
    if (h.senderId === this.senderId) return;
    // Authenticate first, then dedupe, as the app does: a forged header must not be able to
    // occupy a (sender, seq) slot and get the authentic packet dropped as its duplicate.
    const plain = this.crypto.open(P.aadOf(buf), buf.subarray(P.HEADER));
    if (!plain) { this.stats.rejected++; return; }
    if (!this.markSeen(h.senderId, h.seq, h.codec)) return; // the broadcast twin, or a relay echo
    this.stats.rx++;
    const now = this.now();
    let n = this.nodes.get(h.senderId);
    const fresh = !n;
    if (fresh) {
      n = { name: null, transports: 0, hops: 0, lastSeen: now, lastAudio: 0 };
      this.nodes.set(h.senderId, n);
    }
    n.lastSeen = now;
    if (h.codec === P.Codec.HELLO) {
      const hello = P.decodeHello(plain);
      if (hello) {
        const hops = Math.max(0, hello.ttl - h.ttl);
        if (hello.name !== n.name || hello.transports !== n.transports || hops !== n.hops) {
          n.name = hello.name;
          n.transports = hello.transports;
          n.hops = hops;
          this.publishRoster(true);
          return;
        }
      }
    } else {
      const was = now - n.lastAudio < this.talkingMs;
      n.lastAudio = now;
      if (!was) {
        this.emit("talking", true, h.senderId);
        this.publishRoster(true);
        return;
      }
    }
    this.publishRoster(fresh);
  }

  markSeen(senderId, seq, codec) {
    const key = `${codec === P.Codec.HELLO ? "h" : "a"}${senderId}:${seq}`;
    if (this.seen.has(key)) return false;
    this.seen.set(key, true);
    if (this.seen.size > SEEN_CAPACITY) this.seen.delete(this.seen.keys().next().value);
    return true;
  }

  publishRoster(force) {
    const r = this.roster();
    const key = r.map((n) => `${n.id}|${n.name}|${n.transports}|${n.hops}|${n.talking ? 1 : 0}`).join("\n");
    if (force || key !== this.lastRosterKey) {
      this.lastRosterKey = key;
      this.emit("roster", r);
    }
  }
}

function randomSenderId() {
  for (;;) {
    const id = crypto.randomBytes(4).readInt32BE(0);
    if (id !== 0) return id;
  }
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

module.exports = { ChannelNode, FRAME_BYTES, FRAME_MS };
