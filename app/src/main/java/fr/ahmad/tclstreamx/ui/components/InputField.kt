package fr.ahmad.tclstreamx.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette

/**
 * Text entry adapted to a remote: the field is a normal focusable row while browsing (so moving
 * past it never pops the keyboard); OK turns it into an editor and opens the TV keyboard.
 */
@Composable
fun InputField(
    value: String,
    placeholder: String,
    onSubmit: (String) -> Unit,
    modifier: Modifier = Modifier,
    keyboardType: KeyboardType = KeyboardType.Text,
    rowModifier: Modifier = Modifier,
) {
    var editing by remember { mutableStateOf(false) }
    var edited by remember { mutableStateOf(false) }
    val rowFocus = remember { FocusRequester() }
    val type = LocalTallyType.current
    // Give focus back to the row once the keyboard is closed.
    LaunchedEffect(editing) { if (!editing && edited) runCatching { rowFocus.requestFocus() } }
    if (!editing) {
        TallyRow(
            onClick = { editing = true; edited = true },
            modifier = modifier.then(rowModifier).fillMaxWidth().height(52.dp).focusRequester(rowFocus),
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 14.dp).align(Alignment.CenterStart)) {
                Text(
                    value.ifEmpty { placeholder },
                    style = type.body.copy(color = if (value.isEmpty()) Palette.TextFaint else Palette.Text),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        val focus = remember { FocusRequester() }
        var field by remember { mutableStateOf(TextFieldValue(value, TextRange(value.length))) }
        var hadFocus by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
        BasicTextField(
            value = field,
            onValueChange = { field = it.copy(text = it.text.take(MAX_LEN)) },
            singleLine = true,
            textStyle = type.body.copy(color = Palette.Text),
            cursorBrush = SolidColor(Palette.Tally),
            keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                editing = false
                onSubmit(field.text.trim())
            }),
            modifier = modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(Palette.Raised)
                .border(2.dp, Palette.Tally)
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .focusRequester(focus)
                .onFocusChanged {
                    if (it.isFocused) hadFocus = true
                    else if (hadFocus && editing) {
                        editing = false
                        onSubmit(field.text.trim())
                    }
                },
        )
    }
}

private const val MAX_LEN = 2048
