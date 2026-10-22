package fr.ahmad.tclstreamx.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class AppSettings(
    val lastChannelKey: String? = null,
    val epgUrl: String = "",
    val previewOnFocus: Boolean = true,
    val autoCheckBuiltin: Boolean = true,
    val sportsRegion: String = "FR",
    val lastBuiltinCheck: Long = 0,
    val lastEpgRefresh: Long = 0,
    /** Refresh playlists and accounts older than a day when the app starts. */
    val autoRefresh: Boolean = true,
    /** Subtitle text size multiplier. */
    val subtitleScale: Float = 1f,
    /** 0 white with outline, 1 yellow with outline, 2 white on a dark box. */
    val subtitleStyle: Int = 0,
    /** A parental code is set (its salted hash is stored, never the code). */
    val pinSet: Boolean = false,
)

class SettingsStore(private val context: Context) {
    private object Keys {
        val LAST_CHANNEL = stringPreferencesKey("last_channel")
        val EPG_URL = stringPreferencesKey("epg_url")
        val PREVIEW = booleanPreferencesKey("preview_on_focus")
        val AUTO_CHECK = booleanPreferencesKey("auto_check_builtin")
        val SPORTS_REGION = stringPreferencesKey("sports_region")
        val LAST_BUILTIN_CHECK = longPreferencesKey("last_builtin_check")
        val LAST_EPG = longPreferencesKey("last_epg_refresh")
        val AUTO_REFRESH = booleanPreferencesKey("auto_refresh")
        val SUB_SCALE = floatPreferencesKey("subtitle_scale")
        val SUB_STYLE = intPreferencesKey("subtitle_style")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { p ->
        AppSettings(
            lastChannelKey = p[Keys.LAST_CHANNEL],
            epgUrl = p[Keys.EPG_URL].orEmpty(),
            previewOnFocus = p[Keys.PREVIEW] ?: true,
            autoCheckBuiltin = p[Keys.AUTO_CHECK] ?: true,
            sportsRegion = p[Keys.SPORTS_REGION] ?: "FR",
            lastBuiltinCheck = p[Keys.LAST_BUILTIN_CHECK] ?: 0,
            lastEpgRefresh = p[Keys.LAST_EPG] ?: 0,
            autoRefresh = p[Keys.AUTO_REFRESH] ?: true,
            subtitleScale = p[Keys.SUB_SCALE] ?: 1f,
            subtitleStyle = p[Keys.SUB_STYLE] ?: 0,
            pinSet = p[Keys.PIN_HASH] != null,
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLastChannel(key: String) = context.dataStore.edit { it[Keys.LAST_CHANNEL] = key }
    suspend fun clearLastChannel() = context.dataStore.edit { it.remove(Keys.LAST_CHANNEL) }
    suspend fun setAutoRefresh(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_REFRESH] = v }
    suspend fun setSubtitleScale(v: Float) = context.dataStore.edit { it[Keys.SUB_SCALE] = v }
    suspend fun setSubtitleStyle(v: Int) = context.dataStore.edit { it[Keys.SUB_STYLE] = v }

    /** Parental code: only a salted SHA-256 hash is stored. */
    suspend fun setPin(pin: String) {
        val salt = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        context.dataStore.edit {
            it[Keys.PIN_SALT] = salt
            it[Keys.PIN_HASH] = hash(salt, pin)
        }
    }

    suspend fun clearPin() = context.dataStore.edit {
        it.remove(Keys.PIN_SALT)
        it.remove(Keys.PIN_HASH)
    }

    suspend fun checkPin(pin: String): Boolean {
        val p = context.dataStore.data.first()
        val salt = p[Keys.PIN_SALT] ?: return false
        val expected = p[Keys.PIN_HASH] ?: return false
        return java.security.MessageDigest.isEqual(expected.toByteArray(), hash(salt, pin).toByteArray())
    }

    private fun hash(salt: String, pin: String): String =
        java.security.MessageDigest.getInstance("SHA-256").digest((salt + ":" + pin).toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun setEpgUrl(url: String) = context.dataStore.edit { it[Keys.EPG_URL] = url.trim() }
    suspend fun setPreviewOnFocus(v: Boolean) = context.dataStore.edit { it[Keys.PREVIEW] = v }
    suspend fun setAutoCheck(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_CHECK] = v }
    suspend fun setSportsRegion(v: String) = context.dataStore.edit { it[Keys.SPORTS_REGION] = v }
    suspend fun markBuiltinChecked(t: Long) = context.dataStore.edit { it[Keys.LAST_BUILTIN_CHECK] = t }
    suspend fun markEpgRefreshed(t: Long) = context.dataStore.edit { it[Keys.LAST_EPG] = t }
}
