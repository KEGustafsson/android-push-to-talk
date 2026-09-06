// SPDX-License-Identifier: EUPL-1.2
"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const { LanLink, chooseInterface, broadcastOf } = require("../lib/lan");

test("broadcast address from address and netmask", () => {
  assert.equal(broadcastOf("192.168.0.34", "255.255.255.0"), "192.168.0.255");
  assert.equal(broadcastOf("10.1.2.3", "255.255.0.0"), "10.1.255.255");
  assert.equal(broadcastOf("172.16.5.9", "255.255.255.240"), "172.16.5.15");
  assert.equal(broadcastOf("192.168.0.34", undefined), "192.168.0.255");
  assert.equal(broadcastOf("bad", "255.255.255.0"), null);
});

test("chooseInterface: a named interface that does not exist is null; auto prefers Wi-Fi names", () => {
  assert.equal(chooseInterface("no-such-interface-xyz"), null);
  const auto = chooseInterface(null);
  if (auto) {
    assert.match(auto.address, /^\d+\.\d+\.\d+\.\d+$/);
    assert.equal(chooseInterface(auto.name).address, auto.address);
  }
});

test("opening a link on the machine's interface binds, joins the group and can send; closing is idempotent", async (t) => {
  const pick = chooseInterface(null);
  if (!pick) { t.skip("no IPv4 interface on this machine"); return; }
  const link = new LanLink({ group: "239.255.42.1", port: 40000 + Math.floor(Math.random() * 20000), iface: pick.name });
  let where;
  try {
    where = await link.open();
  } catch (e) {
    t.skip(`multicast not available here: ${e.message}`);
    return;
  }
  try {
    assert.equal(where.iface, pick.name);
    assert.equal(where.address, pick.address);
    assert.equal(link.send(Buffer.from("hello")), true);
  } finally {
    link.close();
    link.close();
  }
  assert.equal(link.send(Buffer.from("x")), false, "closed links do not send");
});

test("send() goes to the group, the broadcast address and each unicast target", () => {
  const link = new LanLink({ group: "239.255.42.1", port: 47474 });
  const sent = [];
  link.sock = { send: (buf, port, addr) => sent.push(`${addr}:${port}`) };
  link.broadcast = "192.168.0.255";
  assert.equal(link.send(Buffer.from("x"), ["192.168.0.30", "192.168.0.35"]), true);
  assert.deepEqual(sent, ["239.255.42.1:47474", "192.168.0.255:47474", "192.168.0.30:47474", "192.168.0.35:47474"]);
  sent.length = 0;
  link.send(Buffer.from("x"));
  assert.deepEqual(sent, ["239.255.42.1:47474", "192.168.0.255:47474"]);
});

test("a bad group address rejects open() instead of throwing later", async (t) => {
  const pick = chooseInterface(null);
  if (!pick) { t.skip("no IPv4 interface on this machine"); return; }
  const link = new LanLink({ group: "not-an-address", port: 40000 + Math.floor(Math.random() * 20000), iface: pick.name });
  await assert.rejects(link.open());
});

test("an unknown named interface rejects open()", async () => {
  const link = new LanLink({ group: "239.255.42.1", port: 47474, iface: "no-such-interface-xyz" });
  await assert.rejects(link.open(), /no IPv4 address/);
});
