package dev.kalimote.app

import android.content.Context
import dev.kalimote.atvremote.ClientIdentity
import java.io.File

/** The app's client certificate, generated once and shared by the UI and the sleep timer. */
object Identity {
    @Volatile
    private var cached: ClientIdentity? = null

    /** Blocking (key generation can take a moment); call off the main thread. */
    @Synchronized
    fun get(context: Context): ClientIdentity {
        cached?.let { return it }
        val file = File(context.applicationContext.filesDir, "identity")
        val loaded = runCatching { ClientIdentity.decode(file.readText()) }.getOrNull()
        val identity = loaded ?: ClientIdentity.generate("kalimote-android").also { file.writeText(it.encode()) }
        cached = identity
        return identity
    }
}
