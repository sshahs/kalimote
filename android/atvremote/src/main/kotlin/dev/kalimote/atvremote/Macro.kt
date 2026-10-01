package dev.kalimote.atvremote

import java.util.Locale

/**
 * Macros: small scripts of remote actions, one step per line (or comma
 * separated). Same syntax as the web server (server/src/macros.js):
 *
 *     HOME                 press a key
 *     DPAD_DOWN x3         press a key several times
 *     hold DPAD_CENTER 1s  long-press a key
 *     wait 500             pause (ms, or "1.5s")
 *     text hello world     type into the focused text field
 *     open https://...     open an app link / deep link
 *     # comment
 */
sealed interface MacroStep {
    data class Key(val code: Int, val times: Int = 1) : MacroStep
    data class Hold(val code: Int, val ms: Long) : MacroStep
    data class Wait(val ms: Long) : MacroStep
    data class Text(val text: String) : MacroStep
    data class Open(val url: String) : MacroStep
}

class MacroException(message: String) : IllegalArgumentException(message)

object Macro {
    private const val KEY_DELAY_MS = 150L
    private const val MAX_STEPS = 500
    private const val MAX_WAIT_MS = 10 * 60 * 1000L

    val KEYS: Map<String, Int> = buildMap {
        put("HOME", KeyCodes.HOME); put("BACK", KeyCodes.BACK)
        for (n in 0..9) put("DIGIT_$n", KeyCodes.digit(n))
        put("DPAD_UP", KeyCodes.DPAD_UP); put("DPAD_DOWN", KeyCodes.DPAD_DOWN)
        put("DPAD_LEFT", KeyCodes.DPAD_LEFT); put("DPAD_RIGHT", KeyCodes.DPAD_RIGHT)
        put("DPAD_CENTER", KeyCodes.DPAD_CENTER)
        put("VOLUME_UP", KeyCodes.VOLUME_UP); put("VOLUME_DOWN", KeyCodes.VOLUME_DOWN)
        put("POWER", KeyCodes.POWER); put("ENTER", KeyCodes.ENTER); put("DEL", KeyCodes.DEL)
        put("MENU", KeyCodes.MENU); put("SEARCH", KeyCodes.SEARCH)
        put("MEDIA_PLAY_PAUSE", KeyCodes.MEDIA_PLAY_PAUSE); put("MEDIA_STOP", KeyCodes.MEDIA_STOP)
        put("MEDIA_NEXT", KeyCodes.MEDIA_NEXT); put("MEDIA_PREVIOUS", KeyCodes.MEDIA_PREVIOUS)
        put("MEDIA_REWIND", KeyCodes.MEDIA_REWIND); put("MEDIA_FAST_FORWARD", KeyCodes.MEDIA_FAST_FORWARD)
        put("MUTE", 91); put("MEDIA_PLAY", 126); put("MEDIA_PAUSE", 127)
        put("VOLUME_MUTE", KeyCodes.VOLUME_MUTE); put("INFO", KeyCodes.INFO)
        put("CHANNEL_UP", KeyCodes.CHANNEL_UP); put("CHANNEL_DOWN", KeyCodes.CHANNEL_DOWN)
        put("GUIDE", KeyCodes.GUIDE); put("CAPTIONS", KeyCodes.CAPTIONS)
        put("SETTINGS", KeyCodes.SETTINGS); put("TV_INPUT", KeyCodes.TV_INPUT)
        put("PROG_RED", KeyCodes.PROG_RED); put("PROG_GREEN", KeyCodes.PROG_GREEN)
        put("PROG_YELLOW", KeyCodes.PROG_YELLOW); put("PROG_BLUE", KeyCodes.PROG_BLUE)
        put("ASSIST", KeyCodes.ASSIST); put("MEDIA_AUDIO_TRACK", KeyCodes.MEDIA_AUDIO_TRACK); put("SLEEP", 223); put("WAKEUP", 224)
    }

    fun resolveKey(name: String): Int {
        name.toIntOrNull()?.let { if (it > 0) return it }
        val key = name.uppercase(Locale.ROOT).removePrefix("KEYCODE_")
        return KEYS[key] ?: throw MacroException("Unknown key: $name")
    }

