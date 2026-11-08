package dev.kalimote.atvremote

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Mirrors server/test/features.test.js so both implementations agree. */
class FeaturesTest {

    @Test
    fun parseMacro() {
        val steps = Macro.parse(
            """
            # open YouTube and search
            HOME, wait 1s
            DPAD_DOWN x3
            hold DPAD_CENTER 2s
            text hello, world
            open https://www.youtube.com/watch?v=abc,def
            26*2
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                MacroStep.Key(3),
                MacroStep.Wait(1000),
                MacroStep.Key(20, 3),
                MacroStep.Hold(23, 2000),
                MacroStep.Text("hello, world"),
                MacroStep.Open("https://www.youtube.com/watch?v=abc,def"),
                MacroStep.Key(26, 2),
            ),
            steps,
        )
        assertTrue(assertFailsWith<MacroException> { Macro.parse("NOT_A_KEY") }.message!!.startsWith("Line 1: Unknown key"))
        assertTrue(assertFailsWith<MacroException> { Macro.parse("HOME\nwait forever") }.message!!.startsWith("Line 2: Invalid duration"))
        assertFailsWith<MacroException> { Macro.parse("open youtube") }
        assertEquals(listOf(MacroStep.Key(KeyCodes.HOME)), Macro.parse("keycode_home"))
    }

    @Test
    fun wakeOnLanPacket() {
        assertEquals("aa:bb:cc:dd:ee:ff", WakeOnLan.normalize("AA-BB-CC-DD-EE-FF"))
        assertNull(WakeOnLan.normalize("nope"))
        val p = WakeOnLan.magicPacket("aa:bb:cc:dd:ee:ff")
        assertEquals(102, p.size)
        assertTrue(p.take(6).all { it == 0xff.toByte() })
        assertEquals(listOf(0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff), p.takeLast(6).map { it.toInt() and 0xff })
    }

    @Test
    fun keyTyping() {
        // Lower case, and characters without their own key ('%', '!') are dropped.
        assertEquals("it's 50 off, ok", KeyTyping.typeable("It's 50% OFF, ok!"))
        assertEquals(listOf(36, 37), KeyTyping.edit("", "hi"))
        assertEquals(listOf(KeyCodes.DEL, KeyCodes.DEL), KeyTyping.edit("hey", "h"))
        assertEquals(listOf(KeyCodes.DEL, 41), KeyTyping.edit("ab", "am"))
        assertEquals(emptyList(), KeyTyping.edit("same", "same"))
        assertEquals(listOf(7, 16, 62, 77, 56), KeyTyping.edit("", "09 @."))
    }

    @Test
    fun protoDump() {
        val msg = ProtoMessage(Remote.imeBatchEdit(3, 1, "hi there"))
        assertEquals("21{1=3 2=1 3{1=1 2{1=7 2=7 3=\"hi there\"}}}", msg.dump())
    }
}
