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
const MAX_PAYLOAD = 4 * 1024 * 1024;

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
      let head;
      try {
        head = JSON.parse(this.buf.subarray(0, nl).toString("utf8"));
      } catch {
        throw new Error("wyoming: malformed header");
      }
      if (typeof head !== "object" || head === null || typeof head.type !== "string") throw new Error("wyoming: header without type");
      const dataLen = lengthField(head.data_length);
      const payloadLen = lengthField(head.payload_length);
      if (payloadLen > MAX_PAYLOAD) throw new Error("wyoming: payload too large");
      const total = nl + 1 + dataLen + payloadLen;
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
   */
  constructor(opts) {
    super();
    this.play = opts.play;
    this.identity = opts.identity;
    this.log = opts.log ?? (() => {});
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
        if (![rate, width, channels].every((n) => Number.isInteger(n) && n > 0) || width !== 2) {
          this.log(`wyoming: unsupported audio format ${JSON.stringify(ev.data)}`);
          this.current = null;
          break;
        }
        this.current = { rate, width, channels, chunks: [], bytes: 0 };
        break;
      }
      case "audio-chunk":
        if (this.current && ev.payload) {
          this.current.chunks.push(ev.payload);
          this.current.bytes += ev.payload.length;
          if (this.current.bytes > MAX_PAYLOAD * 4) {
            this.log("wyoming: announcement too long; dropped");
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

module.exports = { encodeEvent, EventDecoder, SatelliteServer };
