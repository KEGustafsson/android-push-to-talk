// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * signalk-crewradio: the Signal K server speaks on the Crew Radio channel.
 *
 *  1. A node on the crew's push-to-talk channel over the boat's LAN or WLAN, byte-compatible
 *     with the Android app (lib/packet.js, lib/crypto.js, lib/node.js); the phones relay it
 *     onward over Bluetooth and Wi-Fi Aware.
 *  2. Text to speech inside the plugin (lib/tts.js: Flite in WebAssembly, English), with a
 *     queue where urgent announcements go first (lib/queue.js). Three doors to say():
 *     PUT communication.crewradio.say, POST /plugins/signalk-crewradio/say, and the in-process
 *     PropertyValue "signalk-crewradio.api".
 *  3. A notification bridge (lib/bridge.js): Signal K notifications at or above a chosen state
 *     are announced, urgent for emergencies, repeated until they clear.
 *  4. The channel's roster in Signal K: communication.crewradio.* (online, nodes, talking, speaking).
 */

const os = require("node:os");
const path = require("node:path");
const { ChannelCrypto } = require("./lib/crypto");
const { LanLink } = require("./lib/lan");
const { ChannelNode } = require("./lib/node");
const { FliteTts, VOICES, MAX_TEXT } = require("./lib/tts");
const { AnnouncementQueue } = require("./lib/queue");
const { NotificationBridge } = require("./lib/bridge");
const { samplesToBytes, bytesToSamples } = require("./lib/resample");
const tones = require("./lib/tones");
const pkg = require("./package.json");

const API_PROPERTY = "signalk-crewradio.api";
const SAY_PATH = "communication.crewradio.say";

