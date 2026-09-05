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

test("two nodes see each other by hello, duplicates are dropped, and silence drops a node", async () => {
  const m = new Medium();
  let clock = 1_000_000;
  const now = () => clock;
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000, silenceMs: 4000, now });
  const b = new ChannelNode({ name: "Anna", crypto, link: m.link(), heartbeatMs: 100000, silenceMs: 4000, now });
  a.start(); b.start();
  await tick();
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
  await tick();
  assert.deepEqual(a.roster(), []);
  assert.equal(a.stats.rejected, 1);
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
  await tick();
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
  await tick();
  assert.deepEqual(talking, [true]);
  assert.ok(b.roster()[0].talking);
  a.stop(); b.stop();
});

test("waitForSilence resolves at once when nobody talks, and after the gap when someone did", async () => {
  const m = new Medium();
  let clock = 0;
  const a = new ChannelNode({ name: "Boat", crypto, link: m.link(), heartbeatMs: 100000, now: () => clock });
  a.start();
  assert.equal(await a.waitForSilence(300, 1000), true);
  a.nodes.set(1, { name: "x", transports: 1, hops: 0, lastSeen: 0, lastAudio: 0 });
  clock = 100; // talked 100 ms ago
  const p = a.waitForSilence(300, 5000);
  setTimeout(() => (clock = 500), 60);
  assert.equal(await p, true);
  a.stop();
});
