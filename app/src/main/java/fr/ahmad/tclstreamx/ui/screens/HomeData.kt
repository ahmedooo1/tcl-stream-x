package fr.ahmad.tclstreamx.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import fr.ahmad.tclstreamx.AppContainer
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.ContentKind
import fr.ahmad.tclstreamx.data.db.VodProgressEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * What Home shows, kept between visits. Before, every return to Home started all its queries again
 * (counts over tens of thousands of titles, newest, best rated...), so the screen came back empty and
 * filled in slowly. The values stay in memory while Home was seen in the last ten minutes, and the
 * queries are re-run at most every 1.5 s when the library changes (a channel check writes many rows).
 */
class HomeData private constructor(c: AppContainer) {
    private val scope = c.appScope
    private val dao = c.db.channels()

    private fun <T> Flow<T>.keep(initial: T): StateFlow<T> =
        calm().stateIn(scope, SharingStarted.WhileSubscribed(KEEP_MS), initial)

    val recent = dao.observeRecent(12).keep(emptyList())
    val favorites = dao.observeFavorites(30).keep(emptyList())
    val news = dao.observeCategory("news").keep(emptyList())
    val sports = dao.observeCategory("sports").keep(emptyList())
    val resume: StateFlow<List<VodProgressEntity>> = c.db.vodProgress().observeContinue(12).keep(emptyList())
    val newMovies = dao.observeNewest(ContentKind.MOVIE, 30).keep(emptyList())
    val newSeries = dao.observeNewest(ContentKind.SERIES, 30).keep(emptyList())
    val topMovies = dao.observeTopRated(ContentKind.MOVIE, 30).keep(emptyList())
    val liveCount = dao.observeCount(0, ContentKind.LIVE, null).keep(0)
    val movieCount = dao.observeCount(0, ContentKind.MOVIE, null).keep(0)
    val seriesCount = dao.observeCount(0, ContentKind.SERIES, null).keep(0)
    val lastChannel: StateFlow<ChannelEntity?> = c.settings.settings
        .map { it.lastChannelKey }
        .distinctUntilChanged()
        .map { key -> key?.let { dao.byKey(it) } }
        .keep(null)

    companion object {
        private const val KEEP_MS = 10 * 60_000L
        private const val CALM_MS = 1_500L

        @Volatile private var instance: HomeData? = null

        fun of(c: AppContainer): HomeData = instance ?: synchronized(this) { instance ?: HomeData(c).also { instance = it } }

        /** First value at once, then at most one every [ms]; the latest value always comes through. */
        private fun <T> Flow<T>.calm(ms: Long = CALM_MS): Flow<T> = flow {
            var last = 0L
            conflate().collect { v ->
                val wait = last + ms - System.currentTimeMillis()
                if (wait > 0) delay(wait)
                emit(v)
                last = System.currentTimeMillis()
            }
        }
    }
}

/** Text spoken on Home, waiting to be picked up by the search screen. */
object SearchRequest {
    val pending = MutableStateFlow<String?>(null)
    fun take(): String? = pending.value.also { pending.value = null }
}

/**
 * Voice search: the system speech screen listens through the remote's microphone and returns the
 * words. When the device has no speech service, [onUnavailable] runs (the typed search opens).
 */
@Composable
fun rememberVoiceSearch(onText: (String) -> Unit): ManagedActivityResultLauncher<Intent, ActivityResult> =
    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) onText(text)
    }

fun voiceIntent(prompt: String): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

fun ManagedActivityResultLauncher<Intent, ActivityResult>.listen(prompt: String, onUnavailable: () -> Unit) {
    try {
        launch(voiceIntent(prompt))
    } catch (e: ActivityNotFoundException) {
        onUnavailable()
    }
}
