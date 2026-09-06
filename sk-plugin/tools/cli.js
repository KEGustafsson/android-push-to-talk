#!/usr/bin/env node
// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Development tool: the plugin's pieces from a shell, no Signal K needed.
 *
 *   node tools/cli.js roster --key KEY [--name NAME] [--seconds 10]            join, print the roster, leave
 *   node tools/cli.js say    --key KEY --text "..." [--voice slt] [--rate 1]    speak a text on the channel
 *   node tools/cli.js say    --key KEY --wav FILE                                speak a WAV (PCM16, any rate) on the channel
 *   node tools/cli.js tts    --text "..." --out FILE.wav [--voice slt] [--rate 1] speech to a WAV file, no network
 *
 * Common: --group 239.255.42.1 --udp 47474 --iface auto --hops 4 [--unicast HOST,HOST] [--lead 100] [--no-chime].
 */

const fs = require("node:fs");
const { ChannelCrypto } = require("../lib/crypto");
const { LanLink } = require("../lib/lan");
const { ChannelNode } = require("../lib/node");
const { FliteTts, VOICES } = require("../lib/tts");
const { resample, bytesToSamples, samplesToBytes, toMono } = require("../lib/resample");
const tones = require("../lib/tones");

const args = parseArgs(process.argv.slice(2));
const cmd = args._[0];
const log = (m) => console.log(`${new Date().toISOString().slice(11, 23)} ${m}`);

main().catch((e) => { console.error(e.message); process.exit(1); });

async function main() {
  switch (cmd) {
    case "roster": {
      const { node, link } = await join();
      node.on("roster", (r) => log(`roster: ${r.map(fmt).join(", ") || "(nobody)"}`));
      node.on("talking", (on, id) => log(`talking ${on ? "start" : "stop"} #${(id >>> 0).toString(16)}`));
      await sleep((Number(args.seconds) || 10) * 1000);
      log(`stats ${JSON.stringify(node.stats)}`);
      node.stop(); link.close();
      break;
    }
    case "say": {
      if (args.wav !== undefined && !(typeof args.wav === "string" && args.wav)) throw new Error("--wav needs a file name");
      const speech = args.wav ? wavSamples(readWav(args.wav)) : bytesToSamples(await speak(args.text));
      const parts = args.chime !== false ? [tones.chime(), speech, tones.silence(150)] : [speech, tones.silence(150)];
      const pcm = samplesToBytes(tones.concat(parts));
      const { node, link } = await join();
      await sleep(1500); // let the phones hear our hello first
      log(`speaking ${(pcm.length / 640 / 50).toFixed(1)} s to ${node.roster().map(fmt).join(", ") || "(nobody)"}`);
      await node.waitForSilence(300, 2000);
      await node.speak(pcm);
      await sleep(500);
      node.stop(); link.close();
      break;
    }
    case "tts": {
      if (typeof args.out !== "string" || !args.out) throw new Error("--out FILE.wav is required");
      const pcm = await speak(args.text);
      fs.writeFileSync(args.out, wavFile(pcm, 16000));
      log(`wrote ${args.out}: ${(pcm.length / 32000).toFixed(1)} s`);
      break;
    }
    default:
      console.log(fs.readFileSync(__filename, "utf8").split("\n").slice(4, 14).join("\n"));
  }
}

async function speak(text) {
  if (typeof text !== "string" || !text.trim()) throw new Error("--text is required");
  const voice = args.voice || "slt";
  if (!VOICES.includes(voice)) throw new Error(`--voice must be one of ${VOICES.join(", ")}`);
  const tts = new FliteTts({ voice, rate: args.rate !== undefined ? Number(args.rate) : 1 });
  const t0 = Date.now();
  const pcm = await tts.synthesize(text);
  log(`${voice}: "${FliteTts.normalise(text)}" in ${Date.now() - t0} ms, ${(pcm.length / 32000).toFixed(1)} s of speech`);
  return pcm;
}

async function join() {
  if (!args.key) throw new Error("--key is required");
  const link = new LanLink({ group: args.group || "239.255.42.1", port: Number(args.udp) || 47474, iface: args.iface || "auto" });
  const where = await link.open();
  log(`network ${where.iface} ${where.address} broadcast ${where.broadcast}`);
  if (args.unicast) {   // extra unicast targets besides the nodes learnt from hellos (a host that filters multicast)
    const hosts = String(args.unicast).split(",");
    const send = link.send.bind(link);
    link.send = (buf, unicast = []) => send(buf, [...new Set([...unicast, ...hosts])]);
    log(`also unicasting to ${hosts.join(", ")}`);
  }
  const node = new ChannelNode({ name: args.name || "Laptop", crypto: ChannelCrypto.forChannelKey(args.key), link, ttl: Number(args.hops) || 4,
    leadMs: args.lead !== undefined ? Number(args.lead) : undefined, repeatMs: args.repeat !== undefined ? Number(args.repeat) : undefined });
  node.start();
  return { node, link };
}

function wavSamples(wav) {
  return resample(toMono(wav.samples, wav.channels), wav.rate, 16000);
}

/** Minimal RIFF/WAVE reader for PCM16. */
function readWav(file) {
  const b = fs.readFileSync(file);
  if (b.toString("ascii", 0, 4) !== "RIFF" || b.toString("ascii", 8, 12) !== "WAVE") throw new Error("not a WAV file");
  let off = 12, rate = 0, channels = 0, bits = 0, pcm = null;
  while (off + 8 <= b.length) {
    const id = b.toString("ascii", off, off + 4), size = b.readUInt32LE(off + 4);
    if (id === "fmt ") { channels = b.readUInt16LE(off + 10); rate = b.readUInt32LE(off + 12); bits = b.readUInt16LE(off + 22); }
    if (id === "data") { pcm = b.subarray(off + 8, off + 8 + size); break; }
    off += 8 + size + (size & 1);
  }
  if (!pcm || bits !== 16) throw new Error(`unsupported WAV (bits=${bits})`);
  return { rate, channels, pcm, samples: bytesToSamples(pcm) };
}

function wavFile(pcm, rate) {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0); h.writeUInt32LE(36 + pcm.length, 4); h.write("WAVE", 8); h.write("fmt ", 12);
  h.writeUInt32LE(16, 16); h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(rate, 24);
  h.writeUInt32LE(rate * 2, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34); h.write("data", 36); h.writeUInt32LE(pcm.length, 40);
  return Buffer.concat([h, pcm]);
}

function fmt(n) { return `${n.name}${n.talking ? " (talking)" : ""}${n.hops ? ` +${n.hops}` : ""}`; }
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function parseArgs(argv) {
  const out = { _: [] };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a.startsWith("--no-")) out[a.slice(5)] = false;
    else if (a.startsWith("--")) { const k = a.slice(2); const v = argv[i + 1]; if (v !== undefined && !v.startsWith("--")) { out[k] = v; i++; } else out[k] = true; }
    else out._.push(a);
  }
  return out;
}
