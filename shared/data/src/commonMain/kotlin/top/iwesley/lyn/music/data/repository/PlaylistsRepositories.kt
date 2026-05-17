package top.iwesley.lyn.music.data.repository

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import top.iwesley.lyn.music.core.model.DiagnosticLogger
import top.iwesley.lyn.music.core.model.ImportSourceType
import top.iwesley.lyn.music.core.model.LyricsHttpClient
import top.iwesley.lyn.music.core.model.NoopDiagnosticLogger
import top.iwesley.lyn.music.core.model.PlaylistDetail
import top.iwesley.lyn.music.core.model.PlaylistKind
import top.iwesley.lyn.music.core.model.PlaylistSummary
import top.iwesley.lyn.music.core.model.PlaylistTrackEntry
import top.iwesley.lyn.music.core.model.SecureCredentialStore
import top.iwesley.lyn.music.core.model.SubsonicAuthMode
import top.iwesley.lyn.music.core.model.Track
import top.iwesley.lyn.music.core.model.error
import top.iwesley.lyn.music.core.model.normalizeArtworkLocator
import top.iwesley.lyn.music.core.model.parseEmbySongLocator
import top.iwesley.lyn.music.core.model.parseSubsonicCompatibleSongLocator
import top.iwesley.lyn.music.data.db.ImportSourceEntity
import top.iwesley.lyn.music.data.db.LynMusicDatabase
import top.iwesley.lyn.music.data.db.PlaylistEntity
import top.iwesley.lyn.music.data.db.PlaylistRemoteBindingEntity
import top.iwesley.lyn.music.data.db.PlaylistTrackEntity
import top.iwesley.lyn.music.domain.addEmbyPlaylistItem
import top.iwesley.lyn.music.domain.createEmbyPlaylist
import top.iwesley.lyn.music.domain.deleteEmbyPlaylist
import top.iwesley.lyn.music.domain.fetchEmbyPlaylistEntries
import top.iwesley.lyn.music.domain.fetchEmbyPlaylists
import top.iwesley.lyn.music.domain.NavidromeResolvedSource
import top.iwesley.lyn.music.domain.isSubsonicCompatibleSourceType
import top.iwesley.lyn.music.domain.normalizeSubsonicBaseUrl
import top.iwesley.lyn.music.domain.removeEmbyPlaylistEntries
import top.iwesley.lyn.music.domain.requestNavidromeJson
import top.iwesley.lyn.music.domain.resolveEmbySource
import top.iwesley.lyn.music.domain.toSubsonicAuthMode
import top.iwesley.lyn.music.domain.updateEmbyPlaylistName

