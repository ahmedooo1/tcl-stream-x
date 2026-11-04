package fr.ahmad.tclstreamx.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import fr.ahmad.tclstreamx.R
import fr.ahmad.tclstreamx.ui.theme.LocalTallyType
import fr.ahmad.tclstreamx.ui.theme.Palette
import fr.ahmad.tclstreamx.ui.theme.isCompactWidth

const val DEVELOPER_NAME = "Ahmad Ahmad"
const val DEVELOPER_SITE = "https://ahmadahmad.aaweb.fr"
const val DEVELOPER_GITHUB = "https://github.com/ahmedooo1"

/** Who made the app, with QR codes: on a TV nobody types an address, a phone camera opens it. */
@Composable
fun DeveloperCard(modifier: Modifier = Modifier) {
    val type = LocalTallyType.current
    val compact = isCompactWidth()
    val shape = RoundedCornerShape(18.dp)
    val info = @Composable {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.about_made_by), style = type.label.copy(color = Palette.TextDim))
            Text(DEVELOPER_NAME, style = type.display)
            Text(stringResource(R.string.about_dev_role), style = type.body.copy(color = Palette.TextDim))
            Text(DEVELOPER_SITE.removePrefix("https://"), style = type.mono.copy(color = Palette.Tally), modifier = Modifier.padding(top = 8.dp))
            Text(DEVELOPER_GITHUB.removePrefix("https://"), style = type.mono.copy(color = Palette.Text))
        }
    }
    val codes = @Composable {
        Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            QrBlock(DEVELOPER_SITE, stringResource(R.string.about_site), if (compact) 110.dp else 150.dp)
            QrBlock(DEVELOPER_GITHUB, "GitHub", if (compact) 110.dp else 150.dp)
        }
    }
    val box = modifier
        .fillMaxWidth()
        .background(Palette.Graphite, shape)
        .border(1.dp, Palette.Hairline, shape)
        .padding(24.dp)
    if (compact) {
        Column(box, verticalArrangement = Arrangement.spacedBy(18.dp)) { info(); codes() }
    } else {
        Row(box, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) { info(); codes() }
    }
}

@Composable
private fun QrBlock(url: String, label: String, size: Dp) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        QrCode(url, size)
        Text(label, style = LocalTallyType.current.label.copy(color = Palette.TextDim))
    }
}

@Composable
fun QrCode(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) {
        runCatching { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 1)) }.getOrNull()
    }
    Canvas(modifier.size(size).background(Color.White, RoundedCornerShape(10.dp)).padding(8.dp)) {
        val m = matrix ?: return@Canvas
        val cell = this.size.width / m.width
        for (y in 0 until m.height) for (x in 0 until m.width) {
            if (m.get(x, y)) drawRect(Color.Black, Offset(x * cell, y * cell), Size(cell + 0.6f, cell + 0.6f))
        }
    }
}