/** @param {object} app the Signal K plugin API; `deps` lets tests inject a fake network link and speech engine */
module.exports = function crewRadioPlugin(app, deps = {}) {
  const Link = deps.LanLink ?? LanLink;
  const Tts = deps.Tts ?? FliteTts;
  const plugin = {
    id: "signalk-crewradio",
    name: "Crew Radio",
    description: pkg.description,
    schema: () => schema(app),
    uiSchema: () => uiSchema(),
  };

  let running = false;
  let cfg = null;
  let link = null;
  let node = null;
  let tts = null;
  let queue = null;
  let bridge = null;
  let unsubscribes = [];
  let reopenTimer = null;
  let backoffMs = 1000;
  let lastStatus = "";

  plugin.start = function (options) {
    running = true;
    cfg = withDefaults(options, app);
    if (!cfg.channelKey) {
      // Not configured yet is not a failure: nothing is started, and the status says what is needed.
      app.setPluginStatus("Waiting for the channel key: set the same key as on the phones (Plugin Config)");
      return;
    }
    const crypto = ChannelCrypto.forChannelKey(cfg.channelKey);
    try {
      tts = new Tts({ voice: cfg.voice, rate: cfg.rate, tempDir: path.join(dataDir(app), "tts-tmp") });
    } catch (e) {
      app.setPluginError(`Speech: ${e.message}`);
      return;
    }
    queue = new AnnouncementQueue({
      play: (pcm, cancelled) => playOnChannel(pcm, cancelled),
      onCancel: () => node?.cancel(),
      log: (m) => app.debug(m),
    });
    queue.on("started", () => status());
    queue.on("done", () => status());

    const openLink = async () => {
      if (!running) return;
      const mine = new Link({ group: cfg.group, port: cfg.port, iface: cfg.iface });
      link = mine;
      mine.on("error", (e) => {
        app.error(`Network link: ${e.message}`);
        scheduleReopen();
        status();
      });
      try {
        const where = await mine.open();
        if (!running || link !== mine) { mine.close(); return; }   // stopped or reopened while the socket was binding
        backoffMs = 1000;
        app.debug(`Network link up on ${where.iface} ${where.address} (group ${cfg.group}:${cfg.port}, broadcast ${where.broadcast})`);
      } catch (e) {
        app.error(`Network link: ${e.message}`);
        scheduleReopen();
        status();
        return;
      }
      node = new ChannelNode({ name: cfg.nodeName, crypto, link, ttl: cfg.hops });
      node.on("roster", (r) => publishRoster(r));
      node.on("speaking", (on) => { publishSpeaking(on); status(); });
      node.start();
      publishRoster(node.roster());              // the paths exist from the start, even when nobody is there yet
    };
    const scheduleReopen = () => {
      if (!running || reopenTimer) return;
      if (node) { node.stop(); node = null; }
      if (link) { link.close(); link = null; }
      reopenTimer = setTimeout(() => { reopenTimer = null; openLink(); }, backoffMs);
      backoffMs = Math.min(backoffMs * 2, 15_000);
    };

    // The three doors to say(): PUT on a path, REST (registerWithRouter), in-process.
    if (typeof app.registerPutHandler === "function") {
      app.registerPutHandler("vessels.self", SAY_PATH, (context, p, value, callback) => {
        say(typeof value === "string" ? { text: value } : value ?? {}).then(
          (r) => callback({ state: "COMPLETED", statusCode: 200, message: JSON.stringify(r) }),
          (e) => callback({ state: "COMPLETED", statusCode: 400, message: e.message }),
        );
        return { state: "PENDING" };
      }, plugin.id);
    }
    if (typeof app.emitPropertyValue === "function") {
      app.emitPropertyValue(API_PROPERTY, { version: 1, say: (o) => say(o) });
    }

    // Notifications to announcements.
    if (cfg.bridge.enabled) {
      bridge = new NotificationBridge({
        say: (o) => say(o),
        log: (m) => app.debug(m),
        rules: {
          minState: cfg.bridge.minState,
          soundOnly: cfg.bridge.soundOnly,
          repeatSec: cfg.bridge.repeatSec,
          urgentStates: cfg.bridge.urgentStates,
          include: cfg.bridge.include,
          exclude: cfg.bridge.exclude,
        },
      });
      bridge.on("announce", (a) => app.debug(`announce ${a.priority}: ${a.path}: ${a.message}`));
      bridge.start();
      app.subscriptionmanager.subscribe(
        { context: "vessels.self", subscribe: [{ path: "notifications.*", policy: "instant" }] },
        unsubscribes,
        (err) => app.error(`notifications subscription: ${err}`),
        (delta) => bridge.onDelta(delta),
      );
    }

    openLink();
    status();
  };

  /** REST: POST /plugins/signalk-crewradio/say with {text, priority} or a plain text body; GET for the state. */
  plugin.registerWithRouter = function (router) {
    router.post("/say", (req, res) => {
      const done = (body) => {
        say(typeof body === "string" ? { text: body } : body ?? {}).then(
          (r) => res.json(r),
          (e) => res.status(400).json({ ok: false, error: e.message }),
        );
      };
      if (req.body !== undefined && req.body !== null && !(Buffer.isBuffer(req.body) && req.body.length === 0)) return done(req.body);
      let raw = "";
      req.setEncoding("utf8");
      req.on("data", (c) => { raw += c; if (raw.length > 10_000) req.destroy(); });
      req.on("end", () => { try { done(raw.trim().startsWith("{") ? JSON.parse(raw) : raw); } catch (e) { res.status(400).json({ ok: false, error: e.message }); } });
    });
    router.get("/say", (req, res) => res.json({ voice: cfg?.voice ?? null, voices: VOICES, queued: queue?.size ?? 0, speaking: !!node?.speaking, online: node ? node.roster().length : 0 }));
  };

  plugin.stop = function () {
    running = false;
    if (reopenTimer) { clearTimeout(reopenTimer); reopenTimer = null; }
    for (const u of unsubscribes) { try { u(); } catch { /* gone */ } }
    unsubscribes = [];
    if (bridge) { bridge.stop(); bridge = null; }
    if (queue) { queue.stop(); queue = null; }
    if (node) { node.stop(); node = null; }
    if (link) { link.close(); link = null; }
    tts = null;
    if (typeof app.emitPropertyValue === "function") app.emitPropertyValue(API_PROPERTY, null);
    publishRoster([]);
    app.setPluginStatus("Stopped");
  };

  /**
   * Speaks a text on the channel. Resolves when it is queued: {ok, queued: position}. Rejects
   * for an empty or over-long text, or when the plugin is not running.
   */
  async function say(opts) {
    if (!running || !tts || !queue) throw new Error("signalk-crewradio is not running");
    const text = typeof opts?.text === "string" ? opts.text.trim() : "";
    if (!text) throw new Error("say: text is required");
    if (text.length > MAX_TEXT) throw new Error(`say: text over ${MAX_TEXT} characters`);
    const priority = opts.priority === "urgent" ? "urgent" : "normal";
    const speech = await tts.synthesize(text);
    const parts = [];
    if (cfg.chime) parts.push(priority === "urgent" ? tones.urgentChime() : tones.chime());
    parts.push(bytesToSamples(speech), tones.silence(150));
    const pcm = samplesToBytes(tones.concat(parts));
    const position = queue.enqueue(pcm, priority);
    app.debug(`say (${priority}, position ${position}): ${text}`);
    return { ok: true, queued: position, priority, seconds: Math.round(pcm.length / 32) / 1000 };
  }

  /** How long an announcement waits for the network link to come back before it is dropped. */
  const LINK_WAIT_MS = 60_000;

  async function playOnChannel(pcm, cancelled) {
    // The link reconnects on its own (1 s doubling to 15 s); an announcement made while it is
    // down waits for it at the head of the queue instead of being thrown away.
    const t0 = Date.now();
    while (!node) {
      if (!running || cancelled()) return;
      if (Date.now() - t0 > LINK_WAIT_MS) throw new Error("not on the channel (network link down)");
      await new Promise((r) => setTimeout(r, 100));
    }
    if (cfg.waitForSilenceMs > 0) await node.waitForSilence(300, cfg.waitForSilenceMs);
    if (cancelled()) return;
    await node.speak(pcm);
  }

  function publishRoster(roster) {
    const talking = roster.filter((n) => n.talking).map((n) => n.name);
    app.handleMessage(plugin.id, {
      updates: [{
        values: [
          { path: "communication.crewradio.online", value: roster.length },
          { path: "communication.crewradio.nodes", value: roster.map((n) => ({ name: n.name, hops: n.hops, transports: n.transports, talking: n.talking })) },
          { path: "communication.crewradio.talking", value: talking },
          { path: "communication.crewradio.speaking", value: !!node?.speaking },
        ],
      }],
    });
    status(roster);
  }

  function publishSpeaking(on) {
    app.handleMessage(plugin.id, { updates: [{ values: [{ path: "communication.crewradio.speaking", value: !!on }] }] });
  }

  function status(roster) {
    if (!running) return;
    const r = roster ?? node?.roster() ?? [];
    const parts = [node ? `${r.length} online` : "network link down"];
    const talking = r.filter((n) => n.talking).map((n) => n.name);
    if (talking.length) parts.push(`talking: ${talking.join(", ")}`);
    if (node?.speaking) parts.push("announcing");
    const waiting = (queue?.size ?? 0) + (queue?.current && !node?.speaking ? 1 : 0);   // held for the link, or for a gap in talk
    if (waiting) parts.push(`${waiting} waiting`);
    parts.push(`voice ${cfg?.voice ?? "-"}`);
    const line = parts.join(" · ");
    if (line !== lastStatus) { lastStatus = line; app.setPluginStatus(line); }
  }

  return plugin;
};

