package fr.ahmad.tclstreamx.data.sports

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class MatchState { SCHEDULED, LIVE, FINISHED }

data class Team(val name: String, val logo: String?, val score: String?)

data class Match(
    val id: String,
    val leagueId: String,
    val leagueName: String,
    val start: Instant,
    val home: Team,
    val away: Team,
    val state: MatchState,
    val statusText: String,
    val venue: String?,
)

@Serializable
data class SportsGuide(
    val reviewedAt: String,
    val note: String,
    val regions: Map<String, List<LeagueRights>>,
    val freeLegal: List<FreeOption>,
    val leagues: List<LeagueRef>,
)

@Serializable
data class LeagueRights(
    val league: String,
    val name: String,
    val destinations: List<String>,
    val verified: Boolean,
    val source: String = "",
    val free: Boolean = false,
)

@Serializable
data class FreeOption(val name: String, val kind: String, val channelKey: String? = null, val query: String? = null)

@Serializable
data class LeagueRef(val id: String, val sport: String)

data class SportsResult(val matches: List<Match>, val failedLeagues: List<String>, val fetchedAt: Long)

/**
 * Match schedules from ESPN's public scoreboard endpoint (no key, no account). It is not an
 * officially documented API, so every field is read defensively and a failing league is reported
 * instead of breaking the whole hub. Rights holders come from the reviewed asset file.
 */
class SportsRepository(private val context: Context, private val http: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private var cache: SportsResult? = null

    val guide: SportsGuide by lazy {
        context.assets.open("sports_destinations.json").use {
            json.decodeFromString(SportsGuide.serializer(), it.readBytes().decodeToString())
        }
    }

    suspend fun schedule(force: Boolean = false): SportsResult = withContext(Dispatchers.IO) {
        cache?.let { if (!force && System.currentTimeMillis() - it.fetchedAt < CACHE_MS) return@withContext it }
        val today = LocalDate.now(ZoneId.systemDefault())
        val range = "${today.minusDays(1).format(DAY)}-${today.plusDays(DAYS_AHEAD).format(DAY)}"
        val failed = mutableListOf<String>()
        val matches = coroutineScope {
            guide.leagues.map { league ->
                async {
                    try {
                        fetchLeague(league, range)
                    } catch (e: IOException) {
                        synchronized(failed) { failed += league.id }
                        emptyList()
                    } catch (e: IllegalArgumentException) {
                        synchronized(failed) { failed += league.id }
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        val now = Instant.now()
        val sorted = matches
            .filter { it.state != MatchState.FINISHED || it.start.isAfter(now.minusSeconds(6 * 3600)) }
            .sortedWith(compareBy<Match> { it.state != MatchState.LIVE }.thenBy { it.start })
        SportsResult(sorted, failed, System.currentTimeMillis()).also { cache = it }
    }

    fun rightsFor(region: String, leagueId: String): LeagueRights? =
        guide.regions[region]?.firstOrNull { it.league == leagueId }

    private fun fetchLeague(league: LeagueRef, range: String): List<Match> {
        val url = "$BASE/${league.sport}/${league.id}/scoreboard?dates=$range&limit=200"
        val req = Request.Builder().url(url).header("User-Agent", "TCLStreamX/1.0").build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string() ?: throw IOException("empty")
        }
        val root = json.parseToJsonElement(body).obj() ?: return emptyList()
        val leagueName = root.arr("leagues")?.firstOrNull()?.obj()?.str("name") ?: league.id
        return root.arr("events").orEmpty().mapNotNull { parseEvent(it, league.id, leagueName) }
    }

    private fun parseEvent(e: JsonElement, leagueId: String, leagueName: String): Match? {
        val ev = e.obj() ?: return null
        val comp = ev.arr("competitions")?.firstOrNull()?.obj() ?: return null
        val competitors = comp.arr("competitors").orEmpty().mapNotNull { it.obj() }
        val home = competitors.firstOrNull { it.str("homeAway") == "home" } ?: competitors.getOrNull(0) ?: return null
        val away = competitors.firstOrNull { it.str("homeAway") == "away" } ?: competitors.getOrNull(1) ?: return null
        val type = (comp.obj("status") ?: ev.obj("status"))?.obj("type")
        val state = when (type?.str("state")) {
            "in" -> MatchState.LIVE
            "post" -> MatchState.FINISHED
            else -> MatchState.SCHEDULED
        }
        val start = ev.str("date")?.let { parseInstant(it) } ?: return null
        return Match(
            id = ev.str("id") ?: return null,
            leagueId = leagueId,
            leagueName = leagueName,
            start = start,
            home = team(home, state),
            away = team(away, state),
            state = state,
            statusText = type?.str("shortDetail") ?: "",
            venue = comp.obj("venue")?.str("fullName"),
        )
    }

    private fun team(c: JsonObject, state: MatchState): Team {
        val t = c.obj("team")
        return Team(
            name = t?.str("shortDisplayName") ?: t?.str("displayName") ?: "?",
            logo = t?.str("logo"),
            score = if (state == MatchState.SCHEDULED) null else c.str("score"),
        )
    }

    private fun JsonElement.obj(): JsonObject? = this as? JsonObject
    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray
    private fun JsonObject.str(key: String): String? = (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    companion object {
        private const val BASE = "https://site.api.espn.com/apis/site/v2/sports"
        private const val DAYS_AHEAD = 7L
        private const val CACHE_MS = 10 * 60_000L
        private val DAY = DateTimeFormatter.BASIC_ISO_DATE

        /** ESPN dates look like 2026-10-21T19:00Z (no seconds). */
        fun parseInstant(s: String): Instant? = try {
            Instant.parse(if (s.length == 17 && s.endsWith("Z")) s.dropLast(1) + ":00Z" else s)
        } catch (e: java.time.format.DateTimeParseException) {
            null
        }
    }
}
