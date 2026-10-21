package fr.ahmad.tclstreamx.data.m3u

import java.text.Normalizer

/** Country (ISO 3166 alpha-2, upper case) and language (ISO 639-1, lower case) of an entry, when known. */
data class Region(val country: String?, val language: String?)

/**
 * Derives a country and a language for every entry, so very large libraries can be browsed by
 * country and by language even when the source only has free-form category names.
 *
 * Order of trust: explicit tvg-country / tvg-language attributes, then the prefix providers put in
 * front of category names ("FR| SPORT", "[AR] MBC", "UK: NEWS", "|DE| FILME"), then country or
 * language names written in the category ("France", "Arabic", "عربي", "Maroc").
 */
object RegionTagger {
    private val COUNTRIES: Set<String> = java.util.Locale.getISOCountries().toSet()

    /** Codes used by providers that are not ISO 3166, or that mean a language rather than a country. */
    private val PREFIX_ALIASES = mapOf(
        "UK" to Region("GB", "en"),
        "USA" to Region("US", "en"),
        "KSA" to Region("SA", "ar"),
        "UAE" to Region("AE", "ar"),
        "AR" to Region(null, "ar"),
        "ARA" to Region(null, "ar"),
        "ARB" to Region(null, "ar"),
        "ARAB" to Region(null, "ar"),
        "EN" to Region(null, "en"),
        "KU" to Region(null, "ku"),
        "KRD" to Region(null, "ku"),
        "KURD" to Region(null, "ku"),
        "ENG" to Region(null, "en"),
        "FRA" to Region("FR", "fr"),
        "FRE" to Region(null, "fr"),
        "GER" to Region("DE", "de"),
        "DEU" to Region("DE", "de"),
        "ITA" to Region("IT", "it"),
        "ESP" to Region("ES", "es"),
        "SPA" to Region(null, "es"),
        "POR" to Region("PT", "pt"),
        "TUR" to Region("TR", "tr"),
        "NED" to Region("NL", "nl"),
        "LAT" to Region(null, "es"),
        "LATINO" to Region(null, "es"),
        "AFR" to Region(null, null),
        "EXYU" to Region(null, null),
        "VIP" to Region(null, null),
        "HD" to Region(null, null),
        "FHD" to Region(null, null),
        "UHD" to Region(null, null),
        "SD" to Region(null, null),
        "TV" to Region(null, null),
        "PPV" to Region(null, null),
        "VOD" to Region(null, null),
        "XXX" to Region(null, null),
    )

    /** Main language of a country, used when only the country is known. */
    private val COUNTRY_LANGUAGE = mapOf(
        "FR" to "fr", "MC" to "fr", "GB" to "en", "US" to "en", "IE" to "en", "AU" to "en", "NZ" to "en",
        "DE" to "de", "AT" to "de", "IT" to "it", "ES" to "es", "MX" to "es", "AR" to "es", "CO" to "es",
        "PT" to "pt", "BR" to "pt", "NL" to "nl", "TR" to "tr", "PL" to "pl", "RO" to "ro", "RU" to "ru",
        "GR" to "el", "SE" to "sv", "NO" to "no", "DK" to "da", "FI" to "fi", "CZ" to "cs", "HU" to "hu",
        "IR" to "fa", "IL" to "he", "IN" to "hi", "PK" to "ur", "AL" to "sq", "RS" to "sr", "HR" to "hr",
        "MA" to "ar", "DZ" to "ar", "TN" to "ar", "LY" to "ar", "EG" to "ar", "SA" to "ar", "AE" to "ar",
        "QA" to "ar", "KW" to "ar", "BH" to "ar", "OM" to "ar", "YE" to "ar", "IQ" to "ar", "SY" to "ar",
        "LB" to "ar", "JO" to "ar", "PS" to "ar", "SD" to "ar",
    )

