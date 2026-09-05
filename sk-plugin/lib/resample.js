// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * PCM16 mono resampler for speech: Piper speaks at 22 050 Hz, the channel is 16 000 Hz.
 * Windowed-sinc interpolation (Blackman window, 16 taps a side) with the cutoff at the lower
 * of the two Nyquist frequencies, so downsampling does not alias. Pure JavaScript, no state
 * between calls: an announcement is resampled whole.
 */

const TAPS = 16;

/**
 * @param {Int16Array} input samples at `fromRate`
 * @returns {Int16Array} samples at `toRate`
 */
function resample(input, fromRate, toRate) {
  if (fromRate === toRate) return Int16Array.from(input);
  const ratio = fromRate / toRate;
  const cutoff = Math.min(1, 1 / ratio); // as a fraction of the input Nyquist
  const outLen = Math.floor(input.length / ratio);
  const out = new Int16Array(outLen);
  const window = ratio > 1 ? TAPS * ratio : TAPS;
  for (let i = 0; i < outLen; i++) {
    const centre = i * ratio;
    const lo = Math.max(0, Math.ceil(centre - window));
    const hi = Math.min(input.length - 1, Math.floor(centre + window));
    let acc = 0;
    let norm = 0;
    for (let j = lo; j <= hi; j++) {
      const x = (j - centre) * cutoff;
      const w = blackman((j - centre) / window);
      const s = sinc(x) * w * cutoff;
      acc += input[j] * s;
      norm += s;
    }
    const v = norm > 0 ? acc / norm : acc;
    out[i] = v > 32767 ? 32767 : v < -32768 ? -32768 : Math.round(v);
  }
  return out;
}

function sinc(x) {
  if (x === 0) return 1;
  const px = Math.PI * x;
  return Math.sin(px) / px;
}

/** Blackman window on [-1, 1]; zero outside. */
function blackman(t) {
  if (t <= -1 || t >= 1) return 0;
  const u = (t + 1) / 2; // 0..1
  return 0.42 - 0.5 * Math.cos(2 * Math.PI * u) + 0.08 * Math.cos(4 * Math.PI * u);
}

/** Little-endian PCM16 bytes to samples. An odd trailing byte is dropped. */
function bytesToSamples(buf) {
  const n = Math.floor(buf.length / 2);
  const s = new Int16Array(n);
  for (let i = 0; i < n; i++) s[i] = buf.readInt16LE(i * 2);
  return s;
}

/** Samples to little-endian PCM16 bytes. */
function samplesToBytes(samples) {
  const b = Buffer.alloc(samples.length * 2);
  for (let i = 0; i < samples.length; i++) b.writeInt16LE(samples[i], i * 2);
  return b;
}

/** Interleaved multi-channel PCM16 to mono by averaging. */
function toMono(samples, channels) {
  if (channels <= 1) return samples;
  const n = Math.floor(samples.length / channels);
  const out = new Int16Array(n);
  for (let i = 0; i < n; i++) {
    let acc = 0;
    for (let c = 0; c < channels; c++) acc += samples[i * channels + c];
    out[i] = Math.round(acc / channels);
  }
  return out;
}

module.exports = { resample, bytesToSamples, samplesToBytes, toMono };