function withDefaults(o, app) {
  o = o ?? {};
  const b = o.bridge ?? {};
  return {
    channelKey: String(o.channelKey ?? "").trim(),
    nodeName: String(o.nodeName ?? "").trim() || boatName(app),
    group: String(o.group ?? "239.255.42.1").trim(),
    port: Number(o.port ?? 47474),
    iface: String(o.iface ?? "auto").trim() || "auto",
    hops: Number(o.hops ?? 4),
    voice: VOICES.includes(o.voice) ? o.voice : "slt",
    rate: Number(o.rate ?? 1),
    chime: o.chime ?? true,
    waitForSilenceMs: Number(o.waitForSilenceMs ?? 2000),
    bridge: {
      enabled: b.enabled ?? true,
      minState: b.minState ?? "alarm",
      soundOnly: b.soundOnly ?? true,
      repeatSec: Number(b.repeatSec ?? 30),
      urgentStates: Array.isArray(b.urgentStates) ? b.urgentStates : ["emergency"],
      include: Array.isArray(b.include) ? b.include : [],
      exclude: Array.isArray(b.exclude) ? b.exclude : [],
    },
  };
}

function dataDir(app) {
  try {
    const d = app.getDataDirPath?.();
    if (typeof d === "string" && d) return d;
  } catch { /* older server */ }
  return path.join(os.tmpdir(), "signalk-crewradio");
}

