// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const net = require("node:net");
const { encodeEvent, EventDecoder, SatelliteServer, MAX_DATA, MAX_PAYLOAD, MAX_ANNOUNCEMENT_MS, parseAllow, normaliseIp } = require("../lib/wyoming");

test("framing: header line, optional data block, optional payload, any split", () => {
  const payload = Buffer.from([1, 2, 3, 4, 5]);
  const a = encodeEvent("audio-chunk", { rate: 16000, width: 2, channels: 1 }, payload);
  const b = encodeEvent("ping", { text: null });
  const withData = Buffer.from('{"type":"info","data_length":13}\n{"tts":[1,2]}', "utf8");
  const all = Buffer.concat([a, b, withData]);
  for (const cut of [1, 7, 30, all.length - 1]) {
    const d = new EventDecoder();
    const events = [...d.feed(all.subarray(0, cut)), ...d.feed(all.subarray(cut))];
    assert.equal(events.length, 3, `cut at ${cut}`);
    assert.equal(events[0].type, "audio-chunk");
    assert.deepEqual(events[0].data, { rate: 16000, width: 2, channels: 1 });
    assert.deepEqual(events[0].payload, payload);
    assert.equal(events[1].type, "ping");
    assert.deepEqual(events[2].data, { tts: [1, 2] });
  }
});

test("framing rejects malformed headers and absurd lengths, and will not buffer without bound", () => {
  assert.throws(() => new EventDecoder().feed(Buffer.from("not json\n")));
  assert.throws(() => new EventDecoder().feed(Buffer.from('{"type":"x","payload_length":-1}\n')));
  assert.throws(() => new EventDecoder().feed(Buffer.from('{"type":"x","payload_length":99999999999}\n')));
  assert.throws(() => new EventDecoder().feed(Buffer.from(`{"type":"x","data_length":${MAX_DATA + 1}}\n`)), /data block/);
  assert.throws(() => new EventDecoder().feed(Buffer.from(`{"type":"x","payload_length":${MAX_PAYLOAD + 1}}\n`)), /payload/);
  assert.throws(() => new EventDecoder().feed(Buffer.alloc(70 * 1024, 0x20)), /header line/);
  // a declared-but-incomplete payload is held, and what can be held is bounded by the caps above
  const d = new EventDecoder();
  assert.deepEqual(d.feed(Buffer.from(`{"type":"x","payload_length":${MAX_PAYLOAD}}\n`)), []);
  assert.deepEqual(d.feed(Buffer.alloc(MAX_PAYLOAD - 1)), []);
  assert.ok(d.buf.length <= MAX_PAYLOAD + 64 * 1024);
  assert.equal(d.feed(Buffer.alloc(1)).length, 1, "completes once the last byte arrives");
});

/** A minimal orchestrator: connects, sends events, collects replies. */
function client(port) {
  const sock = net.connect(port, "127.0.0.1");
  const decoder = new EventDecoder();
  const received = [];
  const waiters = [];
  sock.on("data", (b) => {
    for (const ev of decoder.feed(b)) {
      received.push(ev);
      for (const w of waiters.splice(0)) w();
    }
  });
  const send = (type, data, payload) => sock.write(encodeEvent(type, data, payload));
  const next = (type, timeoutMs = 3000) => new Promise((resolve, reject) => {
    const t = setTimeout(() => reject(new Error(`no ${type}`)), timeoutMs);
    const check = () => {
      const i = received.findIndex((e) => e.type === type);
      if (i >= 0) { clearTimeout(t); resolve(received.splice(i, 1)[0]); return true; }
      return false;
    };
    if (!check()) waiters.push(check);
  });
  return { sock, send, next, ready: new Promise((r) => sock.on("connect", r)) };
}

test("satellite: describe gets info, ping gets pong, and an announcement is played then acknowledged", async () => {
  const played = [];
  let release;
  const gate = new Promise((r) => (release = r));
  const sat = new SatelliteServer({
    identity: { name: "Boat", version: "0.0.0" },
    play: async (audio) => { played.push(audio); await gate; },
  });
  const addr = await sat.listen(0, "127.0.0.1");
  try {
    const c = client(addr.port);
    await c.ready;
    c.send("describe");
    const info = await c.next("info");
    assert.equal(info.data.satellite.name, "Boat");
    assert.deepEqual(info.data.tts, []);
    c.send("ping", { text: "hi" });
    assert.equal((await c.next("pong")).data.text, "hi");
    c.send("pause-satellite");
    assert.equal(sat.mode, "pause");
    c.send("audio-start", { rate: 22050, width: 2, channels: 1, timestamp: 0 });
    c.send("audio-chunk", { rate: 22050, width: 2, channels: 1 }, Buffer.alloc(100, 1));
    c.send("audio-chunk", { rate: 22050, width: 2, channels: 1 }, Buffer.alloc(50, 2));
    c.send("audio-stop");
    await new Promise((r) => setTimeout(r, 50));
    assert.equal(played.length, 1);
    assert.equal(played[0].rate, 22050);
    assert.equal(Buffer.concat(played[0].chunks).length, 150);
    let ack = false;
    const playedP = c.next("played").then(() => (ack = true));
    await new Promise((r) => setTimeout(r, 30));
    assert.equal(ack, false, "played waits for the announcement to go out");
    release();
    await playedP;
    assert.equal(ack, true);
    c.sock.destroy();
  } finally {
    await sat.close();
  }
});

