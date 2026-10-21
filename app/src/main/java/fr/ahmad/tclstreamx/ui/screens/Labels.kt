package fr.ahmad.tclstreamx.ui.screens

import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.data.m3u.ImportError
import fr.ahmad.tclstreamx.player.PlaybackIssue
import java.text.DateFormat
import java.util.Date

/** Built-in subsections are stored as "category:language" (e.g. "news:ar"); playlist groups as is. */
@Composable
fun groupLabel(raw: String): String {
    val parts = raw.split(':')
    if (parts.size == 2 && parts[0] in BUILTIN_CATEGORIES) {
        return categoryLabel(parts[0]) + " · " + languageLabel(parts[1])
    }
    return if (raw in BUILTIN_CATEGORIES) categoryLabel(raw) else raw.ifEmpty { stringResource(R.string.group_none) }
}

private val BUILTIN_CATEGORIES = setOf("news", "sports", "entertainment", "kids", "music", "religious", "documentary")

@Composable
private fun categoryLabel(c: String): String = stringResource(
    when (c) {
        "news" -> R.string.cat_news
        "sports" -> R.string.cat_sports
        "kids" -> R.string.cat_kids
        "music" -> R.string.cat_music
        "religious" -> R.string.cat_religious
        "documentary" -> R.string.cat_documentary
        else -> R.string.cat_entertainment
    }
)

private fun languageLabel(code: String): String = when (code) {
    "fr" -> "Français"
    "ar" -> "العربية"
    "en" -> "English"
    "es" -> "Español"
    "tr" -> "Türkçe"
    "it" -> "Italiano"
    "de" -> "Deutsch"
    "pt" -> "Português"
    else -> code.uppercase()
}

fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()

fun clockTime(millis: Long): String = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

@Composable
fun issueText(issue: PlaybackIssue): String = stringResource(
    when (issue) {
        PlaybackIssue.NETWORK -> R.string.player_issue_network
        PlaybackIssue.TIMEOUT -> R.string.player_issue_timeout
        PlaybackIssue.FORBIDDEN -> R.string.player_issue_forbidden
        PlaybackIssue.AUTH -> R.string.player_issue_auth
        PlaybackIssue.NOT_FOUND -> R.string.player_issue_not_found
        PlaybackIssue.SERVER -> R.string.player_issue_server
        PlaybackIssue.FORMAT -> R.string.player_issue_format
        PlaybackIssue.DRM -> R.string.player_issue_drm
        PlaybackIssue.DECODER -> R.string.player_issue_decoder
        PlaybackIssue.UNKNOWN -> R.string.player_issue_unknown
    }
)

@Composable
fun importErrorText(e: ImportError): String = stringResource(
    when (e) {
        ImportError.INVALID_URL -> R.string.import_error_INVALID_URL
        ImportError.NETWORK -> R.string.import_error_NETWORK
        ImportError.HTTP -> R.string.import_error_HTTP
        ImportError.EMPTY -> R.string.import_error_EMPTY
        ImportError.TOO_LARGE -> R.string.import_error_TOO_LARGE
        ImportError.UNREADABLE -> R.string.import_error_UNREADABLE
    }
)