class RoomPlaylistRepository(
    private val database: LynMusicDatabase,
    private val secureCredentialStore: SecureCredentialStore,
    private val httpClient: LyricsHttpClient,
    private val logger: DiagnosticLogger = NoopDiagnosticLogger,
) : PlaylistRepository {
    override val playlists: Flow<List<PlaylistSummary>> = combine(
        database.playlistDao().observeAll(),
        database.playlistTrackDao().observeAll(),
        database.trackDao().observeAll(),
        database.importSourceDao().observeAll(),
        database.lyricsCacheDao().observeArtworkLocators(),
    ) { playlists, playlistTracks, trackEntities, sources, artworkRows ->
        val enabledSourceIds = sources.asSequence()
            .filter { it.enabled }
            .map { it.id }
            .toSet()
        val artworkOverrides = effectiveArtworkOverridesByTrackId(artworkRows)
        val trackById = trackEntities.associate { entity ->
            entity.id to entity.toDomain(artworkOverrides[entity.id])
        }
        playlists.map { playlist ->
            val visiblePlaylistTracks = playlistTracks.asSequence()
                .filter {
                    it.playlistId == playlist.id &&
                        it.sourceId in enabledSourceIds &&
                        trackById.containsKey(it.trackId)
                }
                .toList()
            val memberTrackIds = visiblePlaylistTracks.asSequence()
                .map { it.trackId }
                .toCollection(linkedSetOf())
            playlist.toSummary(
                memberTrackIds = memberTrackIds,
                artworkLocator = visiblePlaylistTracks.latestPlaylistArtworkLocator(trackById),
            )
        }
    }

    override fun observePlaylistDetail(playlistId: String): Flow<PlaylistDetail?> {
        return combine(
            database.playlistDao().observeAll(),
            database.playlistTrackDao().observeAll(),
            database.trackDao().observeAll(),
            database.importSourceDao().observeAll(),
            database.lyricsCacheDao().observeArtworkLocators(),
        ) { playlists, playlistTracks, trackEntities, sources, artworkRows ->
            val playlist = playlists.firstOrNull { it.id == playlistId } ?: return@combine null
            val artworkOverrides = effectiveArtworkOverridesByTrackId(artworkRows)
            val enabledSourceIds = sources.asSequence()
                .filter { it.enabled }
                .map { it.id }
                .toSet()
            val trackById = trackEntities.associate { entity ->
                entity.id to entity.toDomain(artworkOverrides[entity.id])
            }
            val sourceLabelById = sources.associate { it.id to it.label }
            playlist.toDetail(
                tracks = playlistTracks.filter { it.sourceId in enabledSourceIds },
                trackById = trackById,
                sourceLabelById = sourceLabelById,
            )
        }
    }

    override suspend fun createPlaylist(name: String): Result<PlaylistSummary> {
        return runCatching {
            val displayName = name.trim()
            require(displayName.isNotBlank()) { "歌单名称不能为空。" }
            val normalizedName = normalizePlaylistName(displayName)
            require(database.playlistDao().getByNormalizedName(normalizedName) == null) { "歌单已存在。" }
            val entity = PlaylistEntity(
                id = newId("playlist"),
                name = displayName,
                normalizedName = normalizedName,
                createdLocally = true,
                createdAt = now(),
                updatedAt = now(),
            )
            database.playlistDao().upsert(entity)
            entity.toSummary()
        }
    }

    override suspend fun renamePlaylist(playlistId: String, name: String): Result<PlaylistSummary> {
        return runCatching {
            val playlist = database.playlistDao().getById(playlistId) ?: error("歌单不存在。")
            val displayName = name.trim()
            require(displayName.isNotBlank()) { "歌单名称不能为空。" }
            val normalizedName = normalizePlaylistName(displayName)
            val duplicate = database.playlistDao().getByNormalizedName(normalizedName)
            require(duplicate == null || duplicate.id == playlistId) { "歌单已存在。" }

            val bindings = database.playlistRemoteBindingDao().getByPlaylistId(playlistId)
            bindings.forEach { binding ->
                if (database.importSourceDao().getById(binding.sourceId)?.isEmbySource() == true) {
                    val resolvedSource = resolveEmbySource(database, secureCredentialStore, binding.sourceId)
                        ?: error("Emby 来源不可用，无法重命名歌单。")
                    updateEmbyPlaylistName(
                        httpClient = httpClient,
                        source = resolvedSource,
                        playlistId = binding.remotePlaylistId,
                        name = displayName,
                        logger = logger,
                    )
                } else {
                    val resolvedSource = resolveSubsonicCompatibleSource(binding.sourceId)
                        ?: error("Subsonic-compatible 来源不可用，无法重命名歌单。")
                    requestNavidromeJson(
                        httpClient = httpClient,
                        source = resolvedSource,
                        endpoint = "updatePlaylist",
                        parameters = mapOf(
                            "playlistId" to binding.remotePlaylistId,
                            "name" to displayName,
                        ),
                        logger = logger,
                        logContext = "playlist=\"${playlist.name}\" rename remotePlaylist=${binding.remotePlaylistId}",
                    )
                }
            }

            val updatedAt = now()
            val updatedPlaylist = playlist.copy(
                name = displayName,
                normalizedName = normalizedName,
                updatedAt = updatedAt,
            )
            database.playlistDao().upsert(updatedPlaylist)
            bindings.forEach { binding ->
                database.playlistRemoteBindingDao().upsert(
                    binding.copy(
                        remoteName = displayName,
                        lastSyncedAt = updatedAt,
                    ),
                )
            }
            updatedPlaylist.toSummary(
                memberTrackIds = database.playlistTrackDao()
                    .getByPlaylistId(playlistId)
                    .mapTo(linkedSetOf()) { it.trackId },
            )
        }
    }

    override suspend fun deletePlaylist(playlistId: String): Result<Unit> {
        return runCatching {
            val playlist = database.playlistDao().getById(playlistId) ?: error("歌单不存在。")
            val bindings = database.playlistRemoteBindingDao().getByPlaylistId(playlistId)
            bindings.forEach { binding ->
                if (database.importSourceDao().getById(binding.sourceId)?.isEmbySource() == true) {
                    val resolvedSource = resolveEmbySource(database, secureCredentialStore, binding.sourceId)
                        ?: error("Emby 来源不可用，无法删除歌单。")
                    deleteEmbyPlaylist(
                        httpClient = httpClient,
                        source = resolvedSource,
                        playlistId = binding.remotePlaylistId,
                        logger = logger,
                    )
                } else {
                    val resolvedSource = resolveSubsonicCompatibleSource(binding.sourceId)
                        ?: error("Subsonic-compatible 来源不可用，无法删除歌单。")
                    requestNavidromeJson(
                        httpClient = httpClient,
                        source = resolvedSource,
                        endpoint = "deletePlaylist",
                        parameters = mapOf("id" to binding.remotePlaylistId),
                        logger = logger,
                        logContext = "playlist=\"${playlist.name}\" delete remotePlaylist=${binding.remotePlaylistId}",
                    )
                }
            }
            deleteLocalPlaylist(playlistId)
        }
    }

    override suspend fun addTrackToPlaylist(playlistId: String, track: Track): Result<Unit> {
        return runCatching {
            val playlist = database.playlistDao().getById(playlistId) ?: error("歌单不存在。")
            if (database.playlistTrackDao().getByPlaylistIdAndTrackId(playlistId, track.id) != null) {
                error("歌曲已在歌单中。")
            }
            val subsonicSong = parseSubsonicCompatibleSongLocator(track.mediaLocator)
                ?.takeIf { it.sourceId == track.sourceId }
            if (subsonicSong != null) {
                addSubsonicCompatibleTrackToPlaylist(playlist, track, subsonicSong.itemId)
            } else {
                val embySong = parseEmbySongLocator(track.mediaLocator)
                    ?.takeIf { it.first == track.sourceId }
                if (embySong != null) {
                    addEmbyTrackToPlaylist(playlist, track, embySong.second)
                } else {
                    addLocalTrackToPlaylist(playlist, track)
                }
            }
        }
    }

    override suspend fun removeTrackFromPlaylist(playlistId: String, trackId: String): Result<Unit> {
        return runCatching {
            val playlist = database.playlistDao().getById(playlistId) ?: error("歌单不存在。")
            val row = database.playlistTrackDao().getByPlaylistIdAndTrackId(playlistId, trackId) ?: return@runCatching
            val binding = database.playlistRemoteBindingDao().getByPlaylistIdAndSourceId(playlistId, row.sourceId)
            if (binding != null && row.remoteOrdinal != null) {
                if (database.importSourceDao().getById(row.sourceId)?.isEmbySource() == true) {
                    removeEmbyTrackFromPlaylist(
                        playlist = playlist,
                        binding = binding,
                        row = row,
                    )
                } else {
                    val resolvedSource = resolveSubsonicCompatibleSource(row.sourceId)
                        ?: error("Subsonic-compatible 来源不可用，无法更新歌单。")
                    requestNavidromeJson(
                        httpClient = httpClient,
                        source = resolvedSource,
                        endpoint = "updatePlaylist",
                        parameters = mapOf(
                            "playlistId" to binding.remotePlaylistId,
                            "songIndexToRemove" to row.remoteOrdinal.toString(),
                        ),
                        logger = logger,
                        logContext = "playlist=\"${playlist.name}\" remove track=$trackId",
                    )
                    syncRemoteBinding(
                        playlist = playlist,
                        sourceId = row.sourceId,
                        remotePlaylistId = binding.remotePlaylistId,
                        remoteName = binding.remoteName,
                    )
                }
            } else {
                database.playlistTrackDao().deleteByPlaylistIdAndTrackId(playlistId, trackId)
                touchPlaylist(playlist)
                cleanupPlaylistIfNecessary(playlistId)
            }
        }
    }

    override suspend fun refreshNavidromePlaylists(): Result<Unit> {
        return runCatching {
            val remoteSources = database.importSourceDao().getAll()
                .filter { it.subsonicCompatibleSourceType() != null || it.isEmbySource() }
            cleanupRemovedRemoteSources(remoteSources.mapTo(linkedSetOf()) { it.id })
            val failures = mutableListOf<String>()
            remoteSources
                .filter { it.enabled }
                .forEach { source ->
                runCatching {
                    if (source.isEmbySource()) {
                        syncEmbySourcePlaylists(source)
                    } else {
                        syncSourcePlaylists(source)
                    }
                }
                    .onFailure { throwable ->
                        failures += "${source.label}: ${throwable.message.orEmpty()}"
                    }
            }
            if (failures.isNotEmpty()) {
                error(failures.joinToString("\n"))
            }
        }
    }

    private suspend fun addLocalTrackToPlaylist(
        playlist: PlaylistEntity,
        track: Track,
    ) {
        val nextOrdinal = database.playlistTrackDao().getByPlaylistId(playlist.id)
            .mapNotNull { it.localOrdinal }
            .maxOrNull()
            ?.plus(1)
            ?: 0
        database.playlistTrackDao().upsert(
            PlaylistTrackEntity(
                playlistId = playlist.id,
                trackId = track.id,
                sourceId = track.sourceId,
                addedAt = now(),
                localOrdinal = nextOrdinal,
                remoteOrdinal = null,
            ),
        )
        touchPlaylist(playlist)
    }

    private suspend fun addSubsonicCompatibleTrackToPlaylist(
        playlist: PlaylistEntity,
        track: Track,
        songId: String,
    ) {
        val resolvedSource = resolveSubsonicCompatibleSource(track.sourceId)
            ?: error("Subsonic-compatible 来源不可用，无法更新歌单。")
        val binding = ensureRemoteBinding(
            playlist = playlist,
            sourceId = track.sourceId,
            resolvedSource = resolvedSource,
        )
        requestNavidromeJson(
            httpClient = httpClient,
            source = resolvedSource,
            endpoint = "updatePlaylist",
            parameters = mapOf(
                "playlistId" to binding.remotePlaylistId,
                "songIdToAdd" to songId,
            ),
            logger = logger,
            logContext = "playlist=\"${playlist.name}\" add track=${track.id}",
        )
        syncRemoteBinding(
            playlist = playlist,
            sourceId = track.sourceId,
            remotePlaylistId = binding.remotePlaylistId,
            remoteName = binding.remoteName,
        )
    }

    private suspend fun addEmbyTrackToPlaylist(
        playlist: PlaylistEntity,
        track: Track,
        itemId: String,
    ) {
        val resolvedSource = resolveEmbySource(database, secureCredentialStore, track.sourceId)
            ?: error("Emby 来源不可用，无法更新歌单。")
        val binding = ensureEmbyRemoteBinding(
            playlist = playlist,
            sourceId = track.sourceId,
            resolvedSource = resolvedSource,
        )
        addEmbyPlaylistItem(
            httpClient = httpClient,
            source = resolvedSource,
            playlistId = binding.remotePlaylistId,
            itemId = itemId,
            logger = logger,
        )
        syncEmbyRemoteBinding(
            playlist = playlist,
            sourceId = track.sourceId,
            remotePlaylistId = binding.remotePlaylistId,
            remoteName = binding.remoteName,
        )
    }

    private suspend fun removeEmbyTrackFromPlaylist(
        playlist: PlaylistEntity,
        binding: PlaylistRemoteBindingEntity,
        row: PlaylistTrackEntity,
    ) {
        val resolvedSource = resolveEmbySource(database, secureCredentialStore, row.sourceId)
            ?: error("Emby 来源不可用，无法更新歌单。")
        val entries = fetchEmbyPlaylistEntries(
            httpClient = httpClient,
            source = resolvedSource,
            playlistId = binding.remotePlaylistId,
            logger = logger,
        )
        val entry = entries.getOrNull(row.remoteOrdinal ?: -1)
            ?.takeIf { embyTrackIdFor(row.sourceId, it.itemId) == row.trackId }
            ?: entries.firstOrNull { embyTrackIdFor(row.sourceId, it.itemId) == row.trackId }
            ?: error("Emby 远端歌单未找到要移除的歌曲。")
        val entryId = entry.playlistItemId ?: error("Emby 远端歌单缺少可移除条目 ID。")
        removeEmbyPlaylistEntries(
            httpClient = httpClient,
            source = resolvedSource,
            playlistId = binding.remotePlaylistId,
            entryIds = listOf(entryId),
            logger = logger,
        )
        syncEmbyRemoteBinding(
            playlist = playlist,
            sourceId = row.sourceId,
            remotePlaylistId = binding.remotePlaylistId,
            remoteName = binding.remoteName,
        )
    }

    private suspend fun ensureRemoteBinding(
        playlist: PlaylistEntity,
        sourceId: String,
        resolvedSource: NavidromeResolvedSource,
    ): PlaylistRemoteBindingEntity {
        database.playlistRemoteBindingDao().getByPlaylistIdAndSourceId(playlist.id, sourceId)?.let { return it }

        val remotePlaylist = fetchSourcePlaylists(resolvedSource)
            .firstOrNull { normalizePlaylistName(it.name) == playlist.normalizedName }
            ?: run {
                requestNavidromeJson(
                    httpClient = httpClient,
                    source = resolvedSource,
                    endpoint = "createPlaylist",
                    parameters = mapOf("name" to playlist.name),
                    logger = logger,
                    logContext = "playlist=\"${playlist.name}\" create",
                )
                fetchSourcePlaylists(resolvedSource)
                    .firstOrNull { normalizePlaylistName(it.name) == playlist.normalizedName }
            }
            ?: error("远端歌单创建失败。")

        val binding = PlaylistRemoteBindingEntity(
            playlistId = playlist.id,
            sourceId = sourceId,
            remotePlaylistId = remotePlaylist.id,
            remoteName = remotePlaylist.name,
            lastSyncedAt = null,
        )
        database.playlistRemoteBindingDao().upsert(binding)
        return binding
    }

    private suspend fun ensureEmbyRemoteBinding(
        playlist: PlaylistEntity,
        sourceId: String,
        resolvedSource: top.iwesley.lyn.music.domain.EmbyResolvedSource,
    ): PlaylistRemoteBindingEntity {
        database.playlistRemoteBindingDao().getByPlaylistIdAndSourceId(playlist.id, sourceId)?.let { return it }

        val remotePlaylist = fetchEmbyPlaylists(httpClient, resolvedSource, logger)
            .firstOrNull { normalizePlaylistName(it.name) == playlist.normalizedName }
            ?: createEmbyPlaylist(
                httpClient = httpClient,
                source = resolvedSource,
                name = playlist.name,
                logger = logger,
            )

        val binding = PlaylistRemoteBindingEntity(
            playlistId = playlist.id,
            sourceId = sourceId,
            remotePlaylistId = remotePlaylist.id,
            remoteName = remotePlaylist.name,
            lastSyncedAt = null,
        )
        database.playlistRemoteBindingDao().upsert(binding)
        return binding
    }

    private suspend fun syncSourcePlaylists(source: ImportSourceEntity) {
        val resolvedSource = source.toSubsonicCompatibleResolvedSource()
            ?: error("Subsonic-compatible 来源缺少有效凭据，无法同步歌单。")
        val remotePlaylists = fetchSourcePlaylists(resolvedSource)
        val remoteIds = remotePlaylists.mapTo(linkedSetOf()) { it.id }
        val existingBindingsByRemoteId = database.playlistRemoteBindingDao().getBySourceId(source.id)
            .associateBy { it.remotePlaylistId }
        val playlistsByNormalizedName = database.playlistDao().getAll()
            .associateBy { it.normalizedName }
            .toMutableMap()

        remotePlaylists.forEach { remotePlaylist ->
            val existingBinding = existingBindingsByRemoteId[remotePlaylist.id]
            val currentPlaylist = existingBinding?.let { database.playlistDao().getById(it.playlistId) }
                ?: playlistsByNormalizedName[normalizePlaylistName(remotePlaylist.name)]
            val playlist = currentPlaylist ?: PlaylistEntity(
                id = newId("playlist"),
                name = remotePlaylist.name,
                normalizedName = normalizePlaylistName(remotePlaylist.name),
                createdLocally = false,
                createdAt = now(),
                updatedAt = now(),
            )
            if (currentPlaylist == null) {
                database.playlistDao().upsert(playlist)
                playlistsByNormalizedName[playlist.normalizedName] = playlist
            }
            syncRemoteBinding(
                playlist = playlist,
                sourceId = source.id,
                remotePlaylistId = remotePlaylist.id,
                remoteName = remotePlaylist.name,
            )
        }

        existingBindingsByRemoteId.values
            .filter { it.remotePlaylistId !in remoteIds }
            .forEach { binding ->
                database.playlistRemoteBindingDao().deleteByPlaylistIdAndSourceId(binding.playlistId, binding.sourceId)
                database.playlistTrackDao().deleteByPlaylistIdAndSourceId(binding.playlistId, binding.sourceId)
                cleanupPlaylistIfNecessary(binding.playlistId)
        }
    }

    private suspend fun syncEmbySourcePlaylists(source: ImportSourceEntity) {
        val resolvedSource = resolveEmbySource(database, secureCredentialStore, source.id)
            ?: error("Emby 来源缺少有效凭据，无法同步歌单。")
        val remotePlaylists = fetchEmbyPlaylists(
            httpClient = httpClient,
            source = resolvedSource,
            logger = logger,
        )
        val remoteIds = remotePlaylists.mapTo(linkedSetOf()) { it.id }
        val existingBindingsByRemoteId = database.playlistRemoteBindingDao().getBySourceId(source.id)
            .associateBy { it.remotePlaylistId }
        val playlistsByNormalizedName = database.playlistDao().getAll()
            .associateBy { it.normalizedName }
            .toMutableMap()

        remotePlaylists.forEach { remotePlaylist ->
            val existingBinding = existingBindingsByRemoteId[remotePlaylist.id]
            val currentPlaylist = existingBinding?.let { database.playlistDao().getById(it.playlistId) }
                ?: playlistsByNormalizedName[normalizePlaylistName(remotePlaylist.name)]
            val playlist = currentPlaylist ?: PlaylistEntity(
                id = newId("playlist"),
                name = remotePlaylist.name,
                normalizedName = normalizePlaylistName(remotePlaylist.name),
                createdLocally = false,
                createdAt = now(),
                updatedAt = now(),
            )
            if (currentPlaylist == null) {
                database.playlistDao().upsert(playlist)
                playlistsByNormalizedName[playlist.normalizedName] = playlist
            }
            syncEmbyRemoteBinding(
                playlist = playlist,
                sourceId = source.id,
                remotePlaylistId = remotePlaylist.id,
                remoteName = remotePlaylist.name,
            )
        }

        existingBindingsByRemoteId.values
            .filter { it.remotePlaylistId !in remoteIds }
            .forEach { binding ->
                database.playlistRemoteBindingDao().deleteByPlaylistIdAndSourceId(binding.playlistId, binding.sourceId)
                database.playlistTrackDao().deleteByPlaylistIdAndSourceId(binding.playlistId, binding.sourceId)
                cleanupPlaylistIfNecessary(binding.playlistId)
            }
    }

    private suspend fun syncRemoteBinding(
        playlist: PlaylistEntity,
        sourceId: String,
        remotePlaylistId: String,
        remoteName: String,
    ) {
        val resolvedSource = resolveSubsonicCompatibleSource(sourceId)
            ?: error("Subsonic-compatible 来源不可用，无法同步歌单。")
        val remoteEntries = fetchRemotePlaylistEntries(
            resolvedSource = resolvedSource,
            remotePlaylistId = remotePlaylistId,
        )
        val currentRemoteTrackOrder = database.playlistTrackDao().getByPlaylistIdAndSourceId(playlist.id, sourceId)
            .sortedBy { it.remoteOrdinal ?: Int.MAX_VALUE }
            .map { RemotePlaylistTrackSnapshot(trackId = it.trackId, remoteOrdinal = it.remoteOrdinal ?: -1) }
        val nextRemoteTrackOrder = remoteEntries.mapIndexed { index, entry ->
            RemotePlaylistTrackSnapshot(
                trackId = subsonicCompatibleTrackIdFor(sourceId, entry.songId, resolvedSource.sourceType),
                remoteOrdinal = index,
            )
        }
        val tracksChanged = currentRemoteTrackOrder != nextRemoteTrackOrder
        if (tracksChanged) {
            database.playlistTrackDao().deleteByPlaylistIdAndSourceId(playlist.id, sourceId)
            if (remoteEntries.isNotEmpty()) {
                database.playlistTrackDao().upsertAll(
                    remoteEntries.mapIndexed { index, entry ->
                        PlaylistTrackEntity(
                            playlistId = playlist.id,
                            trackId = subsonicCompatibleTrackIdFor(sourceId, entry.songId, resolvedSource.sourceType),
                            sourceId = sourceId,
                            addedAt = now(),
                            localOrdinal = null,
                            remoteOrdinal = index,
                        )
                    },
                )
            }
        }
        database.playlistRemoteBindingDao().upsert(
            PlaylistRemoteBindingEntity(
                playlistId = playlist.id,
                sourceId = sourceId,
                remotePlaylistId = remotePlaylistId,
                remoteName = remoteName,
                lastSyncedAt = now(),
            ),
        )
        if (tracksChanged) {
            touchPlaylist(playlist)
        }
    }

    private suspend fun syncEmbyRemoteBinding(
        playlist: PlaylistEntity,
        sourceId: String,
        remotePlaylistId: String,
        remoteName: String,
    ) {
        val resolvedSource = resolveEmbySource(database, secureCredentialStore, sourceId)
            ?: error("Emby 来源不可用，无法同步歌单。")
        val remoteEntries = fetchEmbyPlaylistEntries(
            httpClient = httpClient,
            source = resolvedSource,
            playlistId = remotePlaylistId,
            logger = logger,
        )
        val currentRemoteTrackOrder = database.playlistTrackDao().getByPlaylistIdAndSourceId(playlist.id, sourceId)
            .sortedBy { it.remoteOrdinal ?: Int.MAX_VALUE }
            .map { RemotePlaylistTrackSnapshot(trackId = it.trackId, remoteOrdinal = it.remoteOrdinal ?: -1) }
        val nextRemoteTrackOrder = remoteEntries.mapIndexed { index, entry ->
            RemotePlaylistTrackSnapshot(
                trackId = embyTrackIdFor(sourceId, entry.itemId),
                remoteOrdinal = index,
            )
        }
        val tracksChanged = currentRemoteTrackOrder != nextRemoteTrackOrder
        if (tracksChanged) {
            database.playlistTrackDao().deleteByPlaylistIdAndSourceId(playlist.id, sourceId)
            if (remoteEntries.isNotEmpty()) {
                database.playlistTrackDao().upsertAll(
                    remoteEntries.mapIndexed { index, entry ->
                        PlaylistTrackEntity(
                            playlistId = playlist.id,
                            trackId = embyTrackIdFor(sourceId, entry.itemId),
                            sourceId = sourceId,
                            addedAt = now(),
                            localOrdinal = null,
                            remoteOrdinal = index,
                        )
                    },
                )
            }
        }
        database.playlistRemoteBindingDao().upsert(
            PlaylistRemoteBindingEntity(
                playlistId = playlist.id,
                sourceId = sourceId,
                remotePlaylistId = remotePlaylistId,
                remoteName = remoteName,
                lastSyncedAt = now(),
            ),
        )
        if (tracksChanged) {
            touchPlaylist(playlist)
        }
    }

    private suspend fun cleanupRemovedRemoteSources(activeSourceIds: Set<String>) {
        database.playlistRemoteBindingDao().getAll()
            .filter { it.sourceId !in activeSourceIds }
            .forEach { binding ->
                database.playlistRemoteBindingDao().deleteByPlaylistIdAndSourceId(binding.playlistId, binding.sourceId)
                database.playlistTrackDao().deleteByPlaylistIdAndSourceId(binding.playlistId, binding.sourceId)
                cleanupPlaylistIfNecessary(binding.playlistId)
            }
    }

    private suspend fun cleanupPlaylistIfNecessary(playlistId: String) {
        val playlist = database.playlistDao().getById(playlistId) ?: return
        val hasTracks = database.playlistTrackDao().getByPlaylistId(playlistId).isNotEmpty()
        val hasBindings = database.playlistRemoteBindingDao().getAll().any { it.playlistId == playlistId }
        if (!playlist.createdLocally && !hasTracks && !hasBindings) {
            database.playlistDao().deleteById(playlistId)
        }
    }

    private suspend fun deleteLocalPlaylist(playlistId: String) {
        database.playlistRemoteBindingDao().deleteByPlaylistId(playlistId)
        database.playlistTrackDao().deleteByPlaylistId(playlistId)
        database.playlistDao().deleteById(playlistId)
    }

    private suspend fun touchPlaylist(playlist: PlaylistEntity) {
        database.playlistDao().upsert(playlist.copy(updatedAt = now()))
    }

    private suspend fun fetchSourcePlaylists(
        resolvedSource: NavidromeResolvedSource,
    ): List<NavidromePlaylistSummaryPayload> {
        val payload = requestNavidromeJson(
            httpClient = httpClient,
            source = resolvedSource,
            endpoint = "getPlaylists",
            logger = logger,
        )
        return payload["playlists"].asJsonObjectOrNull()
            ?.get("playlist")
            .asJsonObjectList()
            .mapNotNull { playlist ->
                val id = playlist.string("id") ?: return@mapNotNull null
                val name = playlist.string("name")?.trim().orEmpty().ifBlank { "未命名歌单" }
                NavidromePlaylistSummaryPayload(
                    id = id,
                    name = name,
                )
            }
    }

    private suspend fun fetchRemotePlaylistEntries(
        resolvedSource: NavidromeResolvedSource,
        remotePlaylistId: String,
    ): List<NavidromePlaylistEntryPayload> {
        val payload = requestNavidromeJson(
            httpClient = httpClient,
            source = resolvedSource,
            endpoint = "getPlaylist",
            parameters = mapOf("id" to remotePlaylistId),
            logger = logger,
            logContext = "playlistId=$remotePlaylistId",
        )
        return payload["playlist"].asJsonObjectOrNull()
            ?.get("entry")
            .asJsonObjectList()
            .mapNotNull { entry ->
                val songId = entry.string("id") ?: return@mapNotNull null
                NavidromePlaylistEntryPayload(songId = songId)
            }
    }

    private suspend fun resolveSubsonicCompatibleSource(sourceId: String): NavidromeResolvedSource? {
        val source = database.importSourceDao().getById(sourceId)
            ?.takeIf { it.subsonicCompatibleSourceType() != null && it.enabled }
            ?: return null
        return source.toSubsonicCompatibleResolvedSource()
    }

    private suspend fun ImportSourceEntity.toSubsonicCompatibleResolvedSource(): NavidromeResolvedSource? {
        val sourceType = subsonicCompatibleSourceType() ?: return null
        val authMode = authMode.toSubsonicAuthMode()
        val username = username?.trim().orEmpty()
        val credential = credentialKey?.let { secureCredentialStore.get(it) }.orEmpty()
        if (authMode == SubsonicAuthMode.PASSWORD && (username.isBlank() || credential.isBlank())) return null
        if (authMode == SubsonicAuthMode.API_KEY && credential.isBlank()) return null
        return NavidromeResolvedSource(
            baseUrl = normalizeSubsonicBaseUrl(rootReference),
            username = username,
            password = credential,
            authMode = authMode,
            sourceType = sourceType,
        )
    }

    private fun ImportSourceEntity.subsonicCompatibleSourceType(): ImportSourceType? {
        val sourceType = runCatching { ImportSourceType.valueOf(type) }.getOrNull() ?: return null
        return sourceType.takeIf(::isSubsonicCompatibleSourceType)
    }

    private fun ImportSourceEntity.isEmbySource(): Boolean {
        return type == ImportSourceType.EMBY.name
    }
}

