#!/usr/bin/env node
// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * Development tool: the plugin's channel node and satellite from a shell, no Signal K needed.
 *
 *   node tools/cli.js roster  --key KEY [--name NAME] [--seconds 10]        join, print the roster, leave
 *   node tools/cli.js say     --key KEY --wav FILE [--name NAME] [--no-chime] speak a WAV on the channel
 *   node tools/cli.js serve   --key KEY [--port 10701] [--bind 127.0.0.1]    join and run the Wyoming satellite (no auth: loopback unless told)
 *   node tools/cli.js send    --to HOST:PORT --wav FILE                       act as the orchestrator: stream a WAV to a satellite
 *
 * Common: --group 239.255.42.1 --udp 47474 --iface auto --hops 4 [--unicast HOST,HOST]. WAV: PCM16, any rate, mono or stereo.
 */

const fs = require("node:fs");
const net = require("node:net");
const { ChannelCrypto } = require("../lib/crypto");
const { LanLink } = require("../lib/lan");
const { ChannelNode } = require("../lib/node");
const { SatelliteServer, encodeEvent, EventDecoder } = require("../lib/wyoming");
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
      const pcm = prepare(readWav(args.wav), args.chime !== false);
      const { node, link } = await join();
      await sleep(1500); // let the phones hear our hello first
      log(`speaking ${(pcm.length / 640 / 50).toFixed(1)} s to ${node.roster().map(fmt).join(", ") || "(nobody)"}`);
      await node.waitForSilence(300, 2000);
      await node.speak(pcm);
      await sleep(500);
      node.stop(); link.close();
      break;
    }
    case "serve": {
      const { node } = await join();
      node.on("roster", (r) => log(`roster: ${r.map(fmt).join(", ") || "(nobody)"}`));
      const sat = new SatelliteServer({
        identity: { name: args.name || "Laptop", version: "cli" },
        log,
        play: async (audio) => {
          log(`announcement: ${audio.rate} Hz x${audio.channels}, ${Buffer.concat(audio.chunks).length} bytes`);
          let s = toMono(bytesToSamples(Buffer.concat(audio.chunks)), audio.channels);
          s = resample(s, audio.rate, 16000);
          const pcm = samplesToBytes(tones.concat([tones.chime(), s, tones.silence(150)]));
          await node.waitForSilence(300, 2000);
          await node.speak(pcm);
          log("announcement done");
        },
      });
      sat.on("connect", () => log("orchestrator connected"));
      sat.on("disconnect", () => log("orchestrator disconnected"));
      const bind = args.bind || "127.0.0.1";
      if (bind !== "127.0.0.1") log(`WARNING: the Wyoming protocol has no authentication; anyone who can reach ${bind}:${args.port || 10701} can speak on the channel`);
      const a = await sat.listen(Number(args.port) || 10701, bind);
      log(`satellite listening on ${a.address}:${a.port}; Ctrl-C to stop`);
      await new Promise(() => {});
      break;
    }
    case "send": {
      const [host, port] = String(args.to || "127.0.0.1:10701").split(":");
      const wav = readWav(args.wav);
      const sock = net.connect(Number(port), host);
      await new Promise((r, j) => sock.once("connect", r).once("error", j));
      const dec = new EventDecoder();
      const got = [];
      sock.on("data", (b) => { for (const e of dec.feed(b)) { got.push(e); log(`<- ${e.type} ${JSON.stringify(e.data)}`); } });
      const send = (t, d, p) => { sock.write(encodeEvent(t, d, p)); log(`-> ${t}`); };
      send("describe");
      await sleep(200);
      send("pause-satellite");
      const fmtd = { rate: wav.rate, width: 2, channels: wav.channels };
      send("audio-start", { ...fmtd, timestamp: 0 });
      const chunk = wav.rate * wav.channels * 2 / 10; // 100 ms
      for (let off = 0; off < wav.pcm.length; off += chunk) {
        sock.write(encodeEvent("audio-chunk", fmtd, wav.pcm.subarray(off, off + chunk)));
        await sleep(25); // ~4x real time, as the orchestrator paces
      }
      send("audio-stop");
      const t0 = Date.now();
      while (!got.some((e) => e.type === "played") && Date.now() - t0 < 60000) await sleep(50);
      log(got.some((e) => e.type === "played") ? "played acknowledged" : "no played within 60 s");
      sock.destroy();
      break;
    }
    default:
      console.log(fs.readFileSync(__filename, "utf8").split("\n").slice(4, 13).join("\n"));
  }
}

async function join() {
  if (!args.key) throw new Error("--key is required");
  const link = new LanLink({ group: args.group || "239.255.42.1", port: Number(args.udp) || 47474, iface: args.iface || "auto" });
  const where = await link.open();
  log(`WLAN ${where.iface} ${where.address} broadcast ${where.broadcast}`);
  if (args.unicast) {   // debugging on a network that filters multicast and broadcast: also send straight to these hosts
    const hosts = String(args.unicast).split(",");
    const send = link.send.bind(link);
    link.send = (buf) => { for (const h of hosts) link.sock?.send(buf, link.port, h, () => {}); return send(buf); };
    log(`also unicasting to ${hosts.join(", ")}`);
  }
  const node = new ChannelNode({ name: args.name || "Laptop", crypto: ChannelCrypto.forChannelKey(args.key), link, ttl: Number(args.hops) || 4 });
  node.start();
  return { node, link };
}

function prepare(wav, chime) {
  let s = toMono(wav.samples, wav.channels);
  s = resample(s, wav.rate, 16000);
  const parts = chime ? [tones.chime(), s, tones.silence(150)] : [s, tones.silence(150)];
  return samplesToBytes(tones.concat(parts));
}

/** Minimal RIFF/WAVE reader for PCM16. */
function readWav(file) {
  if (!file) throw new Error("--wav is required");
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
