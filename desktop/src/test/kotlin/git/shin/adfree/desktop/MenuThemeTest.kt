package git.shin.adfree.desktop

import java.awt.Color
import java.awt.Font
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MenuThemeTest {
    @Test
    fun theMenuTakesItsColoursAndFontFromTheDesktopTheme() {
        val file = Files.createTempFile("kdeglobals", ".ini")
        Files.writeString(
            file,
            """
            [Colors:Window]
            BackgroundNormal=32,35,38
            ForegroundNormal=252,252,252
            [Colors:View]
            BackgroundNormal=35,38,41
            [Colors:Selection]
            BackgroundNormal=61,174,233
            ForegroundNormal=1,2,3
            [General]
            font=Noto Sans,11,-1,1,50,0,0,0,0,0
            [Other]
            Unrelated=value
            """.trimIndent(),
        )
        val globals = KdeGlobals.read(file.toFile())
        assertEquals(Color(32, 35, 38), globals.rgb("Colors:Window", "BackgroundNormal"))
        assertEquals(Color(61, 174, 233), globals.rgb("Colors:Selection", "BackgroundNormal"))
        val font = globals.font()!!
        // The family the theme *asks* for, which is what this parser decides.
        // `getFamily()` is resolved by the font system afterwards and answers
        // "Dialog" on a machine without the font — a CI runner has no Noto Sans
        // — so asserting it would be testing the machine's installed fonts.
        assertEquals("Noto Sans", font.name)
        assertEquals(11, font.size)
        assertEquals(Font.BOLD, font.style)
        // A missing key is a miss, not a crash and not black.
        assertNull(globals.rgb("Colors:Window", "NotThere"))
    }
}
