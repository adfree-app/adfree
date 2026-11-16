package git.shin.adfree.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import git.shin.adfree.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How much the theme scales the text.
 *
 * `MyApplicationTheme` multiplies the density's `fontScale` so labels can grow
 * without their dp boxes growing with them. A multiplication applied twice is
 * 1.44 instead of 1.2, and that is not a small difference: every label grows a
 * fifth while the box around it stays the size it was. The desktop applies the
 * theme at the window, so the title bar shares the palette, and the app screen
 * applied it again for its own settings — which is the nesting this test refuses
 * to let scale twice.
 */
@OptIn(ExperimentalTestApi::class)
class ThemeScaleTest {

    private fun measure(theme: @androidx.compose.runtime.Composable (@androidx.compose.runtime.Composable () -> Unit) -> Unit): Float {
        var seen = 0f
        runComposeUiTest {
            setContent {
                theme { MaterialTheme { seen = LocalDensity.current.fontScale } }
            }
            waitForIdle()
        }
        return seen
    }

    private val plain: Float
        get() = measure { content -> content() }

    private val once: Float
        get() = measure { content -> MyApplicationTheme { content() } }

    private val twice: Float
        get() = measure { content -> MyApplicationTheme { MyApplicationTheme { content() } } }

    @Test
    fun theThemeEnlargesTheText() {
        assertTrue("the theme must scale the text up: $plain -> $once", once > plain)
    }

    @Test
    fun aNestedApplicationDoesNotScaleAgain() {
        assertEquals(
            "a theme inside a theme must not multiply the scale",
            once,
            twice,
            0.001f,
        )
    }
}
