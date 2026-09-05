// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * signalk-crewradio: the Signal K server as a node on the Crew Radio channel.
 *
 * Three things, all optional but the first:
 *  1. A Wyoming satellite (speaker only) that signalk-wyoming connects to. Whatever the boat's
 *     voice assistant is asked to say (PUT voice.say, its REST API, or another plugin's say())
 *     reaches the crew's phones as speech on the channel, resampled from Piper's 22 050 Hz to
 *     the channel's 16 kHz and keyed like a talker. Urgent announcements come first, as
 *     signalk-wyoming orders them.
 *  2. A notification bridge: Signal K notifications at or above a chosen state are announced
 *     through the assistant, urgent for emergencies, and repeated until they clear.
 *  3. The channel's roster in Signal K: communication.crewradio.* (who is online, who is talking).
 *
 * The wire, the crypto and the roster protocol are byte-compatible with the Android app; see
 * lib/packet.js, lib/crypto.js and lib/node.js.
 */

const { ChannelCrypto } = require("./lib/crypto");
const { LanLink } = require("./lib/lan");
const { ChannelNode } = require("./lib/node");
const { SatelliteServer } = require("./lib/wyoming");
const { NotificationBridge } = require("./lib/bridge");
const { resample, bytesToSamples, samplesToBytes, toMono } = require("./lib/resample");
const tones = require("./lib/tones");
const pkg = require("./package.json");

const CHANNEL_RATE = 16_000;
const WYOMING_API = "signalk-wyoming.api";

/** @param {object} app the Signal K plugin API; `deps` lets tests inject a fake network link */
module.exports = function crewRadioPlugin(app, deps = {}) {
  const Link = deps.LanLink ?? LanLink;
  const plugin = {
    id: "signalk-crewradio",
    name: "Crew Radio",
    description: pkg.description,
    schema: () => schema(app),
    uiSchema: () => uiSchema(),
  };

  let running = false;
  let link = null;
  let node = null;
  let satellite = null;
  let bridge = null;
  let unsubscribes = [];
  let sayFacade = null; // latest {version, say} from signalk-wyoming
  let unsubscribeApi = null;
  let reopenTimer = null;
  let backoffMs = 1000;
  let assistantConnected = false;

  plugin.start = function (options) {
    running = true;
    const cfg = withDefaults(options, app);
    if (!cfg.channelKey) {
      // Not configured yet is not a failure: nothing is started, and the status says what is needed.
      app.setPluginStatus("Waiting for the channel key: set the same key as on the phones (Plugin Config)");
      return;
    }
    const crypto = ChannelCrypto.forChannelKey(cfg.channelKey);

    const openLink = async () => {
      if (!running) return;
      link = new Link({ group: cfg.group, port: cfg.port, iface: cfg.iface });
      link.on("error", (e) => {
        app.error(`Network link: ${e.message}`);
        scheduleReopen();
        status();                                 // the roster is gone with the link; say so now, not at the next event
      });
      try {
        const where = await link.open();
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
      node.on("speaking", () => status());
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

    // The assistant's say(), published in-process by signalk-wyoming.
    if (typeof app.onPropertyValues === "function") {
      unsubscribeApi = app.onPropertyValues(WYOMING_API, (values) => {
        const latest = values?.at?.(-1)?.value;
        sayFacade = latest && typeof latest.say === "function" ? latest : null;
        status();
      });
    }
    const say = (opts) => {
      if (!sayFacade) return Promise.reject(new Error("signalk-wyoming is not running"));
      return sayFacade.say(opts);
    };

    // The satellite signalk-wyoming connects to.
    satellite = new SatelliteServer({
      identity: { name: cfg.nodeName, description: "Crew Radio channel (signalk-crewradio)", version: pkg.version },
      log: (m) => app.debug(m),
      play: (audio) => announce(audio, cfg),
      allowFrom: cfg.satelliteAllowFrom,
    });
    satellite.on("connect", () => { assistantConnected = true; status(); });
    satellite.on("disconnect", () => { assistantConnected = false; status(); });
    satellite.on("error", (e) => app.error(`Wyoming satellite: ${e.message}`));
    // The Wyoming protocol has no authentication: whoever can reach this port can make the crew
    // hear anything. signalk-wyoming runs on this host, so loopback is the default. A wider bind
    // is only taken together with an allowlist of the orchestrator's address(es); the satellite
    // itself refuses every other client, and without a list it stays on loopback and says so.
    let bindHost = cfg.satelliteHost;
    if (!isLoopback(bindHost)) {
      if (cfg.satelliteAllowFrom.length === 0) {
        app.error(`Wyoming satellite: bind address ${bindHost} needs an allowlist of orchestrator addresses; listening on 127.0.0.1 instead`);
        bindHost = "127.0.0.1";
      } else {
        app.debug(`Wyoming satellite on ${bindHost}:${cfg.satellitePort}, clients limited to ${cfg.satelliteAllowFrom.join(", ")}`);
      }
    }
    plugin.satelliteListening = satellite.listen(cfg.satellitePort, bindHost).then(
      (a) => { app.debug(`Wyoming satellite listening on ${a.address}:${a.port}`); return a; },
      (e) => { app.setPluginError(`Wyoming satellite: ${e.message}`); return null; },
    );

    // Notifications to announcements.
    if (cfg.bridge.enabled) {
      bridge = new NotificationBridge({
        say,
        log: (m) => app.debug(m),
        rules: {
          minState: cfg.bridge.minState,
          soundOnly: cfg.bridge.soundOnly,
          repeatSec: cfg.bridge.repeatSec,
          urgentStates: cfg.bridge.urgentStates,
          include: cfg.bridge.include,
          exclude: cfg.bridge.exclude,
          targets: cfg.bridge.alsoSpeakers ? null : [cfg.satelliteId],
        },
      });
      bridge.on("announce", (a) => app.debug(`announce ${a.priority}: ${a.path}: ${a.message}`));
      bridge.start();
      app.subscriptionmanager.subscribe(
        { context: "vessels.self", subscribe: [{ path: "notifications.*", policy: "instant" }] },   // no period: the server warns that a period implies 'fixed'
        unsubscribes,
        (err) => app.error(`notifications subscription: ${err}`),
        (delta) => bridge.onDelta(delta),
      );
    }

    openLink();
  };

  plugin.stop = function () {
    running = false;
    if (reopenTimer) { clearTimeout(reopenTimer); reopenTimer = null; }
    for (const u of unsubscribes) { try { u(); } catch { /* gone */ } }
    unsubscribes = [];
    if (typeof unsubscribeApi === "function") { try { unsubscribeApi(); } catch { /* gone */ } }
    unsubscribeApi = null;
    sayFacade = null;
    if (bridge) { bridge.stop(); bridge = null; }
    if (satellite) { satellite.close(); satellite = null; }
    if (node) { node.stop(); node = null; }
    if (link) { link.close(); link = null; }
    publishRoster([]);
    app.setPluginStatus("Stopped");
  };

  /** One announcement from the assistant: to mono, to 16 kHz, chime in front, wait for a gap, key the channel. */
  async function announce(audio, cfg) {
    if (!node) throw new Error("not on the channel (network link down)");
    let samples = bytesToSamples(Buffer.concat(audio.chunks));
    samples = toMono(samples, audio.channels);
    samples = resample(samples, audio.rate, CHANNEL_RATE);
    const parts = [];
    if (cfg.chime) parts.push(tones.chime());
    parts.push(samples, tones.silence(150));
    const pcm = samplesToBytes(tones.concat(parts));
    if (cfg.waitForSilenceMs > 0) await node.waitForSilence(300, cfg.waitForSilenceMs);
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
        ],
      }],
    });
    status(roster);
  }

  function status(roster) {
    if (!running) return;
    const r = roster ?? node?.roster() ?? [];
    const parts = [];
    parts.push(node ? `${r.length} online` : "network link down");
    const talking = r.filter((n) => n.talking).map((n) => n.name);
    if (talking.length) parts.push(`talking: ${talking.join(", ")}`);
    if (node?.speaking) parts.push("announcing");
    parts.push(assistantConnected ? "assistant connected" : "assistant not connected");
    if (!sayFacade) parts.push("signalk-wyoming say() unavailable");
    app.setPluginStatus(parts.join(" · "));
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
    satellitePort: Number(o.satellitePort ?? 10701),
    satelliteHost: String(o.satelliteHost ?? "127.0.0.1").trim() || "127.0.0.1",
    satelliteAllowFrom: (Array.isArray(o.satelliteAllowFrom) ? o.satelliteAllowFrom : []).map((a) => String(a).trim()).filter(Boolean),
    satelliteId: String(o.satelliteId ?? "crewradio").trim() || "crewradio",
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
      alsoSpeakers: b.alsoSpeakers ?? false,
    },
  };
}