    /** Words written inside category names (accents removed, lower case). */
    private val WORDS = listOf(
        "france" to Region("FR", "fr"), "francais" to Region(null, "fr"), "french" to Region(null, "fr"),
        "belgique" to Region("BE", null), "belgium" to Region("BE", null), "suisse" to Region("CH", null),
        "canada" to Region("CA", null), "quebec" to Region("CA", "fr"),
        "maroc" to Region("MA", "ar"), "morocco" to Region("MA", "ar"), "algerie" to Region("DZ", "ar"),
        "algeria" to Region("DZ", "ar"), "tunisie" to Region("TN", "ar"), "tunisia" to Region("TN", "ar"),
        "egypt" to Region("EG", "ar"), "egypte" to Region("EG", "ar"), "liban" to Region("LB", "ar"),
        "lebanon" to Region("LB", "ar"), "syria" to Region("SY", "ar"), "syrie" to Region("SY", "ar"),
        "iraq" to Region("IQ", "ar"), "irak" to Region("IQ", "ar"), "saudi" to Region("SA", "ar"),
        "qatar" to Region("QA", "ar"), "kuwait" to Region("KW", "ar"), "emirates" to Region("AE", "ar"),
        "jordan" to Region("JO", "ar"), "palestine" to Region("PS", "ar"),
        "arabic" to Region(null, "ar"), "arabe" to Region(null, "ar"), "arab" to Region(null, "ar"),
        "عربي" to Region(null, "ar"), "عربية" to Region(null, "ar"), "المغرب" to Region("MA", "ar"),
        "الجزائر" to Region("DZ", "ar"), "تونس" to Region("TN", "ar"), "مصر" to Region("EG", "ar"),
        "لبنان" to Region("LB", "ar"), "سوريا" to Region("SY", "ar"), "العراق" to Region("IQ", "ar"),
        "السعودية" to Region("SA", "ar"), "الخليج" to Region(null, "ar"),
        "english" to Region(null, "en"), "united kingdom" to Region("GB", "en"), "usa" to Region("US", "en"),
        "germany" to Region("DE", "de"), "deutsch" to Region("DE", "de"), "allemagne" to Region("DE", "de"),
        "italia" to Region("IT", "it"), "italy" to Region("IT", "it"), "italie" to Region("IT", "it"),
        "espana" to Region("ES", "es"), "spain" to Region("ES", "es"), "espagne" to Region("ES", "es"),
        "portugal" to Region("PT", "pt"), "brasil" to Region("BR", "pt"), "brazil" to Region("BR", "pt"),
        "turkey" to Region("TR", "tr"), "turkiye" to Region("TR", "tr"), "turquie" to Region("TR", "tr"),
        "netherlands" to Region("NL", "nl"), "nederland" to Region("NL", "nl"), "pays-bas" to Region("NL", "nl"),
        "poland" to Region("PL", "pl"), "polska" to Region("PL", "pl"), "romania" to Region("RO", "ro"),
        "india" to Region("IN", "hi"), "pakistan" to Region("PK", "ur"), "iran" to Region("IR", "fa"),
        "kurdistan" to Region(null, "ku"), "kurdish" to Region(null, "ku"), "kurde" to Region(null, "ku"),
        "kurdi" to Region(null, "ku"), "kurd" to Region(null, "ku"), "کوردی" to Region(null, "ku"),
        "كوردي" to Region(null, "ku"), "كردي" to Region(null, "ku"), "كردستان" to Region(null, "ku"), "کوردستان" to Region(null, "ku"),
        "afrique" to Region(null, null), "africa" to Region(null, null),
    )

    private val LANGUAGE_NAMES = mapOf(
        "french" to "fr", "francais" to "fr", "fra" to "fr", "fre" to "fr",
        "arabic" to "ar", "arabe" to "ar", "ara" to "ar", "عربي" to "ar", "العربية" to "ar",
        "english" to "en", "anglais" to "en", "eng" to "en",
        "german" to "de", "deutsch" to "de", "allemand" to "de", "deu" to "de", "ger" to "de",
        "spanish" to "es", "espanol" to "es", "espagnol" to "es", "spa" to "es",
        "italian" to "it", "italiano" to "it", "italien" to "it", "ita" to "it",
        "portuguese" to "pt", "portugues" to "pt", "por" to "pt",
        "turkish" to "tr", "turkce" to "tr", "tur" to "tr",
        "dutch" to "nl", "nederlands" to "nl", "nld" to "nl",
        "russian" to "ru", "rus" to "ru", "persian" to "fa", "farsi" to "fa", "hindi" to "hi", "urdu" to "ur",
        "kurdish" to "ku", "kurdi" to "ku", "kurmanji" to "ku", "sorani" to "ku", "kmr" to "ku", "ckb" to "ku", "kur" to "ku",
        "berber" to "ber", "amazigh" to "ber", "tamazight" to "ber",
    )

    private val PREFIX = Regex("^[\\s|\\[(]*([A-Za-z]{2,6})\\s*(?:[|\\]):\\-]|\\s+-\\s)")

    fun tag(group: String?, tvgCountry: String? = null, tvgLanguage: String? = null): Region {
        var country = normalizeCountry(tvgCountry)
        var language = normalizeLanguage(tvgLanguage)
        if (country == null || language == null) {
            val g = group.orEmpty()
            val fromPrefix = prefix(g)
            val fromWords = if (fromPrefix?.country == null || fromPrefix.language == null) words(g) else null
            country = country ?: fromPrefix?.country ?: fromWords?.country
            language = language ?: fromPrefix?.language ?: fromWords?.language
        }
        if (language == null && country != null) language = COUNTRY_LANGUAGE[country]
        return Region(country, language)
    }

    fun normalizeCountry(raw: String?): String? {
        val first = raw?.split(';', ',', '|', ' ')?.firstOrNull { it.isNotBlank() }?.trim()?.uppercase() ?: return null
        PREFIX_ALIASES[first]?.country?.let { return it }
        return first.takeIf { it.length == 2 && it in COUNTRIES }
    }

    fun normalizeLanguage(raw: String?): String? {
        val first = raw?.split(';', ',', '|')?.firstOrNull { it.isNotBlank() }?.trim() ?: return null
        val key = fold(first)
        LANGUAGE_NAMES[key]?.let { return it }
        return key.takeIf { it.length == 2 && it.all { c -> c in 'a'..'z' } }
    }

    private fun prefix(group: String): Region? {
        val m = PREFIX.find(group) ?: return null
        val code = m.groupValues[1].uppercase()
        PREFIX_ALIASES[code]?.let { return it.takeIf { r -> r.country != null || r.language != null } }
        if (code.length == 2 && code in COUNTRIES) return Region(code, COUNTRY_LANGUAGE[code])
        return null
    }

    private fun words(group: String): Region? {
        val g = fold(group)
        if (g.isEmpty()) return null
        for ((word, region) in WORDS) {
            if (region.country == null && region.language == null) continue
            val i = g.indexOf(word)
            if (i < 0) continue
            val before = if (i == 0) ' ' else g[i - 1]
            val afterIndex = i + word.length
            val after = if (afterIndex >= g.length) ' ' else g[afterIndex]
            if (!before.isLetter() && !after.isLetter()) return region
        }
        return null
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "").trim()
}
