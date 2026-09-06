// SPDX-License-Identifier: EUPL-1.2
"use strict";

/**
 * The announcement queue: one thing on the channel at a time, in the order asked, except that
 * an urgent announcement goes to the front and cuts short a normal one that is playing. Items
 * are whatever `play` understands; `play(item, cancelled)` must resolve when the item is done
 * and check `cancelled()` (or be interrupted through `onCancel`).
 */

const { EventEmitter } = require("node:events");

class AnnouncementQueue extends EventEmitter {
  /**
   * @param {object} opts
   * @param {(item: any, cancelled: () => boolean) => Promise<void>} opts.play
   * @param {() => void} [opts.onCancel]   asked to interrupt the item playing now
   * @param {number} [opts.max=20]         items waiting beyond this are refused
   * @param {(msg: string) => void} [opts.log]
   */
  constructor(opts) {
    super();
    this.play = opts.play;
    this.onCancel = opts.onCancel ?? (() => {});
    this.max = opts.max ?? 20;
    this.log = opts.log ?? (() => {});
    this.items = [];
    this.current = null;
    this.pumping = false;
    this.stopped = false;
  }

  get size() { return this.items.length; }

  /**
   * Adds an item. Returns its position: 0 = playing now (or next, when nothing plays), else the
   * number of items ahead of it. Throws when the queue is full.
   */
  enqueue(item, priority = "normal") {
    if (this.stopped) throw new Error("queue stopped");
    const entry = { item, priority: priority === "urgent" ? "urgent" : "normal", cancelled: false };
    if (entry.priority === "urgent") {
      const firstNormal = this.items.findIndex((e) => e.priority === "normal");
      const at = firstNormal < 0 ? this.items.length : firstNormal;
      this.items.splice(at, 0, entry);
      if (this.current && this.current.priority === "normal" && !this.current.cancelled) {
        this.log("urgent announcement: interrupting the one playing");
        this.current.cancelled = true;
        this.onCancel();
      }
      this.pump();
      return at;
    }
    if (this.items.length >= this.max) throw new Error(`queue full (${this.max} waiting)`);
    this.items.push(entry);
    this.pump();
    return this.items.length - 1 + (this.current ? 1 : 0);
  }

  /** Drops everything waiting and interrupts what plays. */
  clear() {
    this.items.length = 0;
    if (this.current) { this.current.cancelled = true; this.onCancel(); }
  }

  stop() {
    this.stopped = true;
    this.clear();
  }

  async pump() {
    if (this.pumping) return;
    this.pumping = true;
    try {
      while (this.items.length > 0 && !this.stopped) {
        const entry = this.items.shift();
        this.current = entry;
        this.emit("started", entry.item, entry.priority);
        try {
          await this.play(entry.item, () => entry.cancelled);
        } catch (e) {
          this.log(`announcement failed: ${e.message}`);
        } finally {
          this.current = null;
          this.emit("done", entry.item, entry.priority, entry.cancelled);
        }
      }
    } finally {
      this.pumping = false;
    }
  }
}

module.exports = { AnnouncementQueue };
