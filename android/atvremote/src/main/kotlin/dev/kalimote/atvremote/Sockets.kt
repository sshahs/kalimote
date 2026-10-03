package dev.kalimote.atvremote

import java.io.Closeable

internal object Sockets {
    /**
     * Closes [c] on a background thread. Closing a TLS socket sends a
     * close_notify alert, which is network I/O: on Android's main thread that
     * throws NetworkOnMainThreadException (e.g. from Activity.onStop).
     */
    fun closeInBackground(c: Closeable?) {
        if (c == null) return
        Thread({ runCatching { c.close() } }, "atvremote-close").apply { isDaemon = true }.start()
    }
}
