package com.museum.reader.data

import androidx.room.withTransaction
import com.museum.reader.data.db.*
import com.museum.reader.domain.TextSegmenter
import com.museum.reader.model.*
import java.io.File
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

enum class BackupConflictPolicy(val label: String) {
    KEEP_LOCAL("保留本机内容"), COPY("另存备份副本"), REPLACE("覆盖并保留恢复点"),
}

data class BackupPreview(
    val newDocuments: Int,
    val sameDocuments: Int,
    val conflictTitles: List<String>,
    val localFingerprint: String,
    val audioCount: Int = 0,
    val warnings: List<String> = emptyList(),
    val audioConflictTitles: List<String> = emptyList(),
    val metadataConflictTitles: List<String> = emptyList(),
    val routeConflictTitles: List<String> = emptyList(),
) {
    val hasConflicts: Boolean get() = conflictTitles.isNotEmpty() || routeConflictTitles.isNotEmpty()
}

data class BackupExportData(val manifest: JSONObject, val audio: List<SentenceAudio>)

internal data class BackupDocumentData(
    val document: DocumentEntity,
    val sentences: List<SentenceEntity>,
    val bookmarks: List<BookmarkEntity>,
    val versions: List<DocumentVersionEntity>,
    val draft: EditorDraftEntity?,
)

internal data class BackupGraph(
    val root: JSONObject,
    val documents: List<BackupDocumentData>,
    val settings: ReaderSettings?,
) {
    val fingerprint: String = DocumentRepository.textHash(root.toString())
}

/** Database-only portion of restore. Files are staged by BackupManager before this transaction. */
internal class BackupStore(private val database: ReaderDatabase) {
    private val dao = database.readerDao()
    private val metadata = BackupMetadata(dao)

    suspend fun export(settings: ReaderSettings): BackupExportData = database.withTransaction {
        require(!dao.hasOversizedNames()) { "备份中有名称超过 1000 字，请缩短名称后再导出；原资料保留" }
        require(dao.backupTextLowerBound() <= 30L * 1024 * 1024) {
            "正文与历史总量超过备份清单 30 MB 限制，本次未导出，本机原资料保留"
        }
        val documents = dao.documentsIncludingTrash()
        val root = JSONObject().put("format", "museum-reader-backup").put("version", 2)
            .put("createdAt", System.currentTimeMillis()).put("settings", settings.toBackupJson())
        root.put("documents", JSONArray().also { array ->
            for (document in documents) {
                val item = JSONObject()
                    .put("id", document.id).put("title", document.title).put("source", document.source)
                    .put("text", document.text).put("createdAt", document.createdAt).put("updatedAt", document.updatedAt)
                    .put("lastSentence", document.lastSentence).put("isPinned", document.isPinned)
                    .putNullable("speechRate", document.speechRate).putNullable("sentencePauseMs", document.sentencePauseMs)
                    .putNullable("paragraphPauseMs", document.paragraphPauseMs).putNullable("trashedAt", document.trashedAt)
                item.put("sentences", JSONArray().also { sentences ->
                    dao.allSentences(document.id).forEach { sentence ->
                        sentences.put(JSONObject().put("id", sentence.id).put("ordinal", sentence.ordinal)
                            .put("paragraphIndex", sentence.paragraphIndex).put("text", sentence.text)
                            .put("textHash", sentence.textHash).putNullable("retiredAt", sentence.retiredAt))
                    }
                })
                item.put("bookmarks", JSONArray().also { bookmarks ->
                    dao.bookmarks(document.id).forEach {
                        bookmarks.put(JSONObject().put("id", it.id).put("sentenceId", it.sentenceId).put("createdAt", it.createdAt))
                    }
                })
                item.put("versions", JSONArray().also { versions ->
                    dao.versions(document.id).forEach {
                        versions.put(JSONObject().put("id", it.id).put("title", it.title).put("text", it.text)
                            .put("createdAt", it.createdAt).putNullable("sentenceSnapshot", it.sentenceSnapshot))
                    }
                })
                dao.draft(document.id)?.let { draft ->
                    item.put("draft", JSONObject().put("title", draft.title).put("text", draft.text)
                        .put("selectionStart", draft.selectionStart).put("selectionEnd", draft.selectionEnd)
                        .put("updatedAt", draft.updatedAt))
                }
                array.put(item)
            }
        })
        root.put("playlists", JSONArray().also { array ->
            dao.playlists().forEach {
                array.put(JSONObject().put("id", it.id).put("title", it.title).put("createdAt", it.createdAt)
                    .put("updatedAt", it.updatedAt).putNullable("lastDocumentId", it.lastDocumentId)
                    .put("documentIds", JSONArray(dao.playlistItems(it.id).map { entry -> entry.documentId })))
            }
        })
        parse(root.toString()) // Do not export a manifest that this version would reject on restore.
        BackupExportData(root, dao.allAudioAssets().map { it.toAudioModel() })
    }