private fun PlaylistEntity.toSummary(
    memberTrackIds: Set<String> = emptySet(),
    artworkLocator: String? = null,
): PlaylistSummary {
    return PlaylistSummary(
        id = id,
        name = name,
        kind = PlaylistKind.USER,
        trackCount = memberTrackIds.size,
        updatedAt = updatedAt,
        memberTrackIds = memberTrackIds,
        artworkLocator = artworkLocator,
    )
}

private fun List<PlaylistTrackEntity>.latestPlaylistArtworkLocator(trackById: Map<String, Track>): String? {
    val latestTrack = maxWithOrNull(
        compareBy<PlaylistTrackEntity> { it.addedAt }
            .thenBy { it.localOrdinal ?: it.remoteOrdinal ?: -1 },
    ) ?: return null
    return trackById[latestTrack.trackId]?.artworkLocator?.takeIf { it.isNotBlank() }
}

private fun PlaylistEntity.toDetail(
    tracks: List<PlaylistTrackEntity>,
    trackById: Map<String, Track>,
    sourceLabelById: Map<String, String>,
): PlaylistDetail {
    val orderedTracks = tracks
        .filter { it.playlistId == id }
        .sortedWith(
            compareBy<PlaylistTrackEntity> { if (it.localOrdinal != null) 0 else 1 }
                .thenBy { it.localOrdinal ?: Int.MAX_VALUE }
                .thenBy { if (it.localOrdinal != null) "" else sourceLabelById[it.sourceId]?.lowercase().orEmpty() }
                .thenBy { it.remoteOrdinal ?: Int.MAX_VALUE }
                .thenBy { it.trackId },
        )
        .mapNotNull { row ->
            trackById[row.trackId]?.let { track ->
                PlaylistTrackEntry(
                    track = track,
                    sourceLabel = sourceLabelById[row.sourceId] ?: row.sourceId,
                )
            }
        }
    return PlaylistDetail(
        id = id,
        name = name,
        kind = PlaylistKind.USER,
        updatedAt = updatedAt,
        tracks = orderedTracks,
    )
}

private fun normalizePlaylistName(name: String): String = name.trim().lowercase()

private data class NavidromePlaylistSummaryPayload(
    val id: String,
    val name: String,
)

private data class NavidromePlaylistEntryPayload(
    val songId: String,
)

private data class RemotePlaylistTrackSnapshot(
    val trackId: String,
    val remoteOrdinal: Int,
)

private fun JsonElement?.asJsonObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asJsonObjectList(): List<JsonObject> {
    return when (val element = this) {
        is JsonArray -> element.mapNotNull { it as? JsonObject }
        is JsonObject -> listOf(element)
        else -> emptyList()
    }
}

private fun JsonObject.string(key: String): String? {
    return (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}
