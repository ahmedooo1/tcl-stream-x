package fr.ahmad.tclstreamx.data.epg

import org.xmlpull.v1.XmlPullParser

data class XmltvProgramme(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val description: String?,
)

/**
 * Streaming XMLTV reader. Guides are often tens of megabytes: only programmes of wanted channels
 * and inside the time window are emitted, everything else is skipped while reading.
 */
class XmltvParser(
    private val wantedChannels: Set<String>,
    private val windowStart: Long,
    private val windowEnd: Long,
) {
    fun parse(parser: XmlPullParser, onProgramme: (XmltvProgramme) -> Unit): Int {
        var emitted = 0
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && parser.name == "programme") {
                val channel = parser.getAttributeValue(null, "channel")?.lowercase()
                val start = parser.getAttributeValue(null, "start")?.let(::parseTime)
                val stop = parser.getAttributeValue(null, "stop")?.let(::parseTime)
                val wanted = channel != null && channel in wantedChannels && start != null && stop != null &&
                    stop > windowStart && start < windowEnd && stop > start
                if (wanted) {
                    val (title, desc) = readBody(parser)
                    if (title.isNotBlank()) {
                        onProgramme(XmltvProgramme(channel!!, start!!, stop!!, title.take(MAX_TITLE), desc?.take(MAX_DESC)))
                        emitted++
                    }
                } else {
                    skip(parser)
                }
            }
            event = parser.next()
        }
        return emitted
    }

    /** Reads children of <programme>; the first title/desc wins (they may repeat per language). */
    private fun readBody(parser: XmlPullParser): Pair<String, String?> {
        var title = ""
        var desc: String? = null
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "title" -> { val t = readText(parser); if (title.isEmpty()) title = t }
                    "desc" -> { val d = readText(parser); if (desc == null) desc = d }
                    else -> skip(parser)
                }
            }
            event = parser.next()
        }
        return title.trim() to desc?.trim()
    }

    private fun readText(parser: XmlPullParser): String {
        val sb = StringBuilder()
        val depth = parser.depth
        var event = parser.next()
        while (!(event == XmlPullParser.END_TAG && parser.depth == depth) && event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.TEXT) sb.append(parser.text)
            event = parser.next()
        }
        return sb.toString()
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    companion object {
        private const val MAX_TITLE = 200
        private const val MAX_DESC = 1000

        /**
         * XMLTV time: "yyyyMMddHHmmss +hhmm" (seconds and offset optional). Returns epoch millis or null.
         * Implemented by hand: it runs for every programme of a guide, SimpleDateFormat is too slow.
         */
        fun parseTime(value: String): Long? {
            val v = value.trim()
            if (v.length < 12 || !v.substring(0, 12).all { it.isDigit() }) return null
            val year = v.substring(0, 4).toInt()
            val month = v.substring(4, 6).toInt()
            val day = v.substring(6, 8).toInt()
            val hour = v.substring(8, 10).toInt()
            val minute = v.substring(10, 12).toInt()
            val second = if (v.length >= 14 && v[12].isDigit() && v[13].isDigit()) v.substring(12, 14).toInt() else 0
            if (month !in 1..12 || day !in 1..31 || hour > 23 || minute > 59 || second > 60) return null
            var offsetMinutes = 0
            val tz = v.substring(if (v.length >= 14 && v[12].isDigit()) 14 else 12).trim()
            if (tz.length == 5 && (tz[0] == '+' || tz[0] == '-') && tz.substring(1).all { it.isDigit() }) {
                val sign = if (tz[0] == '-') -1 else 1
                offsetMinutes = sign * (tz.substring(1, 3).toInt() * 60 + tz.substring(3, 5).toInt())
            }
            val days = daysFromCivil(year, month, day)
            val secondsUtc = days * 86400L + hour * 3600L + minute * 60L + second - offsetMinutes * 60L
            return secondsUtc * 1000L
        }

        /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
        private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
            val y = if (m <= 2) y0 - 1 else y0
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return era * 146097L + doe - 719468L
        }
    }
}
