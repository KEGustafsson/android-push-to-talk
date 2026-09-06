// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const plugin = require("../index");
const { ChannelCrypto } = require("../lib/crypto");
const P = require("../lib/packet");

const KEY = "north-star-2026";
const crypto = ChannelCrypto.forChannelKey(KEY);

/** A fake network link: records what the plugin sends, lets the test inject packets. */
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

/** A fake speech engine: 100 ms of a tone per call, instantly; records what it was asked. */
class FakeTts {
  constructor(opts) { this.opts = opts; this.texts = []; FakeTts.last = this; }
  synthesize(text) {
    this.texts.push(text);
    const pcm = Buffer.alloc(3200);
    for (let i = 0; i < 1600; i++) pcm.writeInt16LE(Math.round(6000 * Math.sin(i / 3)), i * 2);
    return Promise.resolve(pcm);
  }
}

/** The subset of the Signal K plugin API the plugin uses, with everything recorded. */
function fakeApp() {
  const app = {
    log: [], errors: [], status: [], deltas: [], props: {}, subs: [], puts: {},
    debug: (m) => app.log.push(m),
    error: (m) => app.errors.push(m),
    setPluginStatus: (m) => app.status.push(m),
    setPluginError: (m) => app.errors.push(`plugin error: ${m}`),
    handleMessage: (id, delta) => app.deltas.push({ id, delta }),
    getSelfPath: (p) => (p === "name" ? "Sirius" : undefined),
    getDataDirPath: () => require("node:os").tmpdir(),
    emitPropertyValue: (name, value) => { app.props[name] = value; },
    registerPutHandler: (context, path, handler) => { app.puts[path] = handler; },
    subscriptionmanager: { subscribe: (sub, unsubs, onErr, cb) => { app.subs.push({ sub, cb }); unsubs.push(() => app.subs.pop()); } },
  };
  return app;
}

const flush = (n = 3) => new Promise((r) => { let i = 0; const step = () => (++i >= n ? r() : setImmediate(step)); setImmediate(step); });
const until = async (cond, ms = 3000) => { const t0 = Date.now(); while (!cond()) { if (Date.now() - t0 > ms) throw new Error("timeout"); await new Promise((r) => setTimeout(r, 5)); } };
const deps = { LanLink: FakeLink, Tts: FakeTts };

function lastValue(app, path) {
  for (let i = app.deltas.length - 1; i >= 0; i--) {
    for (const v of app.deltas[i].delta.updates[0].values) if (v.path === path) return v.value;
  }
  return undefined;
}

/** A fake Express router capturing the routes the plugin registers. */
function fakeRouter() {
  const routes = {};
  return { routes, post: (p, h) => { routes[`POST ${p}`] = h; }, get: (p, h) => { routes[`GET ${p}`] = h; } };
}
function fakeRes() {
  const res = { code: 200, body: null, status(c) { res.code = c; return res; }, json(b) { res.body = b; res.done?.(); return res; } };
  res.finished = new Promise((r) => (res.done = r));
  return res;
}

test("metadata: id, schema with the key required, the voices, password widget, vessel name as the default", () => {
  const app = fakeApp();
  const p = plugin(app);
  assert.equal(p.id, "signalk-crewradio");
  const schema = p.schema();
  assert.deepEqual(schema.required, ["channelKey"]);
  assert.deepEqual(schema.properties.voice.enum, ["slt", "kal16", "rms", "awb"]);
  assert.match(schema.properties.nodeName.description, /Sirius/);
  assert.equal(p.uiSchema().channelKey["ui:widget"], "password");
});

test("without a channel key the plugin waits, says so in its status, and does nothing else", () => {
  const app = fakeApp();
  const p = plugin(app, deps);
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
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, hops: 3 });
  await until(() => FakeLink.last && FakeLink.last.sent.length > 0);
  const link = FakeLink.last;
  assert.deepEqual(link.opts, { group: "239.255.42.1", port: 47474, iface: "auto" });
  const h = P.parseHeader(link.sent[0]);
  assert.equal(h.codec, P.Codec.HELLO);
  assert.equal(h.ttl, 3);
  const hello = P.decodeHello(crypto.open(P.aadOf(link.sent[0]), link.sent[0].subarray(P.HEADER)));
  assert.equal(hello.name, "Sirius");
  assert.equal(lastValue(app, "communication.crewradio.online"), 0);
  assert.match(app.status.at(-1), /0 online/);
  assert.match(app.status.at(-1), /voice slt/);

  const phoneHeader = P.encodeHeader({ senderId: 42, seq: 0, codec: P.Codec.HELLO, ttl: 4 });
  const phone = Buffer.concat([phoneHeader, crypto.seal(P.aadOf(phoneHeader), P.encodeHello({ name: "Anna", transports: 3, ttl: 4 }))]);
  link.emit("packet", phone, { address: "10.0.0.7" });
  await flush();
  assert.equal(lastValue(app, "communication.crewradio.online"), 1);
  assert.deepEqual(lastValue(app, "communication.crewradio.nodes"), [{ name: "Anna", hops: 0, transports: 3, talking: false }]);
  assert.match(app.status.at(-1), /1 online/);

  p.stop();
  assert.equal(link.closed, true);
  assert.equal(app.status.at(-1), "Stopped");
  assert.equal(lastValue(app, "communication.crewradio.online"), 0);
  assert.equal(app.props["signalk-crewradio.api"], null, "the in-process api is withdrawn");
});

