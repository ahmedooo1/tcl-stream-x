package fr.ahmad.tclstreamx.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import fr.ahmad.tclstreamx.R

/**
 * Visual DNA "master control room": graphite surfaces like a broadcast monitor wall, one hot
 * accent borrowed from the camera tally light (on air), condensed numerals for channel numbers
 * and a monospace voice for technical metadata.
 */
object Palette {
    val Ink = Color(0xFF0B0C0E)
    val Graphite = Color(0xFF131518)
    val Raised = Color(0xFF1B1E22)
    val Lift = Color(0xFF252A30)
    val Hairline = Color(0xFF2C3137)
    val Text = Color(0xFFF1F2F3)
    val TextDim = Color(0xFF9BA1A8)
    val TextFaint = Color(0xFF636970)
    val Tally = Color(0xFFFF4D2E)
    val TallyDeep = Color(0xFF8A2414)
    val Signal = Color(0xFF5BD98F)
    val Caution = Color(0xFFF2B33D)
    val Dead = Color(0xFF6B7078)
}

val PlexSans = FontFamily(
    Font(R.font.ibmplexsansarabic_regular, FontWeight.Normal),
    Font(R.font.ibmplexsansarabic_semibold, FontWeight.SemiBold),
    Font(R.font.ibmplexsansarabic_bold, FontWeight.Bold),
)
val Condensed = FontFamily(
    Font(R.font.barlowcondensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlowcondensed_bold, FontWeight.Bold),
)
val Mono = FontFamily(Font(R.font.ibmplexmono_medium, FontWeight.Medium))

data class TallyType(
    /** Big headlines. Condensed Latin, but Plex for Arabic since Barlow has no Arabic glyphs. */
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    /** Channel numbers, clock: always condensed digits. */
    val numeral: TextStyle,
    val numeralSmall: TextStyle,
    /** Technical metadata: resolution, bitrate, timecodes. */
    val mono: TextStyle,
)

val LocalTallyType = staticCompositionLocalOf<TallyType> { error("TallyTheme missing") }

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TallyTheme(content: @Composable () -> Unit) {
    val arabic = LocalConfiguration.current.locales[0].language == "ar"
    val headlineFamily = if (arabic) PlexSans else Condensed
    val type = TallyType(
        display = TextStyle(fontFamily = headlineFamily, fontWeight = FontWeight.Bold, fontSize = if (arabic) 44.sp else 58.sp, lineHeight = if (arabic) 60.sp else 58.sp, color = Palette.Text),
        headline = TextStyle(fontFamily = headlineFamily, fontWeight = FontWeight.SemiBold, fontSize = if (arabic) 26.sp else 32.sp, lineHeight = if (arabic) 38.sp else 34.sp, color = Palette.Text),
        title = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp, color = Palette.Text),
        body = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, color = Palette.Text),
        bodySmall = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 19.sp, color = Palette.TextDim),
        label = TextStyle(fontFamily = PlexSans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp, color = Palette.TextDim),
        numeral = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 30.sp, color = Palette.Text),
        numeralSmall = TextStyle(fontFamily = Condensed, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 20.sp, color = Palette.TextDim),
        mono = TextStyle(fontFamily = Mono, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, color = Palette.TextDim),
    )
    val colors = darkColorScheme(
        primary = Palette.Tally,
        onPrimary = Palette.Ink,
        secondary = Palette.Signal,
        background = Palette.Ink,
        onBackground = Palette.Text,
        surface = Palette.Graphite,
        onSurface = Palette.Text,
        surfaceVariant = Palette.Raised,
        onSurfaceVariant = Palette.TextDim,
        border = Palette.Hairline,
        error = Palette.Tally,
    )
    val typography = Typography(
        bodyLarge = type.body,
        bodyMedium = type.body,
        bodySmall = type.bodySmall,
        titleMedium = type.title,
        labelMedium = type.label,
    )
    MaterialTheme(colorScheme = colors, typography = typography) {
        CompositionLocalProvider(LocalTallyType provides type, content = content)
    }
}
