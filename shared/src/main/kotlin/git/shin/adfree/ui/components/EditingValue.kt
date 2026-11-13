package git.shin.adfree.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * The text of a field together with where the caret sits and what is being
 * composed, kept in the field rather than derived from a string.
 *
 * A search box handed a bare `String` loses both: the field is told the text and
 * has to guess the rest, so every value that arrives a frame after the keystroke
 * — which is every value that comes back through a state flow — puts the caret
 * somewhere the user did not put it. Measured here: typing `ab` and then `b`
 * left the caret between the two characters, and the next key landed inside the
 * word. The same field also drops an input method's in-progress composition,
 * which is what a Vietnamese keyboard sends on every syllable.
 *
 * The holder follows the screen when code changes the text (a Clear button, a
 * row that fills the box with its domain), and leaves the caret alone when the
 * change is only the echo of the last keystroke.
 */
@Composable
fun rememberEditingValue(text: String): MutableState<TextFieldValue> {
    val state = remember { mutableStateOf(TextFieldValue(text, TextRange(text.length))) }
    LaunchedEffect(text) {
        if (state.value.text != text) {
            state.value = TextFieldValue(text, TextRange(text.length))
        }
    }
    return state
}