function isLoopback(host) {
  return host === "127.0.0.1" || host === "::1" || host === "localhost" || host.startsWith("127.");
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
      group: { type: "string", title: "Multicast group", default: "239.255.42.1", description: "Must match the phones' WLAN setting (Settings › WLAN group and port)." },
      port: { type: "integer", title: "UDP port", default: 47474, minimum: 1024, maximum: 65535 },
      iface: { type: "string", title: "Network interface", default: "auto", description: "The server's interface on the boat network: wired LAN (eth0) or WLAN (wlan0), as long as it is the same network the phones' WLAN is on. auto: a wlan interface, else eth/en, else the first with an IPv4 address." },
      hops: { type: "integer", title: "Hop budget", default: 4, minimum: 1, maximum: 8, description: "How far phones may relay the server's packets over Bluetooth and Wi-Fi Aware." },
      satellitePort: { type: "integer", title: "Wyoming satellite port", default: 10701, minimum: 1024, maximum: 65535, description: "Add a satellite in signalk-wyoming with host 127.0.0.1 and this port, id \"crewradio\" (or the id below), and no wake words (speaker only)." },
      satelliteHost: { type: "string", title: "Wyoming satellite bind address", default: "127.0.0.1", description: "Leave at 127.0.0.1 when signalk-wyoming runs on this server. The Wyoming protocol has no authentication; a wider address is taken only together with the allowlist below, and everyone not on it is refused." },
      satelliteAllowFrom: { type: "array", title: "Orchestrator addresses allowed to connect", items: { type: "string" }, default: [], description: "For an orchestrator on another host: its address, or an IPv4 range such as 10.10.10.0/24. Loopback is always allowed. Empty: loopback only, whatever the bind address." },
      satelliteId: { type: "string", title: "Satellite id in signalk-wyoming", default: "crewradio", description: "The id you gave this satellite in signalk-wyoming; the bridge targets it. Must match ^[a-zA-Z0-9_-]+$." },
      chime: { type: "boolean", title: "Chime before each announcement", default: true },
      waitForSilenceMs: { type: "integer", title: "Wait for a gap in talk (ms)", default: 2000, minimum: 0, maximum: 30000, description: "An announcement waits this long at most for the crew to stop talking before it cuts in." },
      bridge: {
        type: "object",
        title: "Announce Signal K notifications",
        properties: {
          enabled: { type: "boolean", title: "Enabled", default: true },
          minState: { type: "string", title: "Announce from state", enum: ["alert", "warn", "alarm", "emergency"], default: "alarm" },
          soundOnly: { type: "boolean", title: "Only notifications that ask for sound", default: true, description: "A notification's method lists visual and/or sound; off announces everything at or above the state." },
          repeatSec: { type: "integer", title: "Repeat every (s), 0 = once", default: 30, minimum: 0, maximum: 3600 },
          urgentStates: { type: "array", title: "Urgent states", items: { type: "string", enum: ["alert", "warn", "alarm", "emergency"] }, default: ["emergency"], description: "Said with priority urgent: jumps every queue and bypasses mute in signalk-wyoming." },
          include: { type: "array", title: "Only these notification paths (globs)", items: { type: "string" }, default: [], description: "Relative to notifications., e.g. navigation.anchor or mob. Empty: all." },
          exclude: { type: "array", title: "Never these paths (globs)", items: { type: "string" }, default: [] },
          alsoSpeakers: { type: "boolean", title: "Also on the boat's own speakers", default: false, description: "Off: only the crew channel. On: every satellite signalk-wyoming knows." },
        },
      },
    },
  };
}

function uiSchema() {
  return { channelKey: { "ui:widget": "password" } };
}
