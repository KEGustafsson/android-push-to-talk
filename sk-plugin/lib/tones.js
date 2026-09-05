// SPDX-License-Identifier: EUPL-1.2
"use strict";

/** Attention cues, 16 kHz mono PCM16, the way the app's Tones does it: short, clean, faded. */

const RATE = 16_000;

/** A sine burst with 5 ms fades. */
function tone(freq, ms, amplitude = 0.35) {
  const n = Math.round((RATE * ms) / 1000);
  const fade = Math.round(RATE * 0.005);
  const out = new Int16Array(n);
  for (let i = 0; i < n; i++) {
    const env = Math.min(1, i / fade, (n - 1 - i) / fade);
    out[i] = Math.round(Math.sin((2 * Math.PI * freq * i) / RATE) * amplitude * env * 32767);
  }
  return out;
}

function silence(ms) {
  return new Int16Array(Math.round((RATE * ms) / 1000));
}

function concat(parts) {
  const total = parts.reduce((a, p) => a + p.length, 0);
  const out = new Int16Array(total);
  let off = 0;
  for (const p of parts) {
    out.set(p, off);
    off += p.length;
  }
  return out;
}

/** Two rising notes before an announcement, then a short breath. */
function chime() {
  return concat([tone(880, 110), silence(30), tone(1175, 140), silence(180)]);
}

/** Three quick high notes before an urgent one. */
function urgentChime() {
  return concat([tone(1480, 90), silence(40), tone(1480, 90), silence(40), tone(1480, 160), silence(150)]);
}

module.exports = { RATE, tone, silence, concat, chime, urgentChime };
