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
