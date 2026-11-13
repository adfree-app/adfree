package git.shin.adfree.desktop

import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import git.shin.adfree.ui.components.rememberEditingValue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The filter log's search box: what the user typed is what it holds, and the
 * caret stays where they left it.
 *
 * The box used to be handed a bare `String`, so every value that came back
 * through the state flow arrived as a new text plus a caret position the screen
 * guessed. Typing `ab` and then `b` put the caret between the two characters and
 * the next key landed inside the word.
 */
@OptIn(ExperimentalTestApi::class)
class EditingValueUiTest {

    @Test
    fun typingKeepsTheCaretAtTheEndOfWhatWasTyped() {
        runComposeUiTest {
            lateinit var holder: MutableState<TextFieldValue>
            setContent {
                var external by mutableStateOf("")
                holder = rememberEditingValue(external)
                BasicTextField(
                    value = holder.value,
                    onValueChange = {
                        holder.value = it
                        external = it.text
                    },
                    modifier = Modifier.testTag("field"),
                )
            }
            for (key in listOf("a", "b", "b", "a", "c")) {
                onNodeWithTag("field").performTextInput(key)
                waitForIdle()
                assertEquals(holder.value.text.length, holder.value.selection.end)
            }
            assertEquals("abbac", holder.value.text)
            assertEquals(TextRange(5), holder.value.selection)
        }
    }

    @Test
    fun codeThatClearsTheBoxTakesTheCaretWithIt() {
        runComposeUiTest {
            lateinit var holder: MutableState<TextFieldValue>
            var external by mutableStateOf("")
            setContent {
                holder = rememberEditingValue(external)
                BasicTextField(
                    value = holder.value,
                    onValueChange = {
                        holder.value = it
                        external = it.text
                    },
                    modifier = Modifier.testTag("field"),
                )
            }
            onNodeWithTag("field").performTextInput("ads.example")
            waitForIdle()
            assertEquals("ads.example", holder.value.text)
            // The Clear button, or a row that fills the box in, changes the
            // text from outside the field.
            external = ""
            waitForIdle()
            assertEquals("", holder.value.text)
            assertEquals(TextRange(0), holder.value.selection)
        }
    }
}
