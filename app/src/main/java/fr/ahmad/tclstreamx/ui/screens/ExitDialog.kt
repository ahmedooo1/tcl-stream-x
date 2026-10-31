package fr.ahmad.tclstreamx.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette

/** "Leave StreamX?" The focus starts on Stay, so a stray Back press never closes the app. */
@Composable
fun ExitDialog(onStay: () -> Unit) {
    val context = LocalContext.current
    val type = LocalTallyType.current
    val stay = remember { FocusRequester() }
    Dialog(onDismissRequest = onStay, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .width(420.dp)
                .background(Palette.Graphite, RoundedCornerShape(16.dp))
                .border(1.dp, Palette.Hairline, RoundedCornerShape(16.dp))
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(stringResource(R.string.exit_title), style = type.headline)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TallyRow(onClick = onStay, modifier = Modifier.weight(1f).height(52.dp).focusRequester(stay)) {
                    Text(stringResource(R.string.exit_stay), style = type.title, modifier = Modifier.align(Alignment.Center))
                }
                TallyRow(onClick = { context.findActivity()?.finish() }, modifier = Modifier.weight(1f).height(52.dp)) { focused ->
                    Text(
                        stringResource(R.string.exit_quit),
                        style = type.title.copy(color = if (focused) Palette.Tally else Palette.Text),
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { stay.requestFocus() } }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
