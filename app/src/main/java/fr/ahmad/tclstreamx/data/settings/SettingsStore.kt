package fr.ahmad.tclstreamx.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
        )
    }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setLastChannel(key: String) = context.dataStore.edit { it[Keys.LAST_CHANNEL] = key }
    suspend fun setEpgUrl(url: String) = context.dataStore.edit { it[Keys.EPG_URL] = url.trim() }
    suspend fun setPreviewOnFocus(v: Boolean) = context.dataStore.edit { it[Keys.PREVIEW] = v }
    suspend fun setAutoCheck(v: Boolean) = context.dataStore.edit { it[Keys.AUTO_CHECK] = v }
    suspend fun setSportsRegion(v: String) = context.dataStore.edit { it[Keys.SPORTS_REGION] = v }
    suspend fun markBuiltinChecked(t: Long) = context.dataStore.edit { it[Keys.LAST_BUILTIN_CHECK] = t }
    suspend fun markEpgRefreshed(t: Long) = context.dataStore.edit { it[Keys.LAST_EPG] = t }
}
