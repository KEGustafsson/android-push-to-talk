// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { resample, bytesToSamples, samplesToBytes, toMono } = require("../lib/resample");

function sine(rate, freq, ms, amp = 10000) {
  const n = Math.round((rate * ms) / 1000);
  const s = new Int16Array(n);
  for (let i = 0; i < n; i++) s[i] = Math.round(amp * Math.sin((2 * Math.PI * freq * i) / rate));
  return s;
}

function rms(s, from = 0, to = s.length) {
  let acc = 0;
  for (let i = from; i < to; i++) acc += s[i] * s[i];
  return Math.sqrt(acc / (to - from));
}

/** Dominant frequency by zero crossings, on the middle of the signal. */
function freqOf(s, rate) {
  const a = Math.floor(s.length * 0.2), b = Math.floor(s.length * 0.8);
  let crossings = 0;
  for (let i = a + 1; i < b; i++) if ((s[i - 1] < 0) !== (s[i] < 0)) crossings++;
  return (crossings / 2) / ((b - a) / rate);
}

test("22050 to 16000 keeps a 440 Hz tone's pitch and level", () => {
  const input = sine(22050, 440, 500);
  const out = resample(input, 22050, 16000);
  assert.equal(out.length, Math.floor(input.length * 16000 / 22050));
  assert.ok(Math.abs(freqOf(out, 16000) - 440) < 5, `pitch ${freqOf(out, 16000)}`);
  const a = rms(input, 1000, input.length - 1000), b = rms(out, 800, out.length - 800);
  assert.ok(Math.abs(a - b) / a < 0.05, `level ${a} vs ${b}`);
});

test("a tone above the new Nyquist is attenuated, not aliased", () => {
  const input = sine(22050, 9500, 500); // above 8 kHz: must not come back as a 2 kHz alias
  const out = resample(input, 22050, 16000);
  const level = rms(out, 800, out.length - 800);
  assert.ok(level < rms(input) * 0.15, `residual ${level}`);
});

test("same rate is a copy; 16000 to 22050 upsamples cleanly too", () => {
  const input = sine(16000, 1000, 100);
  assert.deepEqual(resample(input, 16000, 16000), input);
  const up = resample(input, 16000, 22050);
  assert.ok(Math.abs(freqOf(up, 22050) - 1000) < 15);
});

test("bytes and samples round-trip little-endian, an odd byte is dropped, stereo averages to mono", () => {
  const s = Int16Array.from([1, -2, 32767, -32768]);
  const b = samplesToBytes(s);
  assert.deepEqual([...b.subarray(0, 4)], [1, 0, 0xfe, 0xff]);
  assert.deepEqual(bytesToSamples(Buffer.concat([b, Buffer.alloc(1)])), s);
  assert.deepEqual(toMono(Int16Array.from([100, 300, -50, 50]), 2), Int16Array.from([200, 0]));
});
