package fr.ahmad.tclstreamx.data.xtream

import java.io.IOException
import java.io.Reader

class JsonFormatException(message: String) : IOException(message)

/**
 * Minimal streaming JSON reader for panel responses that can hold tens of thousands of entries.
 *
 * Only one top-level element is materialised at a time, so a 60 MB `get_vod_streams` answer is
 * read with a constant amount of memory. Numbers are kept as their source text because panels
 * disagree on types (`"stream_id": 12` on one, `"stream_id": "12"` on another).
 */
class JsonStream(private val reader: Reader) {
    private val buf = CharArray(64 * 1024)
    private var pos = 0
    private var len = 0
    private val sb = StringBuilder()

    /**
     * Calls [onElement] for every entry of the top-level value:
     * an array yields its items, an object whose values are all objects (some panels index their
     * lists by id) yields those values, any other object is yielded as is. An empty body yields nothing.
     */
    fun forEachElement(onElement: (Map<String, Any?>) -> Unit) {
        when (peekNonSpace()) {
            -1 -> return
            '['.code -> {
                pos++
                if (peekNonSpace() == ']'.code) { pos++; return }
                while (true) {
                    val v = readValue(1)
                    if (v is Map<*, *>) {
                        @Suppress("UNCHECKED_CAST")
                        onElement(v as Map<String, Any?>)
                    }
                    when (nextNonSpace()) {
                        ','.code -> continue
                        ']'.code -> return
                        else -> throw JsonFormatException("expected , or ] in array")
                    }
                }
            }
            '{'.code -> {
                @Suppress("UNCHECKED_CAST")
                val obj = readValue(1) as Map<String, Any?>
                if (obj.isNotEmpty() && obj.values.all { it is Map<*, *> }) {
                    @Suppress("UNCHECKED_CAST")
                    obj.values.forEach { onElement(it as Map<String, Any?>) }
                } else {
                    onElement(obj)
                }
            }
            else -> throw JsonFormatException("not a JSON array or object")
        }
    }

    /** Reads one complete value (used for small answers such as account info or series info). */
    fun readRoot(): Any? = if (peekNonSpace() == -1) null else readValue(1)

    private fun readValue(depth: Int): Any? {
        if (depth > MAX_DEPTH) throw JsonFormatException("nesting too deep")
        return when (val c = nextNonSpace()) {
            '{'.code -> readObject(depth)
            '['.code -> readArray(depth)
            '"'.code -> readString()
            't'.code -> { expect("rue"); true }
            'f'.code -> { expect("alse"); false }
            'n'.code -> { expect("ull"); null }
            -1 -> throw JsonFormatException("unexpected end of input")
            else -> if (c == '-'.code || c in '0'.code..'9'.code) readNumber(c) else throw JsonFormatException("unexpected character")
        }
    }

    private fun readObject(depth: Int): Map<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        if (peekNonSpace() == '}'.code) { pos++; return map }
        while (true) {
            if (nextNonSpace() != '"'.code) throw JsonFormatException("expected object key")
            val key = readString()
            if (nextNonSpace() != ':'.code) throw JsonFormatException("expected :")
            map[key] = readValue(depth + 1)
            when (nextNonSpace()) {
                ','.code -> continue
                '}'.code -> return map
                else -> throw JsonFormatException("expected , or } in object")
            }
        }
    }

    private fun readArray(depth: Int): List<Any?> {
        val list = ArrayList<Any?>()
        if (peekNonSpace() == ']'.code) { pos++; return list }
        while (true) {
            list += readValue(depth + 1)
            when (nextNonSpace()) {
                ','.code -> continue
                ']'.code -> return list
                else -> throw JsonFormatException("expected , or ] in array")
            }
        }
    }

    private fun readString(): String {
        sb.setLength(0)
        while (true) {
            if (pos >= len && !fill()) throw JsonFormatException("unterminated string")
            val c = buf[pos++]
            when {
                c == '"' -> return sb.toString()
                c == '\\' -> {
                    val e = read()
                    when (e) {
                        '"'.code, '\\'.code, '/'.code -> sb.append(e.toChar())
                        'b'.code -> sb.append('\b')
                        'f'.code -> sb.append('\u000C')
                        'n'.code -> sb.append('\n')
                        'r'.code -> sb.append('\r')
                        't'.code -> sb.append('\t')
                        'u'.code -> {
                            var code = 0
                            repeat(4) {
                                val h = read()
                                val d = Character.digit(h, 16)
                                if (d < 0) throw JsonFormatException("bad unicode escape")
                                code = code * 16 + d
                            }
                            sb.append(code.toChar())
                        }
                        else -> throw JsonFormatException("bad escape")
                    }
                }
                else -> sb.append(c)
            }
            if (sb.length > MAX_STRING) throw JsonFormatException("string too long")
        }
    }

    private fun readNumber(first: Int): String {
        sb.setLength(0)
        sb.append(first.toChar())
        while (true) {
            if (pos >= len && !fill()) break
            val c = buf[pos]
            if (c in '0'..'9' || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                sb.append(c)
                pos++
                if (sb.length > 64) throw JsonFormatException("number too long")
            } else {
                break
            }
        }
        return sb.toString()
    }

    private fun expect(rest: String) {
        for (ch in rest) if (read() != ch.code) throw JsonFormatException("bad literal")
    }

    private fun read(): Int {
        if (pos >= len && !fill()) return -1
        return buf[pos++].code
    }

    private fun nextNonSpace(): Int {
        while (true) {
            val c = read()
            if (c == -1 || !isSpace(c)) return c
        }
    }

    private fun peekNonSpace(): Int {
        while (true) {
            if (pos >= len && !fill()) return -1
            val c = buf[pos].code
            // A UTF-8 byte order mark is tolerated before the first value.
            if (isSpace(c) || c == 0xFEFF) pos++ else return c
        }
    }

    private fun isSpace(c: Int) = c == ' '.code || c == '\n'.code || c == '\r'.code || c == '\t'.code

    private fun fill(): Boolean {
        len = reader.read(buf, 0, buf.size)
        pos = 0
        if (len <= 0) {
            len = 0
            return false
        }
        return true
    }

    companion object {
        private const val MAX_DEPTH = 32
        private const val MAX_STRING = 1_000_000
    }
}

/** Field access tolerant of the type variations between panels. */
internal fun Map<String, Any?>.text(key: String): String? = when (val v = this[key]) {
    null -> null
    is String -> v.trim().ifEmpty { null }
    is Boolean -> if (v) "1" else "0"
    is Map<*, *>, is List<*> -> null
    else -> v.toString()
}

internal fun Map<String, Any?>.long(key: String): Long? = text(key)?.let { it.toLongOrNull() ?: it.toDoubleOrNull()?.toLong() }

@Suppress("UNCHECKED_CAST")
internal fun Map<String, Any?>.obj(key: String): Map<String, Any?>? = this[key] as? Map<String, Any?>
