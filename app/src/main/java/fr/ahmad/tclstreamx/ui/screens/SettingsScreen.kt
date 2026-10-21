package fr.ahmad.tclstreamx.ui.screens

import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.tv.material3.Text
import fr.ahmad.tclstreamx.BuildConfig
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.epg.EpgState
import fr.ahmad.tclstreamx.ui.components.InputField
import fr.ahmad.tclstreamx.ui.components.TallyRow
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.screenPadding
import fr.ahmad.tclstreamx.ui.theme.Palette
import kotlinx.coroutines.launch

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen() {
    val c = (LocalContext.current.applicationContext as TclApp).container
    val type = LocalTallyType.current
    val settings by c.settings.settings.collectAsState(initial = null)
    val epgState by c.epg.state.collectAsState()
    val programmes by remember { c.db.epg().observeCount() }.collectAsState(initial = 0)
    val first = remember { FocusRequester() }
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
    val languages = listOf("" to stringResource(R.string.settings_language_system), "fr" to "Français", "ar" to "العربية")
    LaunchedEffect(Unit) { runCatching { first.requestFocus() } }

    LazyColumn(
        Modifier.fillMaxSize().focusRestorer(),
        contentPadding = screenPadding(end = 120.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item { Text(stringResource(R.string.settings_title), style = type.display, modifier = Modifier.padding(bottom = 16.dp)) }

        item { Section(stringResource(R.string.settings_language)) }
        languages.forEachIndexed { i, (tag, label) ->
            item(key = "lang$tag") {
                OptionRow(label, selected = current == tag, modifier = if (i == 0) Modifier.focusRequester(first) else Modifier) {
                    AppCompatDelegate.setApplicationLocales(
                        if (tag.isEmpty()) LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag)
                    )
                }
            }
        }

        item { Section(stringResource(R.string.settings_epg)) }
        item {
            InputField(
                value = settings?.epgUrl.orEmpty(),
                placeholder = stringResource(R.string.settings_epg_hint),
                onSubmit = { url -> c.appScope.launch { c.settings.setEpgUrl(url) } },
                keyboardType = KeyboardType.Uri,
            )
        }
        item {
            TallyRow(
                onClick = {
                    c.appScope.launch {
                        c.epg.refresh(c.settings.current().epgUrl)
                        c.settings.markEpgRefreshed(System.currentTimeMillis())
                    }
                },
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Text(stringResource(R.string.settings_epg_refresh), style = type.title, modifier = Modifier.align(Alignment.CenterStart).padding(horizontal = 14.dp))
            }
        }
        item {
            val line = when (val s = epgState) {
                is EpgState.Loading -> stringResource(R.string.settings_epg_loading)
                is EpgState.Done -> stringResource(R.string.settings_epg_done, s.programmes, s.sources, s.failed)
                EpgState.Idle -> stringResource(R.string.settings_epg_count, programmes)
            }
            Text(line, style = type.mono, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp))
        }

        item { Section(stringResource(R.string.nav_live)) }
        item {
            ToggleRow(stringResource(R.string.settings_preview), settings?.previewOnFocus ?: true) {
                c.appScope.launch { c.settings.setPreviewOnFocus(it) }
            }
        }
        item {
            ToggleRow(stringResource(R.string.settings_autocheck), settings?.autoCheckBuiltin ?: true) {
                c.appScope.launch { c.settings.setAutoCheck(it) }
            }
        }

        item { Section(stringResource(R.string.settings_about)) }
        item { Text(stringResource(R.string.settings_about_body), style = type.body.copy(color = Palette.TextDim)) }
        item { Text(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), style = type.mono, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = LocalTallyType.current.headline, modifier = Modifier.padding(top = 22.dp, bottom = 6.dp))
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val type = LocalTallyType.current
    TallyRow(onClick = onClick, selected = selected, modifier = modifier.fillMaxWidth().height(50.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = type.body, modifier = Modifier.weight(1f))
            if (selected) Text("●", style = type.mono.copy(color = Palette.Tally))
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    val type = LocalTallyType.current
    TallyRow(onClick = { onChange(!value) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
        Row(Modifier.fillMaxSize().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = type.body, modifier = Modifier.weight(1f))
            Text(
                stringResource(if (value) R.string.on else R.string.off),
                style = type.mono.copy(color = if (value) Palette.Signal else Palette.TextFaint),
            )
        }
    }
}
