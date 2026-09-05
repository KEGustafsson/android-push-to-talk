// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The Wyoming protocol, as much of it as a speaker-only satellite needs.
 *
 * Wire: one JSON header line per event, `{"type": ..., "data": {...}, "data_length": n,
 * "payload_length": m}\n`, then `data_length` bytes of extra JSON merged into data, then
 * `payload_length` bytes of binary payload (PCM for audio-chunk). Mirrors rhasspy/wyoming.
 *
 * SatelliteServer is what signalk-wyoming connects to (it is the client to everything). It
 * answers `describe` with `info`, `ping` with `pong`, notes `run-satellite` / `pause-satellite`,
 * collects one announcement from `audio-start` .. `audio-chunk`* .. `audio-stop`, hands it to
 * the owner, and sends `played` once the owner reports it has gone out.
 */

const net = require("node:net");
const { EventEmitter } = require("node:events");

const MAX_HEADER = 64 * 1024;
const MAX_DATA = 1024 * 1024;              // extra JSON after the header line
const MAX_PAYLOAD = 4 * 1024 * 1024;       // one audio-chunk
const MAX_BUFFERED = MAX_HEADER + MAX_DATA + MAX_PAYLOAD;
const MAX_ANNOUNCEMENT_MS = 60_000;        // longer than any alarm text; bounds the resampling work
const RATES = { min: 8000, max: 48000 };

function encodeEvent(type, data, payload) {
  const head = { type };
  if (data !== undefined && data !== null) head.data = data;
  if (payload && payload.length > 0) head.payload_length = payload.length;
  const line = Buffer.from(JSON.stringify(head) + "\n", "utf8");
  return payload && payload.length > 0 ? Buffer.concat([line, payload]) : line;
}

/** Incremental decoder: feed() bytes, get complete events back in order. */
class EventDecoder {
  constructor() {
    this.buf = Buffer.alloc(0);
  }

  /** @returns {Array<{type: string, data: object, payload?: Buffer}>} events completed by these bytes */
  feed(bytes) {
    this.buf = this.buf.length === 0 ? Buffer.from(bytes) : Buffer.concat([this.buf, bytes]);
    const out = [];
    for (;;) {
      const nl = this.buf.indexOf(0x0a);
      if (nl < 0) {
        if (this.buf.length > MAX_HEADER) throw new Error("wyoming: header line too long");
        break;
      }
      if (nl > MAX_HEADER) throw new Error("wyoming: header line too long");
      let head;
      try {
        head = JSON.parse(this.buf.subarray(0, nl).toString("utf8"));
      } catch {
        throw new Error("wyoming: malformed header");
      }
      if (typeof head !== "object" || head === null || typeof head.type !== "string") throw new Error("wyoming: header without type");
      const dataLen = lengthField(head.data_length);
      const payloadLen = lengthField(head.payload_length);
      if (dataLen > MAX_DATA) throw new Error("wyoming: data block too large");
      if (payloadLen > MAX_PAYLOAD) throw new Error("wyoming: payload too large");
      const total = nl + 1 + dataLen + payloadLen;   // at most MAX_BUFFERED, so an incomplete event holds no more than that
      if (this.buf.length < total) break;
      let data = typeof head.data === "object" && head.data !== null ? head.data : {};
      if (dataLen > 0) {
        try {
          const extra = JSON.parse(this.buf.subarray(nl + 1, nl + 1 + dataLen).toString("utf8"));
          if (typeof extra === "object" && extra !== null) data = { ...data, ...extra };
        } catch {
          throw new Error("wyoming: malformed data block");
        }
      }
      const ev = { type: head.type, data };
      if (payloadLen > 0) ev.payload = Buffer.from(this.buf.subarray(nl + 1 + dataLen, total));
      this.buf = this.buf.subarray(total);
      out.push(ev);
    }
    return out;
  }
}

