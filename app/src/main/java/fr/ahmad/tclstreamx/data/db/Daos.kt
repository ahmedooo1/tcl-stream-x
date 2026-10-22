package fr.ahmad.tclstreamx.data.db

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

private const val ACTIVE = "INNER JOIN playlists p ON p.id = c.playlistId AND p.activeGeneration = c.generation"

/**
 * Lineup filter shared by every channel query:
 * kind selects the section (live, movies, series); playlistId = 0 means every playlist, group = null means every group, fav = 1 keeps favorites only,
 * region = null means every country and language, otherwise an upper case country code or a lower case language code.
 */
private const val LINEUP = """
    c.kind = :kind
    AND (:playlistId = 0 OR c.playlistId = :playlistId)
    AND (:grp IS NULL OR c.groupTitle = :grp)
    AND (:fav = 0 OR c.channelKey IN (SELECT channelKey FROM favorites))
    AND (:region IS NULL OR c.country = :region OR c.language = :region)
"""

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY kind = 'BUILTIN' DESC, id")
    fun observeAll(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY id")
    suspend fun all(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun byId(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE kind = 'BUILTIN' LIMIT 1")
    suspend fun builtin(): PlaylistEntity?

    @Insert
    suspend fun insert(p: PlaylistEntity): Long

    @Upsert
    suspend fun upsert(p: PlaylistEntity)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface ChannelDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelEntity>)

    /** Blocking variant used by the streaming importer, which already runs on an IO thread. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAllBlocking(channels: List<ChannelEntity>)

    @Query("DELETE FROM channels WHERE playlistId = :playlistId AND generation != :keepGeneration")
    suspend fun deleteOtherGenerations(playlistId: Long, keepGeneration: Int): Int

    @Query("DELETE FROM channels WHERE playlistId = :playlistId")
    suspend fun deleteForPlaylist(playlistId: Long)

    @Query("SELECT c.* FROM channels c $ACTIVE WHERE $LINEUP ORDER BY c.playlistId, c.position")
    fun paging(playlistId: Long, grp: String?, fav: Int, kind: Int, region: String?): PagingSource<Int, ChannelEntity>

    @Query("SELECT c.* FROM channels c $ACTIVE WHERE $LINEUP ORDER BY c.playlistId, c.position LIMIT :limit")
    suspend fun firstOfLineup(playlistId: Long, grp: String?, fav: Int, kind: Int, region: String?, limit: Int): List<ChannelEntity>

    @Query(
        """SELECT c.groupTitle AS groupTitle, COUNT(*) AS count FROM channels c $ACTIVE
           WHERE c.kind = :kind AND (:playlistId = 0 OR c.playlistId = :playlistId)
           AND (:region IS NULL OR c.country = :region OR c.language = :region)
           GROUP BY c.groupTitle ORDER BY MIN(c.playlistId), MIN(c.position)"""
    )
    fun observeGroups(playlistId: Long, kind: Int, region: String?): Flow<List<GroupCount>>

    @Query(
        """SELECT COUNT(*) FROM channels c $ACTIVE WHERE c.kind = :kind AND (:playlistId = 0 OR c.playlistId = :playlistId)
           AND (:region IS NULL OR c.country = :region OR c.language = :region)"""
    )
    fun observeCount(playlistId: Long, kind: Int, region: String?): Flow<Int>

    /** Countries and languages present in a section, most represented first. */
    @Query(
        """SELECT code, SUM(n) AS count FROM (
             SELECT c.country AS code, COUNT(*) AS n FROM channels c $ACTIVE
             WHERE (:kind < 0 OR c.kind = :kind) AND (:playlistId = 0 OR c.playlistId = :playlistId) AND c.country IS NOT NULL AND c.country != ''
             GROUP BY c.country
             UNION ALL
             SELECT c.language AS code, COUNT(*) AS n FROM channels c $ACTIVE
             WHERE (:kind < 0 OR c.kind = :kind) AND (:playlistId = 0 OR c.playlistId = :playlistId) AND c.language IS NOT NULL AND c.language != ''
             GROUP BY c.language
           ) GROUP BY code ORDER BY count DESC LIMIT 80"""
    )
    fun observeRegions(playlistId: Long, kind: Int): Flow<List<RegionCount>>

    /** Entries of a group that are not Xtream series containers (M3U episodes), to pick the series layout. */
    @Query("SELECT COUNT(*) FROM channels c $ACTIVE WHERE c.kind = :kind AND c.groupTitle = :grp AND c.url NOT LIKE 'xtream://%/series/%'")
    suspend fun countPlainEntries(kind: Int, grp: String): Int

    /** Every entry of one series group, for grouping episodes by show in memory. */
    @Query("SELECT c.* FROM channels c $ACTIVE WHERE c.kind = :kind AND c.groupTitle = :grp ORDER BY c.position LIMIT :limit")
    suspend fun allInGroup(kind: Int, grp: String, limit: Int): List<ChannelEntity>

    @Query("SELECT c.* FROM channels c $ACTIVE WHERE c.id = :id")
    suspend fun byId(id: Long): ChannelEntity?

    @Query("SELECT c.* FROM channels c $ACTIVE WHERE c.channelKey = :key LIMIT 1")
    suspend fun byKey(key: String): ChannelEntity?

    /** Next channel of the lineup (wraps around in the caller when null). */
    @Query(
        """SELECT c.* FROM channels c $ACTIVE WHERE $LINEUP
           AND (c.playlistId > :fromPlaylist OR (c.playlistId = :fromPlaylist AND c.position > :fromPosition))
           ORDER BY c.playlistId, c.position LIMIT 1"""
    )
    suspend fun next(playlistId: Long, grp: String?, fav: Int, kind: Int, region: String?, fromPlaylist: Long, fromPosition: Int): ChannelEntity?

    @Query(
        """SELECT c.* FROM channels c $ACTIVE WHERE $LINEUP
           AND (c.playlistId < :fromPlaylist OR (c.playlistId = :fromPlaylist AND c.position < :fromPosition))
           ORDER BY c.playlistId DESC, c.position DESC LIMIT 1"""
    )
    suspend fun previous(playlistId: Long, grp: String?, fav: Int, kind: Int, region: String?, fromPlaylist: Long, fromPosition: Int): ChannelEntity?

    @Query(
        """SELECT c.* FROM channels c $ACTIVE WHERE $LINEUP
           ORDER BY c.playlistId DESC, c.position DESC LIMIT 1"""
    )
    suspend fun lastOfLineup(playlistId: Long, grp: String?, fav: Int, kind: Int, region: String?): ChannelEntity?

    @Query("SELECT c.* FROM channels c $ACTIVE WHERE c.playlistId = :playlistId AND c.position = :number LIMIT 1")
    suspend fun byNumber(playlistId: Long, number: Int): ChannelEntity?

    @Query(
        """SELECT c.* FROM channels c $ACTIVE WHERE c.nameNorm LIKE '%' || :query || '%'
           ORDER BY (c.nameNorm LIKE :query || '%') DESC, length(c.nameNorm), c.position LIMIT :limit"""
    )
    suspend fun search(query: String, limit: Int): List<ChannelEntity>

    /**
     * Search with filters: kind < 0 for every section, playlistId = 0 for every source, region = null for every
     * country and language, online = 1 for channels that answered at their last check.
     */
    @Query(
        """SELECT c.* FROM channels c $ACTIVE WHERE c.nameNorm LIKE '%' || :query || '%'
           AND (:kind < 0 OR c.kind = :kind)
           AND (:playlistId = 0 OR c.playlistId = :playlistId)
           AND (:region IS NULL OR c.country = :region OR c.language = :region)
           AND (:online = 0 OR c.status = 1)
           ORDER BY (c.nameNorm LIKE :query || '%') DESC, length(c.nameNorm), c.position LIMIT :limit"""
    )
    suspend fun searchFiltered(query: String, kind: Int, playlistId: Long, region: String?, online: Int, limit: Int): List<ChannelEntity>

    /** Unified search restricted to one section (live, movies or series). */
    @Query(
        """SELECT c.* FROM channels c $ACTIVE WHERE c.kind = :kind AND c.nameNorm LIKE '%' || :query || '%'
           ORDER BY (c.nameNorm LIKE :query || '%') DESC, length(c.nameNorm), c.position LIMIT :limit"""
    )
    suspend fun searchKind(query: String, kind: Int, limit: Int): List<ChannelEntity>

    @Query("SELECT c.* FROM channels c $ACTIVE WHERE c.category = :category ORDER BY c.status != 1, c.position")
    fun observeCategory(category: String): Flow<List<ChannelEntity>>

    @Query(
        """SELECT c.* FROM channels c $ACTIVE INNER JOIN history h ON h.channelKey = c.channelKey
           GROUP BY c.channelKey ORDER BY h.watchedAt DESC LIMIT :limit"""
    )
    fun observeRecent(limit: Int): Flow<List<ChannelEntity>>

    @Query(
        """SELECT c.* FROM channels c $ACTIVE INNER JOIN favorites f ON f.channelKey = c.channelKey
           GROUP BY c.channelKey ORDER BY f.addedAt DESC LIMIT :limit"""
    )
    fun observeFavorites(limit: Int): Flow<List<ChannelEntity>>

    @Query(
        """SELECT c.* FROM channels c $ACTIVE WHERE $LINEUP
           ORDER BY c.statusCheckedAt, c.position LIMIT :limit"""
    )
    suspend fun stalest(playlistId: Long, grp: String?, fav: Int, kind: Int, region: String?, limit: Int): List<ChannelEntity>

    @Query("UPDATE channels SET status = :status, statusDetail = :detail, statusCheckedAt = :checkedAt WHERE channelKey = :key")
    suspend fun updateStatus(key: String, status: Int, detail: String?, checkedAt: Long)

    @Query("SELECT DISTINCT c.tvgId FROM channels c $ACTIVE WHERE c.tvgId IS NOT NULL AND c.tvgId != ''")
    suspend fun allTvgIds(): List<String>
}

@Dao
interface FavoriteDao {
    @Query("SELECT channelKey FROM favorites")
    fun observeKeys(): Flow<List<String>>

    @Upsert
    suspend fun add(f: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE channelKey = :key")
    suspend fun remove(key: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE channelKey = :key)")
    suspend fun isFavorite(key: String): Boolean
}

@Dao
interface HistoryDao {
    @Upsert
    suspend fun touch(h: HistoryEntity)

    @Query("DELETE FROM history")
    suspend fun clear()

    @Query("DELETE FROM history WHERE channelKey NOT IN (SELECT channelKey FROM history ORDER BY watchedAt DESC LIMIT :keep)")
    suspend fun trim(keep: Int)
}

@Dao
interface EpgDao {
    @Insert
    suspend fun insertAll(items: List<ProgrammeEntity>)

    @Insert
    fun insertAllBlocking(items: List<ProgrammeEntity>)

    @Query("DELETE FROM programmes")
    suspend fun clear()

    @Query("DELETE FROM programmes WHERE stop < :before")
    suspend fun deleteEndedBefore(before: Long)

    @Query("SELECT * FROM programmes WHERE channelTvgId = :tvgId AND stop > :now ORDER BY start LIMIT :limit")
    suspend fun upcoming(tvgId: String, now: Long, limit: Int): List<ProgrammeEntity>

    @Query("SELECT * FROM programmes WHERE channelTvgId = :tvgId AND stop > :now ORDER BY start LIMIT :limit")
    fun observeUpcoming(tvgId: String, now: Long, limit: Int): Flow<List<ProgrammeEntity>>

    @Query("SELECT COUNT(*) FROM programmes")
    fun observeCount(): Flow<Int>
}

@Dao
interface VodProgressDao {
    @Upsert
    suspend fun save(p: VodProgressEntity)

    @Query("SELECT * FROM vod_progress WHERE `key` = :key")
    suspend fun get(key: String): VodProgressEntity?

    @Query("SELECT * FROM vod_progress WHERE positionMs > 0 AND positionMs < durationMs * 0.95 ORDER BY updatedAt DESC LIMIT :limit")
    fun observeContinue(limit: Int): Flow<List<VodProgressEntity>>

    @Query("DELETE FROM vod_progress WHERE `key` = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM vod_progress")
    suspend fun clear()
}
