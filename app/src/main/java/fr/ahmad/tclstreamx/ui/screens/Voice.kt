package fr.ahmad.tclstreamx.ui.screens

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.ui.theme.Palette
import kotlinx.coroutines.delay

/** What the microphone is doing, read by the search bar and the microphone button. */
@Stable
class VoiceState {
    var listening by mutableStateOf(false)
    /** Loudness of the voice, 0..1, while listening. */
    var level by mutableFloatStateOf(0f)
    /** Words recognised so far. */
    var partial by mutableStateOf("")
    /** Why nothing was heard, shown a few seconds in the search bar. */
    var message by mutableStateOf<String?>(null)
}

class Voice internal constructor(val state: VoiceState, private val start: () -> Unit, private val stop: () -> Unit) {
    fun toggle() = if (state.listening) stop() else start()
}

/**
 * Voice search inside the app: the system speech service listens (through the remote's microphone on
 * a TV) while the button pulses with the voice, and the words appear as they are recognised. When the
 * device has no speech service, the system speech screen is used instead. Every failure is shown in
 * words with its code, instead of a silent stop.
 */
@Composable
fun rememberVoice(onText: (String) -> Unit): Voice {
    val ctx = LocalContext.current
    val state = remember { VoiceState() }
    val deliver by rememberUpdatedState(onText)
    val prompt = stringResource(R.string.voice_prompt)
    val noMatch = stringResource(R.string.voice_no_match)
    val denied = stringResource(R.string.voice_denied)
    val unavailable = stringResource(R.string.voice_unavailable)
    val failedTpl = stringResource(R.string.voice_error)

    val recognizer = remember {
        if (SpeechRecognizer.isRecognitionAvailable(ctx)) runCatching { SpeechRecognizer.createSpeechRecognizer(ctx) }.getOrNull() else null
    }
    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { state.listening = true }
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) { state.level = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { state.level = 0f }
            override fun onError(error: Int) {
                state.listening = false
                state.level = 0f
                state.message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> noMatch
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> denied
                    else -> String.format(failedTpl, error)
                }
            }
            override fun onResults(results: Bundle?) {
                state.listening = false
                state.level = 0f
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()
                state.partial = ""
                if (text.isNullOrEmpty()) state.message = noMatch else deliver(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.let { state.partial = it }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        onDispose { runCatching { recognizer?.destroy() } }
    }

    fun listenNow() {
        val r = recognizer ?: return
        state.message = null
        state.partial = ""
        state.listening = true
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
        runCatching { r.startListening(intent) }.onFailure {
            state.listening = false
            state.message = unavailable
        }
    }

    val fallback = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val text = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!text.isNullOrEmpty()) deliver(text)
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) listenNow() else state.message = denied
    }

    LaunchedEffect(state.message) {
        if (state.message != null) {
            delay(6_000)
            state.message = null
        }
    }

    val start = {
        when {
            recognizer == null -> try {
                fallback.launch(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
                )
            } catch (e: ActivityNotFoundException) {
                state.message = unavailable
            }
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                permission.launch(Manifest.permission.RECORD_AUDIO)
            else -> listenNow()
        }
    }
    val stop = {
        runCatching { recognizer?.stopListening() }
        state.listening = false
        state.level = 0f
    }
    return Voice(state, start, stop)
}

/** Round microphone button: a plain white microphone; while listening, a ring breathes with the voice. */
@Composable
fun VoiceButton(voice: Voice, diameter: Dp, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val listening = voice.state.listening
    val level by animateFloatAsState(if (listening) voice.state.level else 0f, animationSpec = tween(90), label = "level")
    val breathe by rememberInfiniteTransition(label = "breathe").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "breathe",
    )
    Box(
        modifier
            .size(diameter)
            .onFocusChanged { focused = it.isFocused }
            .clip(CircleShape)
            .clickable { voice.toggle() },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2f
            val c = center
            if (listening) {
                val reach = maxOf(level, breathe * 0.35f)
                drawCircle(Palette.Tally.copy(alpha = 0.22f), radius = r * (0.74f + 0.26f * reach), center = c)
                drawCircle(Palette.Tally, radius = r * 0.64f, center = c)
            } else {
                drawCircle(Color.White.copy(alpha = if (focused) 0.24f else 0.08f), radius = r, center = c)
                if (focused) drawCircle(Color.White, radius = r - 1.5.dp.toPx(), center = c, style = Stroke(2.dp.toPx()))
            }
            // The microphone: capsule, cradle and stem.
            val w = r * 0.28f
            val stroke = r * 0.075f
            drawRoundRect(
                Color.White,
                topLeft = Offset(c.x - w / 2f, c.y - r * 0.40f),
                size = Size(w, r * 0.50f),
                cornerRadius = CornerRadius(w / 2f, w / 2f),
            )
            drawArc(
                Color.White,
                startAngle = 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(c.x - r * 0.27f, c.y - r * 0.16f),
                size = Size(r * 0.54f, r * 0.42f),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
            drawLine(Color.White, Offset(c.x, c.y + r * 0.26f), Offset(c.x, c.y + r * 0.40f), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}