test("say() through the in-process api: chime, speech, tail as paced frames on the channel; the speaking path follows", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0, nodeName: "Boat" });
  await until(() => FakeLink.last);
  const api = app.props["signalk-crewradio.api"];
  assert.equal(api.version, 1);
  const r = await api.say({ text: "Depth 2.5 m" });
  assert.equal(r.ok, true);
  assert.equal(r.queued, 0);
  assert.equal(r.priority, "normal");
  assert.deepEqual(FakeTts.last.texts, ["Depth 2.5 m"]);
  await until(() => lastValue(app, "communication.crewradio.speaking") === false && FakeLink.last.sent.length > 30, 5000);
  const frames = FakeLink.last.sent.map((b) => P.parseHeader(b)).filter((h) => h.codec === P.Codec.PCM);
  // chime (~460 ms) + 100 ms tone + 150 ms tail, in 20 ms frames
  assert.ok(frames.length >= 33 && frames.length <= 38, `${frames.length} frames`);
  assert.deepEqual(frames.map((h) => h.seq), frames.map((_, i) => i));
  assert.ok(app.status.some((s) => /announcing/.test(s)));
  await assert.rejects(api.say({ text: "" }), /text is required/);
  await assert.rejects(api.say({ text: "x".repeat(501) }), /over 500/);
  p.stop();
  await assert.rejects(api.say({ text: "late" }), /not running/);
});

test("say() through PUT and REST, plain text or {text, priority}; urgent goes first", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0 });
  await until(() => FakeLink.last);
  const put = app.puts["communication.crewradio.say"];
  assert.equal(typeof put, "function");
  const results = [];
  const pending = put("vessels.self", "communication.crewradio.say", "Hello crew", (r) => results.push(r));
  assert.equal(pending.state, "PENDING");
  await until(() => results.length === 1);
  assert.equal(results[0].statusCode, 200);
  assert.equal(JSON.parse(results[0].message).ok, true);
  put("vessels.self", "communication.crewradio.say", { text: "" }, (r) => results.push(r));
  await until(() => results.length === 2);
  assert.equal(results[1].statusCode, 400);

  const router = fakeRouter();
  p.registerWithRouter(router);
  const res1 = fakeRes();
  router.routes["POST /say"]({ body: { text: "Man overboard", priority: "urgent" } }, res1);
  await res1.finished;
  assert.equal(res1.code, 200);
  assert.equal(res1.body.priority, "urgent");
  assert.equal(res1.body.queued, 0, "urgent: ahead of what waits");
  const res2 = fakeRes();
  const req = new EventEmitter(); req.setEncoding = () => {}; req.destroy = () => {};
  router.routes["POST /say"](req, res2);
  req.emit("data", "Plain text body"); req.emit("end");
  await res2.finished;
  assert.equal(res2.code, 200);
  assert.ok(FakeTts.last.texts.includes("Plain text body"));
  const res3 = fakeRes();
  router.routes["GET /say"]({}, res3);
  assert.deepEqual(res3.body.voices, ["slt", "kal16", "rms", "awb"]);
  assert.equal(res3.body.voice, "slt");
  p.stop();
});

test("the notification bridge speaks through say(): urgent for an emergency, nothing below the threshold", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, waitForSilenceMs: 0, bridge: { repeatSec: 0 } });
  await until(() => FakeLink.last);
  assert.equal(app.subs.length, 1);
  assert.equal(app.subs[0].sub.subscribe[0].path, "notifications.*");
  app.subs[0].cb({ updates: [{ values: [{ path: "notifications.mob", value: { state: "emergency", method: ["sound", "visual"], message: "Man overboard" } }] }] });
  await until(() => FakeTts.last.texts.length === 1);
  assert.deepEqual(FakeTts.last.texts, ["Man overboard"]);
  await until(() => app.log.some((m) => /say \(urgent/.test(m)));
  app.subs[0].cb({ updates: [{ values: [{ path: "notifications.navigation.depth", value: { state: "warn", method: ["sound"], message: "shallow" } }] }] });
  await flush();
  assert.equal(FakeTts.last.texts.length, 1, "warn is below the default alarm threshold");
  p.stop();
  assert.equal(app.subs.length, 0, "unsubscribed");
});

test("a network link that cannot open is reported and retried; say() still queues", async () => {
  FakeLink.last = undefined;
  FakeLink.failOpen = true;
  const app = fakeApp();
  const p = plugin(app, deps);
  try {
    p.start({ channelKey: KEY });
    await flush();
    assert.match(app.errors.at(-1), /no usable IPv4 interface/);
    assert.match(app.status.at(-1), /network link down/);
    const r = await app.props["signalk-crewradio.api"].say({ text: "hello" });
    assert.equal(r.ok, true, "queued; it plays once the link is back");
  } finally {
    FakeLink.failOpen = false;
    p.stop();
  }
});

test("a link that dies after start reports it in the status at once", async () => {
  FakeLink.last = undefined;
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY });
  await until(() => FakeLink.last && FakeLink.last.sent.length > 0);
  const link = FakeLink.last;
  link.emit("error", new Error("network is unreachable"));
  assert.match(app.errors.at(-1), /network is unreachable/);
  assert.match(app.status.at(-1), /network link down/);
  assert.equal(link.closed, true);
  p.stop();
});

test("an unknown voice in the settings falls back to the default, and a failing engine is a plugin error", () => {
  const app = fakeApp();
  const p = plugin(app, deps);
  p.start({ channelKey: KEY, voice: "nope" });
  assert.equal(app.errors.length, 0);
  assert.equal(FakeTts.last.opts.voice, "slt");
  p.stop();
  class BrokenTts { constructor() { throw new Error("no wasm here"); } }
  const app2 = fakeApp();
  const p2 = plugin(app2, { LanLink: FakeLink, Tts: BrokenTts });
  p2.start({ channelKey: KEY });
  assert.match(app2.errors[0], /Speech: no wasm here/);
  p2.stop();
});
