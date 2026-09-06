// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The LAN/WLAN link, the way the app's LanTransport does it (the server may sit on the wired LAN
 * that the phones' WLAN is bridged to; multicast and broadcast cross that bridge like any other frame): one UDP socket bound to the port on
 * every address, joined to the multicast group on the chosen interface, and every packet sent
 * twice, to the group and to the interface's IPv4 broadcast address, because plenty of access
 * points filter multicast. The receiving side drops the duplicate by (sender, seq).
 *
 * Events: 'packet' (buf, rinfo), 'error' (the socket is dead; the owner reopens it), 'listening'.
 */

const dgram = require("node:dgram");
const os = require("node:os");
const { EventEmitter } = require("node:events");
const { MAX_SIZE } = require("./packet");

class LanLink extends EventEmitter {
  /**
   * @param {{group: string, port: number, iface?: string}} opts iface: interface name, or "auto"/empty
   */
  constructor(opts) {
    super();
    this.group = opts.group;
    this.port = opts.port;
    this.ifaceName = opts.iface && opts.iface !== "auto" ? opts.iface : null;
    this.sock = null;
    this.address = null;
    this.broadcast = null;
    this.iface = null;
  }

  /** Resolves with `{iface, address, broadcast}` once bound and joined. */
  open() {
    const pick = chooseInterface(this.ifaceName);
    if (!pick) return Promise.reject(new Error(this.ifaceName ? `interface ${this.ifaceName} has no IPv4 address` : "no usable IPv4 interface"));
    return new Promise((resolve, reject) => {
      const sock = dgram.createSocket({ type: "udp4", reuseAddr: true });
      let bound = false;
      sock.on("error", (e) => {
        if (!bound) reject(e);
        else this.emit("error", e);
        this.close();
      });
      sock.on("message", (buf, rinfo) => {
        if (buf.length > MAX_SIZE) return; // dropped unread, as the app does
        this.emit("packet", buf, rinfo);
      });
      sock.bind(this.port, "0.0.0.0", () => {
        try {
          sock.setBroadcast(true);
          sock.setMulticastTTL(1);
          sock.setMulticastLoopback(false);
          sock.setMulticastInterface(pick.address);
          sock.addMembership(this.group, pick.address);
        } catch (e) {
          sock.close();
          reject(e);
          return;
        }
        bound = true;
        this.sock = sock;
        this.iface = pick.name;
        this.address = pick.address;
        this.broadcast = pick.broadcast;
        this.emit("listening", { iface: pick.name, address: pick.address, broadcast: pick.broadcast });
        resolve({ iface: pick.name, address: pick.address, broadcast: pick.broadcast });
      });
    });
  }

  /**
   * Sends to the group, to the subnet broadcast and, when given, to each address in `unicast`:
   * an access point sends multicast and broadcast at its lowest rate without acknowledgement,
   * so phones lose a few percent of them even in the same cabin, whereas unicast is retried
   * and rate-adapted. The phones drop the copies they get twice by (sender, seq). Transient
   * failures are ignored, as in the app.
   */
  send(buf, unicast = []) {
    const s = this.sock;
    if (!s) return false;
    s.send(buf, this.port, this.group, () => {});
    if (this.broadcast) s.send(buf, this.port, this.broadcast, () => {});
    for (const a of unicast) s.send(buf, this.port, a, () => {});
    return true;
  }

  close() {
    const s = this.sock;
    this.sock = null;
    if (s) {
      try { s.close(); } catch { /* already closed */ }
    }
  }
}

/** Prefer the named interface; else a wlan interface, then eth or en, then any up non-internal IPv4 interface. */
function chooseInterface(name) {
  const all = os.networkInterfaces();
  const candidates = [];
  for (const [ifName, addrs] of Object.entries(all)) {
    for (const a of addrs ?? []) {
      if (a.family !== "IPv4" && a.family !== 4) continue;
      if (a.internal) continue;
      candidates.push({ name: ifName, address: a.address, broadcast: broadcastOf(a.address, a.netmask) });
    }
  }
  if (name) return candidates.find((c) => c.name === name) ?? null;
  const rank = (c) => (/^wl|wi-?fi|wlan/i.test(c.name) ? 0 : /^(eth|en)/i.test(c.name) ? 1 : 2);
  candidates.sort((a, b) => rank(a) - rank(b));
  return candidates[0] ?? null;
}

function broadcastOf(address, netmask) {
  const a = address.split(".").map(Number);
  const m = (netmask || "255.255.255.0").split(".").map(Number);
  if (a.length !== 4 || m.length !== 4) return null;
  return a.map((o, i) => (o & m[i]) | (~m[i] & 0xff)).join(".");
}

module.exports = { LanLink, chooseInterface, broadcastOf };