function boatName(app) {
  try {
    const n = app.getSelfPath?.("name");
    if (typeof n === "string" && n.trim()) return n.trim();
  } catch { /* no name */ }
  return "Boat";
}

function schema(app) {
  return {
    type: "object",
    required: ["channelKey"],
    properties: {
      channelKey: { type: "string", title: "Channel key", description: "The crew's channel key, exactly as on the phones (Settings › Channel key). Keeps the channel private; every node must share it." },
      nodeName: { type: "string", title: "Name on the roster", description: `How the phones list the server. Empty: the vessel's name (${boatName(app)}).`, default: "" },
      voice: { type: "string", title: "Voice", enum: VOICES, default: "slt", description: "Flite voices, English: slt (female), kal16, rms and awb (male)." },
      rate: { type: "number", title: "Speaking rate", default: 1, minimum: 0.7, maximum: 1.3, description: "1 is the voice's own pace; 0.8 slower for a noisy deck." },
      chime: { type: "boolean", title: "Chime before each announcement", default: true, description: "Two notes; three quick ones before an urgent announcement." },
      waitForSilenceMs: { type: "integer", title: "Wait for a gap in talk (ms)", default: 2000, minimum: 0, maximum: 30000, description: "An announcement waits this long at most for the crew to stop talking before it cuts in." },
      group: { type: "string", title: "Multicast group", default: "239.255.42.1", description: "Must match the phones' WLAN setting (Settings › WLAN group and port)." },
      port: { type: "integer", title: "UDP port", default: 47474, minimum: 1024, maximum: 65535 },
      iface: { type: "string", title: "Network interface", default: "auto", description: "The server's interface on the boat network: wired LAN (eth0) or WLAN (wlan0), as long as it is the same network the phones' WLAN is on. auto: a wlan interface, else eth/en, else the first with an IPv4 address." },
      hops: { type: "integer", title: "Hop budget", default: 4, minimum: 1, maximum: 8, description: "How far phones may relay the server's packets over Bluetooth and Wi-Fi Aware." },
      bridge: {
        type: "object",
        title: "Announce Signal K notifications",
        properties: {
          enabled: { type: "boolean", title: "Enabled", default: true },
          minState: { type: "string", title: "Announce from state", enum: ["alert", "warn", "alarm", "emergency"], default: "alarm" },
          soundOnly: { type: "boolean", title: "Only notifications that ask for sound", default: true, description: "A notification's method lists visual and/or sound; off announces everything at or above the state." },
          repeatSec: { type: "integer", title: "Repeat every (s), 0 = once", default: 30, minimum: 0, maximum: 3600 },
          urgentStates: { type: "array", title: "Urgent states", items: { type: "string", enum: ["alert", "warn", "alarm", "emergency"] }, default: ["emergency"], description: "Said first, interrupting a normal announcement, with the urgent chime." },
          include: { type: "array", title: "Only these notification paths (globs)", items: { type: "string" }, default: [], description: "Relative to notifications., e.g. navigation.anchor or mob. Empty: all." },
          exclude: { type: "array", title: "Never these paths (globs)", items: { type: "string" }, default: [] },
        },
      },
    },
  };
}

function uiSchema() {
  return { channelKey: { "ui:widget": "password" } };
}
