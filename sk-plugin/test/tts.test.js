// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const os = require("node:os");
const path = require("node:path");
const fs = require("node:fs");
const { FliteTts, VOICES, parseWav } = require("../lib/tts");

const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), "crewradio-tts-"));

test("normalise: whitespace, the length cap, and units after numbers", () => {
  assert.equal(FliteTts.normalise("  Depth   2.5 m,\nwind 25 kn  "), "Depth 2.5 metres, wind 25 knots");
  assert.equal(FliteTts.normalise("Battery 12.2V, 45 %"), "Battery 12.2 volts, 45 percent");
  assert.equal(FliteTts.normalise("Heading 270°, 18°C"), "Heading 270 degrees, 18 degrees");
  assert.equal(FliteTts.normalise("a 5 km/h drift, 3 nm off"), "a 5 kilometres per hour drift, 3 nautical miles off");
  assert.equal(FliteTts.normalise(""), "");
  assert.equal(FliteTts.normalise(null), "");
  assert.equal(FliteTts.normalise("x".repeat(600)).length, 500);
  assert.equal(FliteTts.normalise("Amps at 5 A and 5 Ah"), "Amps at 5 amps and 5 amp hours");
  assert.equal(FliteTts.normalise("Sam is here"), "Sam is here", "no unit expansion without a number");
});

test("an unknown voice is refused up front", () => {
  assert.throws(() => new FliteTts({ voice: "nope" }), /unknown Flite voice/);
  assert.deepEqual(VOICES, ["slt", "kal16", "rms", "awb"]);
});

test("Flite in WebAssembly speaks a sentence as 16 kHz PCM, and the cache answers the repeat", async () => {
  const tts = new FliteTts({ voice: "slt", tempDir });
  const t0 = Date.now();
  const pcm = await tts.synthesize("Anchor alarm. The anchor is dragging.");
  const ms = Date.now() - t0;
  assert.ok(pcm.length > 16000 * 2 * 1.5, `at least 1.5 s of speech, got ${pcm.length} bytes`);
  assert.ok(pcm.length < 16000 * 2 * 6, "and under 6 s");
  assert.equal(pcm.length % 2, 0);
  let peak = 0;
  for (let i = 0; i < pcm.length; i += 2) peak = Math.max(peak, Math.abs(pcm.readInt16LE(i)));
  assert.ok(peak > 3000, `not silence (peak ${peak})`);
  assert.equal(tts.stats.synthesized, 1);
  const again = await tts.synthesize("Anchor alarm.  The anchor is dragging. ");
  assert.equal(again, pcm, "same normalised text: the very same buffer, from the cache");
  assert.equal(tts.stats.cached, 1);
  assert.equal(fs.readdirSync(tempDir).length, 0, "no WAV left behind");
  assert.ok(ms < 20000, `synthesis took ${ms} ms`);
});

test("an empty text is no speech, and the rate stretches the speech", async () => {
  const tts = new FliteTts({ voice: "kal16", tempDir });
  assert.equal((await tts.synthesize("   ")).length, 0);
  const slow = new FliteTts({ voice: "kal16", rate: 0.7, tempDir });
  const a = await tts.synthesize("Man overboard, port side.");
  const b = await slow.synthesize("Man overboard, port side.");
  assert.ok(b.length > a.length * 1.2, `slower speech is longer: ${b.length} vs ${a.length}`);
});

test("the cache is bounded by bytes", async () => {
  const tts = new FliteTts({ voice: "slt", tempDir, cacheBytes: 100_000 });
  await tts.synthesize("One.");
  await tts.synthesize("Two.");
  await tts.synthesize("Three.");
  assert.ok(tts.cacheSize <= 100_000);
  assert.ok(tts.cache.size >= 1);
});

test("parseWav reads a PCM16 mono file and refuses others", () => {
  const h = Buffer.alloc(44);
  h.write("RIFF", 0); h.writeUInt32LE(40, 4); h.write("WAVE", 8); h.write("fmt ", 12); h.writeUInt32LE(16, 16);
  h.writeUInt16LE(1, 20); h.writeUInt16LE(1, 22); h.writeUInt32LE(16000, 24); h.writeUInt32LE(32000, 28); h.writeUInt16LE(2, 32); h.writeUInt16LE(16, 34);
  h.write("data", 36); h.writeUInt32LE(4, 40);
  const wav = Buffer.concat([h, Buffer.from([1, 0, 2, 0])]);
  const r = parseWav(wav);
  assert.equal(r.rate, 16000);
  assert.deepEqual([...r.pcm], [1, 0, 2, 0]);
  assert.throws(() => parseWav(Buffer.from("not a wav file at all, really not")), /no WAV/);
  const stereo = Buffer.from(wav); stereo.writeUInt16LE(2, 22);
  assert.throws(() => parseWav(stereo), /unexpected WAV/);
});
