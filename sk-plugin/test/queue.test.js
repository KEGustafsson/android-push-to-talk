// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { AnnouncementQueue } = require("../lib/queue");

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function harness(playMs = 30) {
  const played = [];
  let cancels = 0;
  const q = new AnnouncementQueue({
    play: async (item, cancelled) => {
      const t0 = Date.now();
      while (Date.now() - t0 < playMs) { if (cancelled()) { played.push(`${item}!`); return; } await sleep(2); }
      played.push(item);
    },
    onCancel: () => cancels++,
    max: 3,
  });
  return { q, played, cancels: () => cancels };
}

test("items play one after another in order, and positions count what is ahead", async () => {
  const { q, played } = harness();
  assert.equal(q.enqueue("a"), 0);
  assert.equal(q.enqueue("b"), 1);
  assert.equal(q.enqueue("c"), 2);
  assert.equal(q.size, 2, "a is playing, two wait");
  await sleep(150);
  assert.deepEqual(played, ["a", "b", "c"]);
  assert.equal(q.size, 0);
});

test("an urgent item goes to the front and cuts a normal one short; urgent items keep their order", async () => {
  const { q, played, cancels } = harness(60);
  q.enqueue("a");
  q.enqueue("b");
  await sleep(10);
  assert.equal(q.enqueue("U1", "urgent"), 0);
  assert.equal(q.enqueue("U2", "urgent"), 1);
  await sleep(300);
  assert.deepEqual(played, ["a!", "U1", "U2", "b"]);
  assert.equal(cancels(), 1);
});

test("an urgent item does not interrupt another urgent one", async () => {
  const { q, played, cancels } = harness(40);
  q.enqueue("U1", "urgent");
  await sleep(5);
  q.enqueue("U2", "urgent");
  await sleep(150);
  assert.deepEqual(played, ["U1", "U2"]);
  assert.equal(cancels(), 0);
});

test("the queue is bounded for normal items, clear() drops the rest, stop() refuses more", async () => {
  const { q, played } = harness(40);
  q.enqueue("a"); q.enqueue("b"); q.enqueue("c"); q.enqueue("d");
  assert.throws(() => q.enqueue("e"), /queue full/);
  q.clear();
  await sleep(100);
  assert.deepEqual(played, ["a!"]);
  q.stop();
  assert.throws(() => q.enqueue("f"), /stopped/);
});

test("a failing play does not stop the queue", async () => {
  const played = [];
  const q = new AnnouncementQueue({ play: async (item) => { if (item === "bad") throw new Error("boom"); played.push(item); } });
  q.enqueue("bad"); q.enqueue("good");
  await sleep(30);
  assert.deepEqual(played, ["good"]);
});