test("satellite: an announcement longer than the cap is dropped, not resampled", async () => {
  const played = [];
  const sat = new SatelliteServer({ identity: { name: "Boat" }, play: async (a) => { played.push(a); } });
  const addr = await sat.listen(0, "127.0.0.1");
  try {
    const c = client(addr.port);
    await c.ready;
    const f = { rate: 8000, width: 2, channels: 1 };
    const perSecond = 8000 * 2;
    c.send("audio-start", f);
    for (let s = 0; s < MAX_ANNOUNCEMENT_MS / 1000 + 2; s++) c.send("audio-chunk", f, Buffer.alloc(perSecond));
    c.send("audio-stop");
    await c.next("played");
    assert.equal(played.length, 0);
    c.send("audio-start", { rate: 96000, width: 2, channels: 1 });
    c.send("audio-chunk", { rate: 96000, width: 2, channels: 1 }, Buffer.alloc(10));
    c.send("audio-stop");
    await c.next("played");
    assert.equal(played.length, 0, "a rate outside 8-48 kHz is refused");
    c.sock.destroy();
  } finally {
    await sat.close();
  }
});

test("satellite allowlist: loopback always, listed addresses and IPv4 ranges, nothing else; malformed entries are ignored", () => {
  const sat = new SatelliteServer({ identity: { name: "Boat" }, play: async () => {}, allowFrom: ["10.10.10.0/24", "192.168.0.34", " ", "bad/99", "2001:db8::7"] });
  assert.equal(sat.allow.length, 3);
  for (const ok of ["127.0.0.1", "127.0.0.9", "::1", "::ffff:127.0.0.1", "10.10.10.77", "::ffff:10.10.10.1", "192.168.0.34", "2001:db8::7"]) assert.equal(sat.allowed(ok), true, ok);
  for (const no of ["10.10.11.1", "192.168.0.35", "fe80::1", "", undefined, "999.1.1.1"]) assert.equal(sat.allowed(no), false, String(no));
  const none = new SatelliteServer({ identity: { name: "Boat" }, play: async () => {} });
  assert.equal(none.allowed("127.0.0.1"), true);
  assert.equal(none.allowed("192.168.0.34"), false);
  assert.equal(parseAllow("0.0.0.0/0")("8.8.8.8"), true);
  assert.equal(parseAllow("10.0.0.0/33"), null);
  assert.equal(normaliseIp("::ffff:1.2.3.4"), "1.2.3.4");
});

test("satellite: a client outside the allowlist is dropped before it can send anything", async () => {
  // Every connection in a test comes from loopback, so the allowlist is exercised by narrowing what loopback means:
  const sat = new SatelliteServer({ identity: { name: "Boat" }, play: async () => { throw new Error("must not play"); } });
  sat.allowed = () => false;
  const addr = await sat.listen(0, "127.0.0.1");
  try {
    const c = client(addr.port);
    await c.ready;
    await new Promise((r) => c.sock.on("close", r));
    assert.equal(sat.connected, false);
  } finally {
    await sat.close();
  }
});

test("satellite: a new connection replaces the old one; unsupported audio is ignored but acknowledged", async () => {
  const sat = new SatelliteServer({ identity: { name: "Boat" }, play: async () => { throw new Error("must not play"); } });
  const addr = await sat.listen(0, "127.0.0.1");
  try {
    const first = client(addr.port);
    await first.ready;
    const closed = new Promise((r) => first.sock.on("close", r));
    const second = client(addr.port);
    await second.ready;
    await closed;
    second.send("audio-start", { rate: 22050, width: 1, channels: 1 });
    second.send("audio-chunk", { rate: 22050, width: 1, channels: 1 }, Buffer.alloc(10));
    second.send("audio-stop");
    await second.next("played");
    second.sock.destroy();
  } finally {
    await sat.close();
  }
});