    private fun duration(s: String): Long {
        val m = Regex("^(\\d+(?:\\.\\d+)?)\\s*(ms|s|m)?$", RegexOption.IGNORE_CASE).find(s.trim())
            ?: throw MacroException("Invalid duration: $s")
        val n = m.groupValues[1].toDouble()
        val ms = when (m.groupValues[2].lowercase(Locale.ROOT)) {
            "m" -> n * 60_000
            "s" -> n * 1000
            else -> n
        }.toLong()
        if (ms > MAX_WAIT_MS) throw MacroException("Waits are limited to 10 minutes")
        return ms
    }

    fun parse(script: String): List<MacroStep> {
        val steps = ArrayList<MacroStep>()
        script.lines().forEachIndexed { i, raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            // Text and links may contain commas, so only split other lines.
            val parts = if (Regex("^(text|type|open|launch)\\b", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                listOf(line)
            } else {
                line.split(",")
            }
            for (part in parts.map { it.trim() }.filter { it.isNotEmpty() }) {
                try {
                    steps += step(part)
                } catch (e: MacroException) {
                    throw MacroException("Line ${i + 1}: ${e.message}")
                }
            }
        }
        if (steps.size > MAX_STEPS) throw MacroException("Macros are limited to $MAX_STEPS steps")
        return steps
    }

    private fun step(p: String): MacroStep {
        val word = p.split(Regex("\\s+")).first()
        val arg = p.substring(word.length).trim()
        return when (word.lowercase(Locale.ROOT)) {
            "wait", "sleep", "delay" -> MacroStep.Wait(duration(arg))
            "text", "type" -> {
                if (arg.isEmpty()) throw MacroException("text needs a value")
                MacroStep.Text(arg.removeSurrounding("\""))
            }
            "open", "launch" -> {
                if (!Regex("^[a-z][\\w+.-]*:", RegexOption.IGNORE_CASE).containsMatchIn(arg)) {
                    throw MacroException("open needs a link such as https://… or market://…")
                }
                MacroStep.Open(arg)
            }
            "hold" -> {
                val rest = arg.split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (rest.isEmpty()) throw MacroException("hold needs a key")
                MacroStep.Hold(resolveKey(rest[0]), if (rest.size > 1) duration(rest[1]) else 1000)
            }
            else -> {
                val m = Regex("^(\\S+?)(?:\\s*[x*]\\s*(\\d+))?$", RegexOption.IGNORE_CASE).find(p)
                    ?: throw MacroException("Cannot understand \"$p\"")
                val times = m.groupValues[2].toIntOrNull() ?: 1
                if (times !in 1..100) throw MacroException("Repeat count must be 1-100")
                MacroStep.Key(resolveKey(m.groupValues[1]), times)
            }
        }
    }

    /**
     * Runs steps on [client], blocking the calling thread (use a background
     * thread). Stops early when [cancelled] returns true.
     */
    fun run(client: RemoteClient, steps: List<MacroStep>, cancelled: () -> Boolean = { false }) {
        fun pause(ms: Long) {
            val end = System.currentTimeMillis() + ms
            while (System.currentTimeMillis() < end) {
                if (cancelled()) throw InterruptedException("Macro cancelled")
                Thread.sleep(minOf(50L, end - System.currentTimeMillis()).coerceAtLeast(1))
            }
        }
        for (step in steps) {
            if (cancelled()) throw InterruptedException("Macro cancelled")
            when (step) {
                is MacroStep.Key -> repeat(step.times) {
                    client.sendKey(step.code)
                    pause(KEY_DELAY_MS)
                }
                is MacroStep.Hold -> {
                    client.sendKey(step.code, Direction.START_LONG)
                    pause(step.ms)
                    client.sendKey(step.code, Direction.END_LONG)
                    pause(KEY_DELAY_MS)
                }
                is MacroStep.Wait -> pause(step.ms)
                is MacroStep.Text -> {
                    client.sendText(step.text)
                    pause(KEY_DELAY_MS)
                }
                is MacroStep.Open -> {
                    client.launchApp(step.url)
                    pause(KEY_DELAY_MS)
                }
            }
        }
    }
}
