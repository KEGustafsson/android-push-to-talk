// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const net = require("node:net");
const { EventEmitter } = require("node:events");
const plugin = require("../index");
const { ChannelCrypto } = require("../lib/crypto");
const { encodeEvent, EventDecoder } = require("../lib/wyoming");
const P = require("../lib/packet");

const KEY = "north-star-2026";
const crypto = ChannelCrypto.forChannelKey(KEY);

/** A fake WLAN link: records what the plugin sends, lets the test inject packets. */
class FakeLink extends EventEmitter {
  constructor(opts) {
    super();
    this.opts = opts;
    this.sent = [];
    this.closed = false;
    FakeLink.last = this;
  }
  open() {
    if (FakeLink.failOpen) return Promise.reject(new Error("no usable IPv4 interface"));
    return Promise.resolve({ iface: "fake0", address: "10.0.0.2", broadcast: "10.0.0.255" });
  }
  send(buf) { this.sent.push(Buffer.from(buf)); return true; }
  close() { this.closed = true; }
}

/** The subset of the Signal K plugin API the plugin uses, with everything recorded. */
function fakeApp() {
  const app = {
    log: [], errors: [], status: [], deltas: [], props: {}, subs: [], puts: [],
    debug: (m) => app.log.push(m),
    error: (m) => app.errors.push(m),
    setPluginStatus: (m) => app.status.push(m),
    setPluginError: (m) => app.errors.push(`plugin error: ${m}`),
    handleMessage: (id, delta) => app.deltas.push({ id, delta }),
    getSelfPath: (p) => (p === "name" ? "Arabella" : undefined),
    onPropertyValues: (name, cb) => { app.props[name] = cb; return () => { delete app.props[name]; }; },
    subscriptionmanager: { subscribe: (sub, unsubs, onErr, cb) => { app.subs.push({ sub, cb }); unsubs.push(() => app.subs.pop()); } },
  };
  return app;
}

const flush = (n = 3) => new Promise((r) => { let i = 0; const step = () => (++i >= n ? r() : setImmediate(step)); setImmediate(step); });
const until = async (cond, ms = 3000) => { const t0 = Date.now(); while (!cond()) { if (Date.now() - t0 > ms) throw new Error("timeout"); await new Promise((r) => setTimeout(r, 5)); } };

function lastValue(app, path) {
  for (let i = app.deltas.length - 1; i >= 0; i--) {
    for (const v of app.deltas[i].delta.updates[0].values) if (v.path === path) return v.value;
  }
  return undefined;
}

test("metadata: id, schema with the key required, password widget, vessel name as the default", () => {
  const app = fakeApp();
  const p = plugin(app);
  assert.equal(p.id, "signalk-crewradio");
  const schema = p.schema();
  assert.deepEqual(schema.required, ["channelKey"]);
  assert.match(schema.properties.nodeName.description, /Arabella/);
  assert.equal(schema.properties.satelliteHost.default, "127.0.0.1");
  assert.equal(p.uiSchema().channelKey["ui:widget"], "password");
});

test("without a channel key the plugin waits, says so in its status, and does nothing else", () => {
  const app = fakeApp();
  const p = plugin(app, { LanLink: FakeLink });
  p.start({});
  assert.match(app.status[0], /Waiting for the channel key/);
  assert.deepEqual(app.errors, []);
  assert.equal(FakeLink.last, undefined);
  p.stop();
  p.start(undefined);           // the server may pass nothing at all; restart must be as calm
  assert.deepEqual(app.errors, []);
  p.stop();
});

test("on start it joins the channel, sends hellos, publishes the roster and reports status; stop tears down", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, { LanLink: FakeLink });
  p.start({ channelKey: KEY, satellitePort: 0, hops: 3 });
  await p.satelliteListening;
  await until(() => FakeLink.last && FakeLink.last.sent.length > 0);
  const link = FakeLink.last;
  assert.deepEqual(link.opts, { group: "239.255.42.1", port: 47474, iface: "auto" });
  const h = P.parseHeader(link.sent[0]);
  assert.equal(h.codec, P.Codec.HELLO);
  assert.equal(h.ttl, 3);
  const hello = P.decodeHello(crypto.open(P.aadOf(link.sent[0]), link.sent[0].subarray(P.HEADER)));
  assert.equal(hello.name, "Arabella");
  assert.equal(lastValue(app, "communication.crewradio.online"), 0);
  assert.match(app.status.at(-1), /0 online/);
  assert.match(app.status.at(-1), /assistant not connected/);

  // a phone's hello arrives: roster and status follow
  const phoneHeader = P.encodeHeader({ senderId: 42, seq: 0, codec: P.Codec.HELLO, ttl: 4 });
  const phone = Buffer.concat([phoneHeader, crypto.seal(P.aadOf(phoneHeader), P.encodeHello({ name: "Anna", transports: 3, ttl: 4 }))]);
  link.emit("packet", phone, {});
  await flush();
  assert.equal(lastValue(app, "communication.crewradio.online"), 1);
  assert.deepEqual(lastValue(app, "communication.crewradio.nodes"), [{ name: "Anna", hops: 0, transports: 3, talking: false }]);
  assert.match(app.status.at(-1), /1 online/);

  p.stop();
  assert.equal(link.closed, true);
  assert.equal(app.status.at(-1), "Stopped");
  assert.equal(lastValue(app, "communication.crewradio.online"), 0);
});