    fun parse(payload: String): BackupGraph {
        val root = runCatching { JSONObject(payload) }.getOrElse { error("备份清单损坏") }
        require(root.optString("format") == "museum-reader-backup") { "这不是随身听的备份文件" }
        require(root.optInt("version") in 1..2) { "备份版本不支持，请检查应用版本" }
        val array = root.optJSONArray("documents") ?: error("备份中没有文稿清单")
        require(array.length() <= 10_000) { "备份文稿数量超限" }
        val documentIds = HashSet<String>()
        val sentenceIds = HashSet<String>()
        val versionIds = HashSet<String>()
        val documents = (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            val id = item.requiredId("id")
            require(documentIds.add(id)) { "备份有重复文稿编号" }
            val text = item.getString("text")
            require(text.isNotBlank() && text.length <= 1_000_000) { "备份文稿为空或超过 100 万字" }
            val segmented = TextSegmenter.segment(text)
            require(segmented.sentences.isNotEmpty()) { "备份文稿没有可朗读内容" }
            val document = DocumentEntity(
                id, checkedName(item.optString("title", "未命名文稿"), "备份文稿名称"),
                item.optString("source", DocumentSource.TXT.name), text,
                item.optLong("createdAt", System.currentTimeMillis()), item.optLong("updatedAt", System.currentTimeMillis()),
                item.optInt("lastSentence", 0).coerceIn(0, segmented.sentences.lastIndex), item.optBoolean("isPinned"),
                item.nullableDouble("speechRate")?.also { require(it.isFinite() && it in 0.55..1.55) { "备份语速无效" } }?.toFloat(),
                item.nullableInt("sentencePauseMs")?.also { require(it in 0..3000) { "备份句间停顿无效" } },
                item.nullableInt("paragraphPauseMs")?.also { require(it in 0..5000) { "备份段间停顿无效" } },
                item.nullableLong("trashedAt"),
            )
            val sentenceArray = item.optJSONArray("sentences") ?: JSONArray()
            val sentences = if (sentenceArray.length() == 0) {
                segmented.sentences.map { SentenceEntity(UUID.randomUUID().toString(), id, it.index, it.paragraphIndex,
                    it.text, DocumentRepository.textHash(it.text)) }
            } else (0 until sentenceArray.length()).map { j ->
                val entry = sentenceArray.getJSONObject(j)
                val sentenceText = entry.getString("text")
                require(sentenceText.isNotBlank() && sentenceText.length <= 1_000_000) { "备份句子内容无效" }
                val hash = DocumentRepository.textHash(sentenceText)
                require(entry.optString("textHash", hash) == hash) { "备份句子校验不一致" }
                SentenceEntity(entry.requiredId("id"), id, entry.optInt("ordinal", j),
                    entry.optInt("paragraphIndex", 0), sentenceText, hash, entry.nullableLong("retiredAt"))
            }
            sentences.forEach { require(sentenceIds.add(it.id)) { "备份中有重复句子编号" } }
            val active = sentences.filter { it.retiredAt == null }.sortedBy { it.ordinal }
            val canonicalMatches = active.size == segmented.sentences.size && active.withIndex().all {
                it.value.ordinal == it.index && it.value.text == segmented.sentences[it.index].text &&
                    it.value.paragraphIndex == segmented.sentences[it.index].paragraphIndex
            }
            val legacyMatches = active.withIndex().all { it.value.ordinal == it.index } &&
                active.groupBy { it.paragraphIndex }.values.joinToString("\n\n") { group ->
                    group.joinToString("") { it.text }
                } == text
            require(canonicalMatches || legacyMatches) { "备份正文和句子位置不一致" }
            val known = sentences.mapTo(hashSetOf()) { it.id }
            val bookmarkArray = item.optJSONArray("bookmarks") ?: JSONArray()
            val bookmarks = (0 until bookmarkArray.length()).map { j ->
                val entry = bookmarkArray.getJSONObject(j)
                val sentenceId = entry.requiredId("sentenceId")
                require(sentenceId in known) { "重点句引用不存在的句子" }
                BookmarkEntity(entry.requiredId("id"), id, sentenceId, entry.optLong("createdAt", 0L))
            }.distinctBy { it.sentenceId }
            val versionArray = item.optJSONArray("versions") ?: JSONArray()
            require(versionArray.length() <= 100) { "版本历史数量异常" }
            val versions = (0 until versionArray.length()).map { j ->
                val entry = versionArray.getJSONObject(j)
                val versionId = entry.requiredId("id")
                require(versionIds.add(versionId)) { "备份有重复的历史版本编号，请检查备份后重试" }
                val body = entry.getString("text")
                require(body.length <= 1_000_000) { "历史文稿超过上限" }
                val snapshot = entry.nullableString("sentenceSnapshot")?.also { payload ->
                    val references = historySentences(payload)
                    val byId = sentences.associateBy { it.id }
                    require(references.all { reference -> byId[reference.id]?.text == reference.text } &&
                        historyMatchesBody(references, body)) { "备份历史句子引用、顺序或正文不一致" }
                }
                DocumentVersionEntity(versionId, id,
                    checkedName(entry.optString("title", document.title), "备份历史文稿名称"), body,
                    entry.optLong("createdAt", 0L), snapshot)
            }
            val draft = item.optJSONObject("draft")?.let { entry ->
                val body = entry.getString("text")
                require(body.length <= 1_000_000) { "草稿超过上限" }
                EditorDraftEntity(id, checkedName(entry.optString("title", document.title), "备份草稿名称"), body,
                    entry.optInt("selectionStart").coerceIn(0, body.length),
                    entry.optInt("selectionEnd").coerceIn(0, body.length), entry.optLong("updatedAt", 0L))
            }
            BackupDocumentData(document, sentences, bookmarks, versions, draft)
        }
        val playlists = root.optJSONArray("playlists") ?: JSONArray()
        val playlistIds = HashSet<String>()
        require(playlists.length() <= 10_000) { "路线数量超限" }
        for (i in 0 until playlists.length()) {
            val route = playlists.getJSONObject(i)
            checkedName(route.optString("title", "讲解路线"), "备份路线名称")
            require(playlistIds.add(route.requiredId("id"))) { "备份路线编号重复" }
            val stations = route.optJSONArray("documentIds") ?: JSONArray()
            require(stations.length() <= 10_000) { "路线条目超限" }
            val stationIds = HashSet<String>()
            for (station in 0 until stations.length()) {
                val id = stations.getString(station)
                require(id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,179}")) && stationIds.add(id)) {
                    "备份路线含无效或重复站点，请检查备份后重试"
                }
            }
        }
        return BackupGraph(root, documents, root.optJSONObject("settings")?.let(::settingsFromBackup))
    }

    suspend fun preview(graph: BackupGraph, audio: List<SentenceAudio> = emptyList()): BackupPreview = database.withTransaction {
        val local = dao.documentsIncludingTrash().associateBy { it.id }
        val localAudio = dao.allAudioAssets()
        val audioConflicts = audioConflicts(graph, audio, localAudio)
        val metadataConflicts = metadata.conflicts(graph, local)
        val added = graph.documents.count { it.document.id !in local }
        val same = graph.documents.count { entry -> entry.document.id !in audioConflicts && entry.document.id !in metadataConflicts &&
            local[entry.document.id]?.let { sameContent(it, entry.document) } == true }
        val conflicts = graph.documents.filter { entry ->
            local[entry.document.id]?.let { !sameContent(it, entry.document) || entry.document.id in audioConflicts ||
                entry.document.id in metadataConflicts } == true
        }.map { it.document.title }
        BackupPreview(added, same, conflicts, metadata.fingerprint(local.values, localAudio),
            warnings = routeWarnings(graph, local.keys),
            audioConflictTitles = graph.documents.filter { it.document.id in audioConflicts }.map { it.document.title },
            metadataConflictTitles = graph.documents.filter { it.document.id in metadataConflicts }.map { it.document.title },
            routeConflictTitles = metadata.routeConflicts(graph, local.keys))
    }

    suspend fun restore(
        graph: BackupGraph,
        policy: BackupConflictPolicy,
        audio: List<SentenceAudio>,
        operationId: String,
        expectedFingerprint: String? = null,
        restoreSettings: Boolean = true,
        acceptRouteWarnings: Boolean = false,
        validateSettings: (() -> Unit)? = null,
    ): BackupImportResult = database.withTransaction {
        if (dao.hasRestoreReceipt(operationId)) return@withTransaction BackupImportResult(0, null)
        val local = dao.documentsIncludingTrash().associateBy { it.id }.toMutableMap()
        val routeWarnings = routeWarnings(graph, local.keys)
        require(routeWarnings.isEmpty() || acceptRouteWarnings) { "路线包含找不到的文稿，请先预览并确认缺失站点" }
        val localAudio = dao.allAudioAssets()
        val audioConflicts = audioConflicts(graph, audio, localAudio)
        val metadataConflicts = metadata.conflicts(graph, local)
        require(expectedFingerprint == null || expectedFingerprint == metadata.fingerprint(local.values, localAudio)) {
            "预览后本机资料已变化，请重新预览恢复"
        }
        validateSettings?.invoke()
        val owners = dao.sentenceOwners().associate { it.id to it.documentId }.toMutableMap()
        val documentMap = HashMap<String, String>()
        val sentenceMap = HashMap<String, String>()
        val skipped = HashSet<String>()
        var imported = 0
        for (backup in graph.documents) {
            val incoming = backup.document
            val old = local[incoming.id]
            val conflict = old != null && (!sameContent(old, incoming) || incoming.id in audioConflicts || incoming.id in metadataConflicts)
            if (conflict && policy == BackupConflictPolicy.KEEP_LOCAL) {
                documentMap[incoming.id] = incoming.id
                skipped += incoming.id
                continue
            }
            val copied = conflict && policy == BackupConflictPolicy.COPY
            val targetId = if (copied) copyTarget(graph, backup, audio, local, localAudio) else incoming.id
            documentMap[incoming.id] = targetId
            val existing = local[targetId]
            val target = incoming.copy(id = targetId, title = if (copied) copyTitle(incoming.title) else incoming.title)
            val keepExisting = existing != null && sameContent(existing, target)
            val previous = dao.allSentences(targetId)
            val previousVersions = dao.versions(targetId)
            val sameRelated = existing != null && metadata.sameRelated(backup, targetId, previous)
            val replaceRelated = !copied && conflict && policy == BackupConflictPolicy.REPLACE
            val known = previous.associateBy { it.id }
            val activeByOrdinal = previous.filter { it.retiredAt == null }.associateBy { it.ordinal }
            for (sentence in backup.sentences) {
                val identical = known[sentence.id]?.takeIf { it.textHash == sentence.textHash }
                val ordinalMatch = activeByOrdinal[sentence.ordinal]?.takeIf {
                    keepExisting && sentence.retiredAt == null && it.textHash == sentence.textHash
                }
                val mapped = when {
                    identical != null -> identical.id
                    ordinalMatch != null -> ordinalMatch.id
                    sentence.id !in owners && !copied -> sentence.id
                    else -> stableId("sentence:" + graph.fingerprint + ":" + targetId + ":" + sentence.id)
                }
                require(owners[mapped] == null || owners[mapped] == targetId) { "句子编号发生冲突" }
                sentenceMap[sentence.id] = mapped
                owners[mapped] = targetId
            }
            if (!keepExisting) {
                if (existing != null) dao.putVersion(DocumentVersionEntity(
                    UUID.randomUUID().toString(), targetId, existing.title, existing.text, System.currentTimeMillis(),
                    JSONArray().also { array -> previous.filter { it.retiredAt == null }.forEach {
                        array.put(JSONObject().put("id", it.id).put("text", it.text))
                    } }.toString(),
                ))
                dao.putDocument(target)
                imported++
            }
            val incomingSentences = backup.sentences.map { it.copy(id = sentenceMap.getValue(it.id), documentId = targetId) }
            if (!keepExisting) {
                val activeIds = incomingSentences.filter { it.retiredAt == null }.mapTo(hashSetOf()) { it.id }
                previous.filter { it.retiredAt == null && it.id !in activeIds }.forEach {
                    dao.retireSentence(it.id, System.currentTimeMillis())
                }
                dao.putSentences(incomingSentences)
            } else {
                dao.putSentences(incomingSentences.filter { it.id !in known })
            }
            if (replaceRelated) dao.clearBookmarks(targetId)
            if (!sameRelated || replaceRelated) {
                val existingBookmarks = dao.bookmarks(targetId).mapTo(hashSetOf()) { it.sentenceId }
                backup.bookmarks.forEach {
                    val sentenceId = sentenceMap.getValue(it.sentenceId)
                    if (existingBookmarks.add(sentenceId)) dao.putBookmark(it.copy(
                        id = metadata.bookmarkId(targetId, sentenceId), documentId = targetId, sentenceId = sentenceId,
                    ))
                }
            }
            if (!sameRelated || replaceRelated) {
                backup.versions.forEach { version ->
                    dao.putVersion(version.copy(id = metadata.versionId(targetId, version.id, copied, previousVersions),
                        documentId = targetId, sentenceSnapshot = metadata.mappedSnapshot(version.sentenceSnapshot, sentenceMap)))
                }
                dao.trimVersions(targetId, 5)
            }
            if (replaceRelated && backup.draft == null) dao.deleteDraft(targetId)
            backup.draft?.let {
                if (replaceRelated || !keepExisting || dao.draft(targetId) == null) dao.putDraft(it.copy(documentId = targetId))
            }
            local[targetId] = existing?.takeIf { keepExisting } ?: target
        }
        val currentAudio = dao.allAudioAssets().associateBy { it.id }.toMutableMap()
        var audioCount = 0
        for (asset in audio) {
            if (asset.documentId in skipped) continue
            val targetDocument = documentMap[asset.documentId] ?: error("音频引用不存在的文稿")
            val targetSentence = sentenceMap[asset.sentenceId] ?: error("音频引用不存在的句子")
            val copied = targetDocument != asset.documentId
            fun sameIdentity(existing: AudioAssetEntity) = existing.documentId == targetDocument &&
                existing.sentenceId == targetSentence && existing.textHash == asset.textHash && existing.kind == asset.kind.name &&
                existing.provider == asset.provider && existing.voiceId == asset.voiceId
            val remap = copied || currentAudio[asset.id]?.let { !sameIdentity(it) } == true
            val id = if (!remap) asset.id else (0..100_000).firstNotNullOfOrNull { number ->
                val suffix = if (number == 0) "" else ":collision:$number"
                val candidate = stableId("audio:" + graph.fingerprint + ":" + targetDocument + ":" + asset.id + suffix)
                candidate.takeIf { currentAudio[it]?.let(::sameIdentity) != false }
            } ?: error("音频编号无法安全映射，请重新校验备份")
            val existing = currentAudio[id]
            if (existing != null && existing.textHash == asset.textHash && existing.sentenceId == targetSentence &&
                (existing.archivedAt == null) == (asset.archivedAt == null) && File(existing.path).isFile &&
                AudioFiles.sha256(File(existing.path)) == AudioFiles.sha256(File(asset.path))) {
                // An explicit replacement must select the requested output even if its bytes
                // are already present beside a newer recording from an older provider format.
                if (policy == BackupConflictPolicy.REPLACE && asset.archivedAt == null) {
                    if (asset.kind == AudioKind.RECORDING) dao.archivePreviousRecording(targetSentence, id, System.currentTimeMillis())
                    else dao.archivePreviousAudio(targetSentence, asset.kind.name, asset.provider, asset.voiceId, id, System.currentTimeMillis())
                }
                continue
            }
            if (asset.archivedAt == null) {
                if (asset.kind == AudioKind.RECORDING) dao.archivePreviousRecording(targetSentence, id, System.currentTimeMillis())
                else dao.archivePreviousAudio(targetSentence, asset.kind.name, asset.provider, asset.voiceId, id, System.currentTimeMillis())
            }
            val restored = AudioAssetEntity(id, targetDocument, targetSentence, asset.kind.name, asset.provider,
                asset.voiceId, asset.path, asset.textHash, asset.durationMs, System.currentTimeMillis(), asset.archivedAt)
            dao.putAudio(restored)
            currentAudio[id] = restored
            audioCount++
        }
        val playlistArray = graph.root.optJSONArray("playlists") ?: JSONArray()
        val localRoutes = dao.fullPlaylists().associateBy { it.id }
        for (i in 0 until playlistArray.length()) {
            val item = playlistArray.getJSONObject(i)
            val sourceId = item.requiredId("id")
            val ids = item.optJSONArray("documentIds") ?: JSONArray()
            val mappedIds = (0 until ids.length()).mapNotNull {
                val id = ids.getString(it)
                (documentMap[id] ?: id).takeIf { mapped -> mapped in local }
            }.distinct()
            val old = localRoutes[sourceId]
            if (old != null && policy == BackupConflictPolicy.KEEP_LOCAL) continue
            val title = item.optString("title", "讲解路线")
            val lastDocumentId = item.nullableString("lastDocumentId")?.let { documentMap[it] ?: it }?.takeIf { it in mappedIds }
            fun matches(route: PlaylistEntity, ids: List<String>) = route.title == title &&
                route.lastDocumentId == lastDocumentId && ids == mappedIds
            if (old != null && matches(old, dao.playlistItems(sourceId).map { it.documentId })) continue
            var targetId = sourceId
            if (old != null && policy == BackupConflictPolicy.COPY) {
                var number = 0
                while (true) {
                    targetId = stableId("route:" + graph.fingerprint + ":" + sourceId + if (number == 0) "" else ":copy:$number")
                    val candidate = localRoutes[targetId] ?: break
                    if (matches(candidate, dao.playlistItems(targetId).map { it.documentId })) break
                    number++
                }
                if (targetId in localRoutes) continue
            }
            dao.putPlaylist(PlaylistEntity(targetId, title,
                item.optLong("createdAt", 0L), item.optLong("updatedAt", 0L),
                lastDocumentId))
            dao.clearPlaylist(targetId)
            dao.putPlaylistItems(mappedIds.mapIndexed { index, documentId -> PlaylistItemEntity(targetId, documentId, index) })
        }
        validateSettings?.invoke()
        dao.putRestoreReceipt(RestoreReceiptEntity(operationId, System.currentTimeMillis()))
        BackupImportResult(imported, graph.settings.takeIf { restoreSettings }, audioCount, warnings = routeWarnings)
    }

    private fun routeWarnings(graph: BackupGraph, localIds: Set<String>): List<String> {
        val available = localIds + graph.documents.map { it.document.id }
        val routes = graph.root.optJSONArray("playlists") ?: return emptyList()
        return buildList {
            for (i in 0 until routes.length()) {
                val route = routes.getJSONObject(i)
                val ids = route.optJSONArray("documentIds") ?: continue
                for (station in 0 until ids.length()) {
                    val id = ids.getString(station)
                    if (id !in available) add("路线《${route.optString("title", "讲解路线")}》第${station + 1}站的文稿（$id）不存在；恢复该路线时将跳过此站。")
                }
            }
        }
    }

    private fun sameContent(a: DocumentEntity, b: DocumentEntity) =
        a.title == b.title && a.text == b.text && a.isPinned == b.isPinned &&
            a.speechRate == b.speechRate && a.sentencePauseMs == b.sentencePauseMs &&
            a.paragraphPauseMs == b.paragraphPauseMs &&
            (a.trashedAt == null) == (b.trashedAt == null)

    private fun copyTitle(title: String): String {
        val suffix = "（备份副本）"
        val end = minOf(title.length, MAX_NAME_CHARACTERS - suffix.length)
        val safeEnd = if (end > 0 && end < title.length &&
            title[end - 1].isHighSurrogate() && title[end].isLowSurrogate()) end - 1 else end
        return title.substring(0, safeEnd) + suffix
    }

    private suspend fun audioConflicts(graph: BackupGraph, incoming: List<SentenceAudio>, local: List<AudioAssetEntity>): Set<String> {
        val conflicts = hashSetOf<String>()
        val byId = local.associateBy { it.id }
        val byDocument = local.groupBy { it.documentId }
        val localSentences = HashMap<String, List<SentenceEntity>>()
        val sourceSentences = graph.documents.flatMap { it.sentences }.associateBy { it.id }
        val digests = HashMap<String, String>()
        fun digest(path: String) = digests.getOrPut(path) { AudioFiles.sha256(File(path)) }
        fun usable(asset: AudioAssetEntity) = File(asset.path).isFile && AudioFiles.inspect(File(asset.path)) != null
        for (asset in incoming) {
            val sameId = byId[asset.id]
            if (sameId != null && sameId.documentId == asset.documentId && sameId.textHash == asset.textHash &&
                (sameId.archivedAt == null) != (asset.archivedAt == null) && usable(sameId)) {
                conflicts += asset.documentId
            }
            if (asset.archivedAt != null) continue
            val source = sourceSentences[asset.sentenceId] ?: continue
            val sentences = localSentences.getOrPut(asset.documentId) { dao.allSentences(asset.documentId) }
            val target = sentences.find { it.id == source.id && it.textHash == source.textHash }
                ?: sentences.find { it.retiredAt == null && it.ordinal == source.ordinal && it.textHash == source.textHash }
                ?: continue
            val candidates = byDocument[asset.documentId].orEmpty().filter {
                it.sentenceId == target.id && it.archivedAt == null && it.textHash == asset.textHash &&
                    it.kind == asset.kind.name && (asset.kind == AudioKind.RECORDING ||
                    (it.provider == asset.provider && it.voiceId == asset.voiceId))
            }
            if (candidates.any { usable(it) && digest(it.path) != digest(asset.path) }) conflicts += asset.documentId
        }
        return conflicts
    }

    private suspend fun copyTarget(graph: BackupGraph, backup: BackupDocumentData, audio: List<SentenceAudio>,
        local: Map<String, DocumentEntity>, localAudio: List<AudioAssetEntity>): String {
        for (number in 0..local.size) {
            val key = "document:" + graph.fingerprint + ":" + backup.document.id + if (number == 0) "" else ":copy:$number"
            val id = stableId(key)
            val existing = local[id] ?: return id
            if (sameContent(existing, backup.document.copy(id = id, title = copyTitle(backup.document.title))) &&
                unchangedCopy(graph, backup, audio, id, localAudio)) return id
        }
        error("备份副本位置不足，请重新选择备份")
    }

    private suspend fun unchangedCopy(graph: BackupGraph, backup: BackupDocumentData, audio: List<SentenceAudio>,
        id: String, localAudio: List<AudioAssetEntity>): Boolean {
        val sentenceMap = backup.sentences.associate { it.id to stableId("sentence:" + graph.fingerprint + ":" + id + ":" + it.id) }
        val expectedSentences = backup.sentences.map { it.copy(id = sentenceMap.getValue(it.id), documentId = id) }.toSet()
        if (dao.allSentences(id).toSet() != expectedSentences) return false
        if (!metadata.sameRelated(backup, id, dao.allSentences(id))) return false
        val expectedAudio = audio.filter { it.documentId == backup.document.id }
        val actualAudio = localAudio.filter { it.documentId == id }.toMutableList()
        if (actualAudio.size != expectedAudio.size) return false
        return expectedAudio.all { expected ->
            val actual = actualAudio.find { it.sentenceId == sentenceMap[expected.sentenceId] && it.kind == expected.kind.name &&
                it.provider == expected.provider && it.voiceId == expected.voiceId && it.textHash == expected.textHash &&
                it.archivedAt == expected.archivedAt && File(it.path).isFile &&
                AudioFiles.sha256(File(it.path)) == AudioFiles.sha256(File(expected.path)) }
            actual != null && actualAudio.remove(actual)
        }
    }
}