function lengthField(v) {
  if (v === undefined || v === null) return 0;
  if (!Number.isInteger(v) || v < 0) throw new Error("wyoming: bad length field");
  return v;
}

/**
 * A speaker-only Wyoming satellite. Events: 'connect', 'disconnect', 'mode' (run | pause),
 * 'error'. `play(audio)` is the owner's async callback: `{rate, width, channels, chunks: Buffer[]}`
 * in, resolves when the announcement has been sent; `played` goes back to the orchestrator then.
 */
class SatelliteServer extends EventEmitter {
  /**
   * @param {object} opts
   * @param {(audio: {rate:number,width:number,channels:number,chunks:Buffer[]}) => Promise<void>} opts.play
   * @param {{name:string, description?:string, version?:string, attribution?:{name:string,url:string}}} opts.identity
   * @param {(msg:string)=>void} [opts.log]
   * @param {string[]} [opts.allowFrom]  client addresses or IPv4 CIDRs allowed to connect besides loopback;
   *                                     the protocol has no authentication, so this is the only gate
   */
  constructor(opts) {
    super();
    this.play = opts.play;
    this.identity = opts.identity;
    this.log = opts.log ?? (() => {});
    this.allow = (opts.allowFrom ?? []).map(parseAllow).filter(Boolean);
    this.server = null;
    this.client = null;
    this.mode = "pause";
    this.current = null; // announcement being collected
  }

  listen(port, host = "0.0.0.0") {
    return new Promise((resolve, reject) => {
      const server = net.createServer((sock) => this.accept(sock));
      server.on("error", (e) => {
        if (this.server === null) reject(e);
        else this.emit("error", e);
      });
      server.listen(port, host, () => {
        this.server = server;
        resolve(server.address());
      });
    });
  }

  close() {
    const c = this.client;
    this.client = null;
    if (c) c.sock.destroy();
    const s = this.server;
    this.server = null;
    return new Promise((resolve) => (s ? s.close(() => resolve()) : resolve()));
  }

  get connected() {
    return this.client !== null;
  }

  accept(sock) {
    if (!this.allowed(sock.remoteAddress)) {
      this.log(`wyoming: refused a connection from ${sock.remoteAddress}: not loopback and not in the allowlist`);
      sock.destroy();
      return;
    }
    // One client at a time: the newest wins, so a reconnecting orchestrator reclaims the slot at once.
    if (this.client) {
      this.log("wyoming: new connection replaces the old one");
      const old = this.client;
      this.client = null;
      old.sock.destroy();
    }
    const client = { sock, decoder: new EventDecoder() };
    this.client = client;
    sock.setNoDelay(true);
    sock.on("data", (bytes) => {
      let events;
      try {
        events = client.decoder.feed(bytes);
      } catch (e) {
        this.log(`wyoming: ${e.message}; dropping the connection`);
        sock.destroy();
        return;
      }
      for (const ev of events) this.handle(client, ev);
    });
    sock.on("error", (e) => this.log(`wyoming: socket ${e.message}`));
    sock.on("close", () => {
      if (this.client === client) {
        this.client = null;
        this.current = null;
        this.emit("disconnect");
      }
    });
    this.emit("connect");
  }

  /** Loopback always; anything else only if the allowlist says so. */
  allowed(remote) {
    const ip = normaliseIp(remote);
    if (ip === null) return false;
    if (isLoopbackIp(ip)) return true;
    return this.allow.some((rule) => rule(ip));
  }

  send(client, type, data, payload) {
    if (this.client !== client || client.sock.destroyed) return false;
    client.sock.write(encodeEvent(type, data, payload));
    return true;
  }