test("an announcement from the orchestrator is resampled, chimed, keyed on the channel and acknowledged", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, { LanLink: FakeLink });
  p.start({ channelKey: KEY, satellitePort: 0, waitForSilenceMs: 0, nodeName: "Boat" });
  const addr = await p.satelliteListening;
  await until(() => FakeLink.last);
  const link = FakeLink.last;

  const sock = net.connect(addr.port, "127.0.0.1");
  await new Promise((r) => sock.once("connect", r));
  const dec = new EventDecoder();
  const got = [];
  sock.on("data", (b) => got.push(...dec.feed(b)));
  await until(() => app.status.some((s) => /assistant connected/.test(s)));
  sock.write(encodeEvent("describe"));
  await until(() => got.some((e) => e.type === "info"));
  assert.equal(got.find((e) => e.type === "info").data.satellite.name, "Boat");

  // 200 ms of a 1 kHz tone at 22 050 Hz, stereo
  const rate = 22050, n = rate / 5;
  const pcm = Buffer.alloc(n * 2 * 2);
  for (let i = 0; i < n; i++) { const v = Math.round(8000 * Math.sin((2 * Math.PI * 1000 * i) / rate)); pcm.writeInt16LE(v, i * 4); pcm.writeInt16LE(v, i * 4 + 2); }
  const fmt = { rate, width: 2, channels: 2 };
  sock.write(encodeEvent("audio-start", fmt));
  sock.write(encodeEvent("audio-chunk", fmt, pcm));
  sock.write(encodeEvent("audio-stop"));
  await until(() => got.some((e) => e.type === "played"), 10000);

  const frames = link.sent.map((b) => P.parseHeader(b)).filter((h) => h.codec === P.Codec.PCM);
  // chime (~460 ms) + 200 ms tone + 150 ms tail, in 20 ms frames
  assert.ok(frames.length >= 38 && frames.length <= 44, `${frames.length} frames`);
  assert.deepEqual(frames.map((h) => h.seq), frames.map((_, i) => i));
  assert.ok(app.status.some((s) => /announcing/.test(s)));
  sock.destroy();
  p.stop();
});

test("the notification bridge speaks through signalk-wyoming's say(), urgent for an emergency, to the crewradio target", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, { LanLink: FakeLink });
  p.start({ channelKey: KEY, satellitePort: 0, bridge: { repeatSec: 0 } });
  await p.satelliteListening;
  assert.equal(app.subs.length, 1);
  assert.equal(app.subs[0].sub.subscribe[0].path, "notifications.*");
  const said = [];
  app.props["signalk-wyoming.api"]([{ value: { version: 1, say: async (o) => { said.push(o); return { ok: true, queued: ["crewradio"] }; } } }]);
  await flush();
  assert.ok(!/say\(\) unavailable/.test(app.status.at(-1)));
  app.subs[0].cb({ updates: [{ values: [{ path: "notifications.mob", value: { state: "emergency", method: ["sound", "visual"], message: "Man overboard" } }] }] });
  await flush();
  assert.deepEqual(said, [{ text: "Man overboard", priority: "urgent", targets: ["crewradio"] }]);
  app.subs[0].cb({ updates: [{ values: [{ path: "notifications.navigation.depth", value: { state: "warn", method: ["sound"], message: "shallow" } }] }] });
  await flush();
  assert.equal(said.length, 1, "warn is below the default alarm threshold");
  p.stop();
  assert.equal(app.subs.length, 0, "unsubscribed");
});

test("a WLAN link that cannot open is reported and retried; the satellite still runs", async () => {
  FakeLink.last = undefined;
  FakeLink.failOpen = true;
  const app = fakeApp();
  const p = plugin(app, { LanLink: FakeLink });
  try {
    p.start({ channelKey: KEY, satellitePort: 0 });
    await p.satelliteListening;
    await flush();
    assert.match(app.errors.at(-1), /no usable IPv4 interface/);
    assert.match(app.status.at(-1) ?? "", /WLAN link down|assistant/);
  } finally {
    FakeLink.failOpen = false;
    p.stop();
  }
});

test("a non-loopback satellite bind without an allowlist falls back to loopback and says so", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, { LanLink: FakeLink });
  p.start({ channelKey: KEY, satellitePort: 0, satelliteHost: "0.0.0.0" });
  const addr = await p.satelliteListening;
  assert.equal(addr.address, "127.0.0.1");
  assert.ok(app.errors.some((e) => /needs an allowlist/.test(e)));
  p.stop();
});

test("a non-loopback satellite bind with an allowlist is honoured, and the list reaches the satellite", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, { LanLink: FakeLink });
  p.start({ channelKey: KEY, satellitePort: 0, satelliteHost: "0.0.0.0", satelliteAllowFrom: ["10.10.10.0/24", " "] });
  const addr = await p.satelliteListening;
  assert.equal(addr.address, "0.0.0.0");
  assert.deepEqual(app.errors, []);
  assert.ok(app.log.some((m) => /clients limited to 10\.10\.10\.0\/24/.test(m)));
  p.stop();
});
