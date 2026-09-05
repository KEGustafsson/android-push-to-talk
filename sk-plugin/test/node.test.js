// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { ChannelNode, FRAME_BYTES } = require("../lib/node");
const { ChannelCrypto } = require("../lib/crypto");
const P = require("../lib/packet");

/** A shared medium: every node's send is every other node's 'packet', twice (multicast + broadcast). */
class Medium {
  constructor() { this.links = []; }
  link() {
    const l = new EventEmitter();
    l.send = (buf) => {
      for (const other of this.links) for (let i = 0; i < 2; i++) setImmediate(() => other.emit("packet", Buffer.from(buf)));
      return true;
    };
    this.links.push(l);
    return l;
  }
}

const crypto = ChannelCrypto.forChannelKey("north-star-2026");
const tick = (ms = 5) => new Promise((r) => setTimeout(r, ms));
/** Polls until `cond()` holds (the test files run in parallel, so fixed waits are flaky). */
async function until(cond, timeoutMs = 2000) {
  const t0 = Date.now();
  while (!cond()) {
    if (Date.now() - t0 > timeoutMs) throw new Error("condition not met in time");
    await tick(5);
  }
}

test("two nodes see each other by hello, duplicates are dropped, and silence drops a node", async () => {
  const m = new Medium();
  let clock = 1_000_000;
  const now = () => clock;
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000, silenceMs: 4000, now });
  const b = new ChannelNode({ name: "Anna", crypto, link: m.link(), heartbeatMs: 100000, silenceMs: 4000, now });
  a.start(); b.start();
  await until(() => a.roster().length === 1 && b.roster().length === 1);
  await tick(20); // the broadcast twins arrive too
  assert.deepEqual(a.roster().map((n) => n.name), ["Anna"]);
  assert.deepEqual(b.roster().map((n) => n.name), ["Boat"]);
  assert.equal(a.stats.rx, 1, "the broadcast twin was dropped as a duplicate");
  clock += 5000;
  a.tick();
  assert.deepEqual(a.roster(), [], "Anna silent for 5 s is gone");
  a.stop(); b.stop();
});

test("a node with a different channel key is rejected, not listed", async () => {
  const m = new Medium();
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000 });
  const x = new ChannelNode({ name: "Intruder", crypto: ChannelCrypto.forChannelKey("wrong"), link: m.link(), heartbeatMs: 100000 });
  a.start(); x.start();
  await until(() => a.stats.rejected >= 2);
  assert.deepEqual(a.roster(), []);
  assert.equal(a.stats.rejected, 2, "both copies fail authentication; a forged header cannot occupy a seen-slot");
  a.stop(); x.stop();
});

test("speaking sends 640-byte PCM frames at 20 ms, pads the last one, and the listener hears talking", async () => {
  const m = new Medium();
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000 });
  const b = new ChannelNode({ name: "Anna", crypto, link: m.link(), heartbeatMs: 100000 });
  const sent = [];
  const origSend = a.link.send;
  a.link.send = (buf) => { sent.push(Buffer.from(buf)); return origSend(buf); };
  a.start(); b.start();
  await until(() => a.roster().length === 1 && b.roster().length === 1);
  const talking = [];
  b.on("talking", (on) => talking.push(on));
  const t0 = Date.now();
  await a.speak(Buffer.alloc(FRAME_BYTES * 4 + 100, 7));
  const elapsed = Date.now() - t0;
  const frames = sent.map((p) => P.parseHeader(p)).filter((h) => h && h.codec === P.Codec.PCM);
  assert.equal(frames.length, 5);
  assert.deepEqual(frames.map((h) => h.seq), [0, 1, 2, 3, 4]);
  assert.ok(elapsed >= 80 && elapsed < 400, `paced: ${elapsed} ms`);
  const lastPlain = crypto.open(P.aadOf(sent.at(-1)), sent.at(-1).subarray(P.HEADER));
  assert.equal(lastPlain.length, FRAME_BYTES);
  assert.equal(lastPlain[99], 7);
  assert.equal(lastPlain[100], 0, "padded with silence");
  await until(() => talking.length > 0);
  assert.deepEqual(talking, [true]);
  assert.ok(b.roster()[0].talking);
  a.stop(); b.stop();
});

test("waitForSilence: at once when nobody ever talked, after the gap when someone did, false at the deadline", async () => {
  const m = new Medium();
  let clock = 1000;
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000, now: () => clock });
  a.start();
  assert.equal(await a.waitForSilence(300, 1000), true);
  a.nodes.set(1, { name: "x", transports: 1, hops: 0, lastSeen: 1000, lastAudio: 900 }); // talked 100 ms ago
  let settled = false;
  const p = a.waitForSilence(300, 5000).then((v) => { settled = true; return v; });
  await tick(20);
  assert.equal(settled, false, "still inside the gap");
  clock = 1400; // 500 ms since the last audio
  assert.equal(await p, true);
  a.nodes.get(1).lastAudio = 1400;
  const late = a.waitForSilence(300, 200); // keeps talking: gives up at the deadline (1600)
  await tick(60);
  clock = 1650; // 250 ms since the audio: still inside the gap, but past the deadline
  assert.equal(await late, false);
  a.stop();
});

test("two announcements queued at once go out one after the other, never interleaved", async () => {
  const m = new Medium();
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000 });
  const order = [];
  const origSend = a.link.send;
  a.link.send = (buf) => {
    const h = P.parseHeader(buf);
    if (h && h.codec === P.Codec.PCM) order.push(crypto.open(P.aadOf(buf), buf.subarray(P.HEADER))[0]);
    return origSend(buf);
  };
  a.start();
  const first = a.speak(Buffer.alloc(FRAME_BYTES * 3, 1));
  const second = a.speak(Buffer.alloc(FRAME_BYTES * 3, 2));
  const third = a.speak(Buffer.alloc(FRAME_BYTES * 2, 3));
  await Promise.all([first, second, third]);
  assert.deepEqual(order, [1, 1, 1, 2, 2, 2, 3, 3]);
  a.stop();
});
