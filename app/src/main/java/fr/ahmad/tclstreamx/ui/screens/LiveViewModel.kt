package fr.ahmad.tclstreamx.ui.screens

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import fr.ahmad.tclstreamx.TclApp
import fr.ahmad.tclstreamx.data.db.ChannelEntity
import fr.ahmad.tclstreamx.data.db.GroupCount
import fr.ahmad.tclstreamx.data.db.PlaylistEntity
import fr.ahmad.tclstreamx.data.db.ProgrammeEntity
import fr.ahmad.tclstreamx.player.Lineup
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface GroupSel {
    data object All : GroupSel
    data object Favorites : GroupSel
    data class Named(val title: String) : GroupSel
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as TclApp).container

    val playlists: StateFlow<List<PlaylistEntity>> =
        c.db.playlists().observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val playlistId = MutableStateFlow(0L)
    val group = MutableStateFlow<GroupSel>(GroupSel.All)
    val focused = MutableStateFlow<ChannelEntity?>(null)

    val groups: StateFlow<List<GroupCount>> = playlistId
        .flatMapLatest { c.db.channels().observeGroups(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val totalCount: StateFlow<Int> = playlistId
        .flatMapLatest { c.db.channels().observeCount(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val channels: Flow<PagingData<ChannelEntity>> = combine(playlistId, group) { p, g -> p to g }
        .flatMapLatest { (p, g) ->
            Pager(PagingConfig(pageSize = PAGE, prefetchDistance = PAGE, enablePlaceholders = true, maxSize = PAGE * 10)) {
                c.db.channels().paging(p, (g as? GroupSel.Named)?.title, if (g == GroupSel.Favorites) 1 else 0)
            }.flow
        }
        .cachedIn(viewModelScope)

    val favoriteKeys: StateFlow<Set<String>> = c.db.favorites().observeKeys().map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val focusedEpg: StateFlow<List<ProgrammeEntity>> = focused
        .flatMapLatest { ch -> epgFor(ch?.tvgId) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val checkProgress = c.checker.progress

    fun epgFor(tvgId: String?): Flow<List<ProgrammeEntity>> {
        if (tvgId.isNullOrBlank()) return flowOf(emptyList())
        val full = tvgId.lowercase()
        val base = tvgId.substringBefore('@').lowercase()
        val now = System.currentTimeMillis()
        return combine(c.db.epg().observeUpcoming(full, now, 4), c.db.epg().observeUpcoming(base, now, 4)) { a, b ->
            a.ifEmpty { b }
        }
    }

    fun lineup(): Lineup {
        val g = group.value
        return Lineup(playlistId.value, (g as? GroupSel.Named)?.title, g == GroupSel.Favorites)
    }

    fun selectPlaylist(id: Long) {
        playlistId.value = id
        group.value = GroupSel.All
    }

    fun checkCurrentLineup() {
        val l = lineup()
        c.checker.checkLineup(l.playlistId, l.group, l.favoritesOnly)
    }

    fun toggleFavorite(ch: ChannelEntity) {
        viewModelScope.launch {
            val dao = c.db.favorites()
            if (dao.isFavorite(ch.channelKey)) dao.remove(ch.channelKey)
            else dao.add(fr.ahmad.tclstreamx.data.db.FavoriteEntity(ch.channelKey, System.currentTimeMillis()))
        }
    }

    companion object {
        private const val PAGE = 60
    }
}
