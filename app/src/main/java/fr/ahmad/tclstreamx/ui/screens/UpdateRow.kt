package fr.ahmad.tclstreamx.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.update.UpdateState
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import kotlinx.coroutines.launch

/** Current build, check for a newer one, download and install it (used in Settings and on Home). */
@Composable
fun UpdateRow(compactBanner: Boolean = false) {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val state by c.updater.state.collectAsState()
    if (compactBanner && state !is UpdateState.Available && state !is UpdateState.Downloading && state !is UpdateState.Ready) return
    val text = when (val s = state) {
        UpdateState.Idle -> stringResource(R.string.update_check)
        UpdateState.Checking -> stringResource(R.string.update_checking)
        UpdateState.UpToDate -> stringResource(R.string.update_none, c.updater.currentBuild)
        is UpdateState.Available -> stringResource(R.string.update_available, s.build)
        is UpdateState.Downloading -> stringResource(R.string.update_downloading, s.percent)
        is UpdateState.Ready -> stringResource(R.string.update_ready, s.build)
        is UpdateState.Failed -> stringResource(
            when (s.reason) {
                UpdateState.Reason.NETWORK -> R.string.update_failed_network
                UpdateState.Reason.SIGNATURE -> R.string.update_failed_signature
                UpdateState.Reason.NO_PERMISSION -> R.string.update_failed_permission
            }
        )
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (!compactBanner) Text(stringResource(R.string.update_current, c.updater.currentBuild), style = type.mono)
        TallyRow(
            onClick = {
                c.appScope.launch {
                    when (val s = c.updater.state.value) {
                        is UpdateState.Available -> c.updater.downloadAndInstall(s)
                        is UpdateState.Downloading -> Unit
                        else -> {
                            val checked = c.updater.check()
                            if (checked is UpdateState.Available && s is UpdateState.Failed) c.updater.downloadAndInstall(checked)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Text(
                text,
                style = type.body.copy(color = if (state is UpdateState.Available || state is UpdateState.Ready) Palette.Signal else Palette.Text),
                maxLines = 2,
                modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp),
            )
        }
    }
}
