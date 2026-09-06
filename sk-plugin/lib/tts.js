// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Text to speech inside the plugin: Flite (CMU's Festival-lite) compiled to WebAssembly, run
 * through Node's WASI. English only; four voices built into the module, "slt" (female) by
 * default. Output is 16 kHz mono PCM16, the channel's own rate, so nothing is resampled.
 *
 * Each call instantiates the module afresh (Flite's C entry point is not re-entrant), writes
 * a WAV into a scratch directory and reads it back; that costs some tens of milliseconds on a
 * laptop and a few hundred on a Raspberry Pi, and repeated sentences (an alarm said every
 * 30 s) come from a small cache. Synthesis is serialised: one sentence at a time.
 *
 * Numbers and the units that appear in Signal K alarm texts are spelled out before synthesis,
 * so "25 m" is read as "25 metres" and "12.2 V" as "12.2 volts".
 */

const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const crypto = require("node:crypto");
const { resample, bytesToSamples, samplesToBytes } = require("./resample");

const VOICES = Object.freeze(["slt", "kal16", "rms", "awb"]);
const CHANNEL_RATE = 16_000;
const MAX_TEXT = 500;

class FliteTts {
  /**
   * @param {object} [opts]
   * @param {string} [opts.voice="slt"]
   * @param {number} [opts.rate=1]        speaking rate, 0.7 (slow) .. 1.3 (fast)
   * @param {string} [opts.tempDir]       scratch directory for the WAV round trip (default: the OS temp dir)
   * @param {number} [opts.cacheBytes]    how much rendered speech to keep (default 8 MiB, about 4 minutes)
   * @param {string} [opts.wasmPath]      override for tests
   */
  constructor(opts = {}) {
    this.voice = opts.voice ?? "slt";
    if (!VOICES.includes(this.voice)) throw new Error(`unknown Flite voice "${this.voice}" (one of ${VOICES.join(", ")})`);
    this.rate = clamp(Number(opts.rate ?? 1), 0.5, 2);
    this.tempDir = opts.tempDir ?? path.join(os.tmpdir(), "signalk-crewradio");
    this.cacheBytes = opts.cacheBytes ?? 8 * 1024 * 1024;
    this.wasmPath = opts.wasmPath ?? require.resolve("@echogarden/flite-wasi");
    this.module = null;
    this.cache = new Map(); // key -> Buffer, insertion ordered
    this.cacheSize = 0;
    this.chain = Promise.resolve();
    this.stats = { synthesized: 0, cached: 0, ms: 0 };
  }

  /** The text as it will be spoken: trimmed, capped, numbers and units expanded. */
  static normalise(text) {
    let t = String(text ?? "").replace(/\s+/g, " ").trim();
    if (t.length > MAX_TEXT) t = t.slice(0, MAX_TEXT - 1) + ".";
    t = t.replace(/([0-9]+)[.]([0-9])[0-9]+/g, (_, i, d) => `${i}.${d}`);   // 69.771 -> 69.8 is not worth hearing
    // units after a number (Signal K alarm texts: depth, wind, battery, temperature)
    t = t.replace(/(\d)\s*(km\/h|m\/s|nm|kn|kts|kt|km|m|ft|°C|°F|°|%|hPa|mbar|bar|kW|W|Ah|A|V|l|L|min|s|h)(?![A-Za-z])/g, (_, d, u) => `${d} ${UNITS[u] ?? u}`);
    t = t.replace(/(\d)\s*°\s*(?![CF])/g, "$1 degrees ");
    return t;
  }

  /** 16 kHz mono PCM16 for `text`; an empty text yields an empty buffer. */
  synthesize(text) {
    const spoken = FliteTts.normalise(text);
    if (!spoken) return Promise.resolve(Buffer.alloc(0));
    const key = `${this.voice}|${this.rate}|${spoken}`;
    const hit = this.cache.get(key);
    if (hit) {
      this.cache.delete(key); this.cache.set(key, hit);      // most recently used last
      this.stats.cached++;
      return Promise.resolve(hit);
    }
    const turn = this.chain.then(() => this.run(spoken)).then((pcm) => { this.remember(key, pcm); return pcm; });
    this.chain = turn.catch(() => {});
    return turn;
  }

  async run(text) {
    const t0 = Date.now();
    const { WASI } = require("node:wasi");
    if (!this.module) this.module = await WebAssembly.compile(fs.readFileSync(this.wasmPath));
    fs.mkdirSync(this.tempDir, { recursive: true });
    const id = crypto.randomBytes(8).toString("hex");
    const outName = `${id}.wav`;
    const outPath = path.join(this.tempDir, outName);
    const devnull = fs.openSync(os.devNull, "w");
    try {
      const wasi = new WASI({
        version: "preview1",
        args: ["--", "-voice", this.voice, "--setf", `duration_stretch=${(1 / this.rate).toFixed(3)}`, ` ${text} `, outName],
        env: {},
        preopens: { ".": this.tempDir },
        stdout: devnull,
        stderr: devnull,
        returnOnExit: true,
      });
      const instance = await WebAssembly.instantiate(this.module, { wasi_snapshot_preview1: wasi.wasiImport });
      const exit = wasi.start(instance);
      if (exit !== 0) throw new Error(`Flite exited with ${exit}`);
      const wav = fs.readFileSync(outPath);
      const { rate, pcm } = parseWav(wav);
      let out = pcm;
      if (rate !== CHANNEL_RATE) out = samplesToBytes(resample(bytesToSamples(pcm), rate, CHANNEL_RATE));
      this.stats.synthesized++;
      this.stats.ms += Date.now() - t0;
      return out;
    } finally {
      fs.closeSync(devnull);
      try { fs.unlinkSync(outPath); } catch { /* never written */ }
    }
  }

  remember(key, pcm) {
    if (pcm.length > this.cacheBytes) return;
    const previous = this.cache.get(key);           // two misses for the same text before either finished
    if (previous) { this.cacheSize -= previous.length; this.cache.delete(key); }
    this.cache.set(key, pcm);
    this.cacheSize += pcm.length;
    while (this.cacheSize > this.cacheBytes && this.cache.size > 1) {
      const oldest = this.cache.keys().next().value;
      this.cacheSize -= this.cache.get(oldest).length;
      this.cache.delete(oldest);
    }
  }
}

const UNITS = {
  m: "metres", km: "kilometres", nm: "nautical miles", kn: "knots", kts: "knots", kt: "knots", ft: "feet",
  "km/h": "kilometres per hour", "m/s": "metres per second",
  "°C": "degrees", "°F": "degrees Fahrenheit", "°": "degrees", "%": "percent",
  hPa: "hectopascal", mbar: "millibar", bar: "bar", kW: "kilowatts", W: "watts", Ah: "amp hours", A: "amps", V: "volts",
  l: "litres", L: "litres", min: "minutes", s: "seconds", h: "hours",
};

/** Minimal RIFF/WAVE reader for PCM16 mono. */
function parseWav(b) {
  if (b.length < 44 || b.toString("ascii", 0, 4) !== "RIFF" || b.toString("ascii", 8, 12) !== "WAVE") throw new Error("Flite wrote no WAV");
  let off = 12, rate = 0, channels = 1, bits = 16, pcm = null;
  while (off + 8 <= b.length) {
    const id = b.toString("ascii", off, off + 4), size = b.readUInt32LE(off + 4);
    if (id === "fmt ") { channels = b.readUInt16LE(off + 10); rate = b.readUInt32LE(off + 12); bits = b.readUInt16LE(off + 22); }
    if (id === "data") { pcm = b.subarray(off + 8, Math.min(b.length, off + 8 + size)); break; }
    off += 8 + size + (size & 1);
  }
  if (!pcm || bits !== 16 || channels !== 1) throw new Error(`unexpected WAV from Flite (${bits} bit, ${channels} ch)`);
  return { rate, pcm: Buffer.from(pcm) };
}

const clamp = (v, lo, hi) => (Number.isFinite(v) ? Math.min(hi, Math.max(lo, v)) : 1);

module.exports = { FliteTts, VOICES, CHANNEL_RATE, MAX_TEXT, parseWav };
