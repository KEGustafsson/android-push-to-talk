package fi.arabella.ptt.transport

import kotlin.concurrent.thread

/**
 * A named `ptt-*` transport thread whose uncaught exceptions are reported instead of
 * killing the process.
 *
 * Transport threads talk to the Bluetooth, Wi-Fi Aware and socket stacks, which throw
 * more than [java.io.IOException] — a missing runtime permission surfaces as
 * [SecurityException], a vendor stack can throw almost anything. An uncaught throwable on
 * a plain thread takes the whole app down, so every one of them ends up here and becomes
 * a status line instead.
 */
internal fun transportThread(name: String, onError: (Throwable) -> Unit, body: () -> Unit): Thread =
    thread(name = name) {
        try {
            body()
        } catch (t: Throwable) {
            onError(t)
        }
    }

/** Sleeps [ms]; false if interrupted, which is how a stopping transport ends a retry loop early. */
internal fun sleepQuietly(ms: Long): Boolean =
    try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }

/**
 * Runs [block] and turns anything it throws into a status line. For calls made from
 * framework callbacks on the main thread, where an exception would otherwise crash the app.
 */
internal inline fun reporting(onStatus: (String) -> Unit, what: String, block: () -> Unit) {
    try {
        block()
    } catch (t: Throwable) {
        onStatus("$what: ${t.message}")
    }
}

/** Node ids as they appear in status lines: unsigned hex, e.g. `58738d38`. */
internal fun hex(id: Int): String = id.toUInt().toString(16)
