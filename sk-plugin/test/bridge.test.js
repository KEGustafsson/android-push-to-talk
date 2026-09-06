// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { NotificationBridge, humanise, globToRegExp } = require("../lib/bridge");

function harness(rules, sayImpl) {
  let clock = 0;
  const said = [];
  const bridge = new NotificationBridge({
    say: sayImpl ?? (async (o) => { said.push(o); return { ok: true, queued: 0 }; }),
    rules: { ...rules },
    now: () => clock,
  });
  const delta = (path, value) => bridge.onDelta({ updates: [{ values: [{ path, value }] }] });
  const flush = () => new Promise((r) => setImmediate(r));
  return { bridge, said, delta, flush, advance: (ms) => (clock += ms) };
}

test("an alarm with sound is announced once on raise, urgent for emergency, and stops when cleared", async () => {
  const h = harness({ repeatSec: 0 });
  h.delta("notifications.navigation.anchor", { state: "alarm", method: ["visual", "sound"], message: "Anchor is dragging" });
  h.delta("notifications.navigation.anchor", { state: "alarm", method: ["visual", "sound"], message: "Anchor is dragging" });
  await h.flush();
  assert.equal(h.said.length, 1);
  assert.deepEqual(h.said[0], { text: "Anchor is dragging", priority: "normal" });
  h.delta("notifications.mob", { state: "emergency", method: ["sound"], message: "Man overboard" });
  await h.flush();
  assert.equal(h.said[1].priority, "urgent");
  h.delta("notifications.navigation.anchor", { state: "normal", method: [], message: "" });
  h.delta("notifications.mob", null);
  assert.equal(h.bridge.active.size, 0);
});

test("below the state, without sound, or excluded: nothing is said", async () => {
  const h = harness({ minState: "alarm", include: ["navigation.**"], exclude: ["navigation.courseOverGround"] });
  h.delta("notifications.navigation.depth", { state: "warn", method: ["sound"], message: "shallow" });
  h.delta("notifications.navigation.depth", { state: "alarm", method: ["visual"], message: "shallow" });
  h.delta("notifications.electrical.batteries.house", { state: "alarm", method: ["sound"], message: "low battery" });
  h.delta("notifications.navigation.courseOverGround", { state: "alarm", method: ["sound"], message: "x" });
  await h.flush();
  assert.equal(h.said.length, 0);
  h.delta("notifications.navigation.depth", { state: "alarm", method: ["sound"], message: "shallow" });
  await h.flush();
  assert.equal(h.said.length, 1);
});

test("a raised alarm repeats every repeatSec until it clears; a changed message is said at once", async () => {
  const h = harness({ repeatSec: 30 });
  h.delta("notifications.navigation.anchor", { state: "alarm", method: ["sound"], message: "Dragging 10 m" });
  await h.flush();
  h.advance(29_000);
  h.bridge.repeatDue();
  await h.flush();
  assert.equal(h.said.length, 1);
  h.advance(1_000);
  h.bridge.repeatDue();
  await h.flush();
  assert.equal(h.said.length, 2);
  h.delta("notifications.navigation.anchor", { state: "alarm", method: ["sound"], message: "Dragging 25 m" });
  await h.flush();
  assert.equal(h.said.length, 3);
  assert.equal(h.said[2].text, "Dragging 25 m");
  h.delta("notifications.navigation.anchor", { state: "normal", method: [], message: "" });
  h.advance(60_000);
  h.bridge.repeatDue();
  await h.flush();
  assert.equal(h.said.length, 3);
});

test("a failed say is retried in 5 s rather than a full repeat later; no message falls back to the path", async () => {
  let fail = true;
  const calls = [];
  const h = harness({ repeatSec: 60 }, async (o) => { calls.push(o); if (fail) throw new Error("engine down"); return { ok: true }; });
  h.delta("notifications.propulsion.port.temperature", { state: "alarm", method: ["sound"] });
  await h.flush();
  assert.equal(calls[0].text, "propulsion port temperature");
  fail = false;
  h.advance(5_000);
  h.bridge.repeatDue();
  await h.flush();
  assert.equal(calls.length, 2);
});

test("helpers: humanise and globs", () => {
  assert.equal(humanise("notifications.navigation.anchor.currentRadius"), "navigation anchor current radius");
  assert.ok(globToRegExp("navigation.*").test("navigation.anchor"));
  assert.ok(!globToRegExp("navigation.*").test("navigation.anchor.radius"));
  assert.ok(globToRegExp("navigation.**").test("navigation.anchor.radius"));
  assert.ok(globToRegExp("mob").test("mob"));
});