internal fun AudioAssetEntity.toAudioModel() = SentenceAudio(
    id, documentId, sentenceId, AudioKind.valueOf(kind), provider, voiceId, path, textHash, durationMs, archivedAt,
)
internal fun stableId(value: String) = UUID.nameUUIDFromBytes(value.toByteArray(Charsets.UTF_8)).toString()
internal fun JSONObject.putNullable(key: String, value: Any?): JSONObject = put(key, value ?: JSONObject.NULL)
internal fun JSONObject.nullableString(key: String): String? =
    if (!has(key) || isNull(key)) null else getString(key)
internal fun JSONObject.nullableLong(key: String): Long? =
    if (!has(key) || isNull(key)) null else getLong(key).also { require(it >= 0) { "备份时间值无效" } }
internal fun JSONObject.nullableInt(key: String): Int? = if (!has(key) || isNull(key)) null else getInt(key)
internal fun JSONObject.nullableDouble(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
internal fun JSONObject.requiredId(key: String) = getString(key).also {
    require(it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,179}"))) { "备份编号无效" }
}
internal fun ReaderSettings.toBackupJson() = JSONObject().put("speechRate", speechRate.toDouble())
    .put("pitch", pitch.toDouble()).put("fontSizeSp", fontSizeSp.toDouble()).putNullable("voiceName", voiceName)
    .put("sentencePauseMs", sentencePauseMs).put("paragraphPauseMs", paragraphPauseMs)
internal fun settingsFromBackup(item: JSONObject): ReaderSettings {
    val settings = ReaderSettings(item.optDouble("speechRate", 0.95).toFloat(),
        item.optDouble("pitch", 1.0).toFloat(), item.optDouble("fontSizeSp", 20.0).toFloat(),
        item.nullableString("voiceName"), item.optInt("sentencePauseMs", 300), item.optInt("paragraphPauseMs", 900))
    require(settings.speechRate.isFinite() && settings.speechRate in 0.55f..1.55f &&
        settings.pitch.isFinite() && settings.pitch in 0.7f..1.3f &&
        settings.fontSizeSp.isFinite() && settings.fontSizeSp in 17f..28f &&
        settings.sentencePauseMs in 0..3000 && settings.paragraphPauseMs in 0..5000) { "备份朗读设置无效" }
    return settings
}