  handle(client, ev) {
    switch (ev.type) {
      case "describe":
        this.send(client, "info", this.info());
        break;
      case "ping":
        this.send(client, "pong", { text: ev.data.text ?? null });
        break;
      case "run-satellite":
        this.mode = "run";
        this.emit("mode", "run");
        break;
      case "pause-satellite":
        this.mode = "pause";
        this.emit("mode", "pause");
        break;
      case "audio-start": {
        const rate = ev.data.rate, width = ev.data.width, channels = ev.data.channels;
        const sane = [rate, width, channels].every((n) => Number.isInteger(n) && n > 0)
          && width === 2 && channels <= 2 && rate >= RATES.min && rate <= RATES.max;
        if (!sane) {
          this.log(`wyoming: unsupported audio format ${JSON.stringify(ev.data)}`);
          this.current = null;
          break;
        }
        // Bytes for MAX_ANNOUNCEMENT_MS at this format: the cap on what one announcement may make us resample.
        const maxBytes = Math.floor((rate * width * channels * MAX_ANNOUNCEMENT_MS) / 1000);
        this.current = { rate, width, channels, chunks: [], bytes: 0, maxBytes };
        break;
      }
      case "audio-chunk":
        if (this.current && ev.payload) {
          this.current.chunks.push(ev.payload);
          this.current.bytes += ev.payload.length;
          if (this.current.bytes > this.current.maxBytes) {
            this.log(`wyoming: announcement over ${MAX_ANNOUNCEMENT_MS / 1000} s; dropped`);
            this.current = null;
          }
        }
        break;
      case "audio-stop": {
        const audio = this.current;
        this.current = null;
        if (!audio || audio.chunks.length === 0) {
          this.send(client, "played");
          break;
        }
        Promise.resolve()
          .then(() => this.play(audio))
          .catch((e) => this.log(`announcement failed: ${e.message}`))
          .then(() => this.send(client, "played"));
        break;
      }
      default:
        break; // unknown events are ignored, as the protocol asks
    }
  }

  info() {
    const id = this.identity;
    return {
      asr: [], tts: [], handle: [], intent: [], wake: [], mic: [], snd: [],
      satellite: {
        name: id.name,
        description: id.description ?? "Crew Radio channel",
        attribution: id.attribution ?? { name: "Crew Radio", url: "https://github.com/KEGustafsson/android-push-to-talk" },
        installed: true,
        version: id.version ?? null,
        area: null,
        snd_format: null,
      },
    };
  }
}

/** "::ffff:10.0.0.5" -> "10.0.0.5"; null for anything unparseable. */
function normaliseIp(remote) {
  if (typeof remote !== "string" || remote === "") return null;
  return remote.startsWith("::ffff:") ? remote.slice(7) : remote;
}

function isLoopbackIp(ip) {
  return ip === "::1" || /^127\.\d+\.\d+\.\d+$/.test(ip);
}

function ipv4ToInt(ip) {
  const m = /^(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})$/.exec(ip);
  if (!m) return null;
  const parts = m.slice(1).map(Number);
  if (parts.some((n) => n > 255)) return null;
  return ((parts[0] << 24) | (parts[1] << 16) | (parts[2] << 8) | parts[3]) >>> 0;
}

/** An allowlist entry: an exact address (v4 or v6) or an IPv4 CIDR; returns a predicate, or null when malformed. */
function parseAllow(entry) {
  const s = String(entry).trim();
  if (!s) return null;
  const cidr = /^(.+)\/(\d{1,2})$/.exec(s);
  if (cidr) {
    const base = ipv4ToInt(cidr[1]), bits = Number(cidr[2]);
    if (base === null || bits > 32) return null;
    const mask = bits === 0 ? 0 : (0xffffffff << (32 - bits)) >>> 0;
    return (ip) => { const n = ipv4ToInt(ip); return n !== null && (n & mask) === (base & mask); };
  }
  return (ip) => ip === s;
}

module.exports = { encodeEvent, EventDecoder, SatelliteServer, MAX_ANNOUNCEMENT_MS, MAX_DATA, MAX_PAYLOAD, parseAllow, normaliseIp };
