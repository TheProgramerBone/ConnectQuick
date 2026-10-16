package dev.connectquick.controller

import dev.connectquick.controller.files.FileNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test
    fun ordinary_names_are_kept() {
        assertEquals("foto 1.jpg", FileNames.safe("foto 1.jpg"))
        assertEquals("ñandú ✓.txt", FileNames.safe("ñandú ✓.txt"))
    }

    @Test
    fun directories_and_traversal_never_survive() {
        assertEquals("passwd", FileNames.safe("../../etc/passwd"))
        assertEquals("secret.txt", FileNames.safe("C:\\Users\\x\\..\\secret.txt"))
        assertEquals("a.txt", FileNames.safe("/abs/path/a.txt"))
        assertEquals("archivo", FileNames.safe(".."))
        assertEquals("archivo", FileNames.safe("../"))
        assertEquals("archivo", FileNames.safe(""))
    }

    @Test
    fun control_bidi_and_forbidden_characters_are_neutralised() {
        assertEquals("ab.txt", FileNames.safe("a\u0000b\u0007.txt"))
        val spoof = FileNames.safe("report\u202Etxt.exe")
        assertFalse(spoof.contains('\u202E'))
        assertEquals("a_b_c.txt", FileNames.safe("a:b|c.txt"))
    }

    @Test
    fun reserved_device_names_and_trailing_dots_are_defused() {
        assertEquals("_CON.txt", FileNames.safe("CON.txt"))
        assertEquals("_nul", FileNames.safe("nul"))
        assertEquals("x", FileNames.safe("x. . "))
    }

    @Test
    fun very_long_names_are_shortened_but_keep_their_extension() {
        val long = "a".repeat(400) + ".pdf"
        val safe = FileNames.safe(long)
        assertTrue(safe.length <= 120)
        assertTrue(safe.endsWith(".pdf"))
    }
}
