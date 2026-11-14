package com.museum.reader.ui

import com.museum.reader.data.*
import com.museum.reader.data.db.EditorDraftEntity
import com.museum.reader.model.ReaderDocument
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class EditorUiState(
    val document: ReaderDocument? = null,
    val title: String = "",
    val text: String = "",
    val revision: Long = 0,
    val persistedRevision: Long = -1,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val scrollY: Int = 0,
    val installRevision: Long = 0,
    val pendingDraft: EditorDraftEntity? = null,
    val versions: List<DocumentVersion> = emptyList(),
    val saving: Boolean = false,
    val choosingDraft: Boolean = false,
    val error: String? = null,
    val savedInputTitle: String = "",
    val savedInputText: String = "",
    val dirty: Boolean = false,
    val retainedTitle: String? = null,
    val renamingVersion: Boolean = false,
    val versionNameError: String? = null,
) {
    val draftStatus: String get() = when {
        choosingDraft -> "正在放弃未提交草稿…"
        renamingVersion -> "正在修正历史版本名称…"
        saving -> "正在保存正式文稿…"
        error != null -> error
        !dirty -> if (retainedTitle != null) "旧名称过长，未改名时保留原名" else "正式文稿已保存"
        persistedRevision == revision -> if (retainedTitle != null) "草稿已保存，旧名称原值保留" else "草稿已保存，尚未提交正式文稿"
        else -> "草稿正在保存…"
    }
}

/** Draft writes and formal saves share a mutex, so late autosaves cannot resurrect discarded input. */
class EditorController(
    private val repository: DocumentRepository,
    private val scope: CoroutineScope,
    private val onSaved: suspend (LoadedDocument) -> Unit,
) {
    private val mutableState = MutableStateFlow(EditorUiState())
    val state = mutableState.asStateFlow()
    private val writes = Mutex()
    private var opening = 0L
    private var install = 0L
    private fun owns(id: String, request: Long) = opening == request && mutableState.value.document?.id == id
    init {
        scope.launch {
            while (isActive) {
                delay(750) // Throttle, not debounce: continuous input still reaches durable storage.
                persistCurrent()
            }
        }
    }

    fun open(id: String, ready: () -> Unit) = open(id, {}, ready)

    fun open(id: String, onFailure: (String) -> Unit, ready: () -> Unit) {
        val request = ++opening
        scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) { repository.find(id) } ?: error("没有找到这篇文稿")
                val draft = withContext(Dispatchers.IO) { repository.draft(id) }
                val versions = withContext(Dispatchers.IO) { repository.versions(id) }
                if (request != opening) return@launch
                mutableState.value = EditorUiState(document = loaded, title = loaded.title, text = loaded.text,
                    savedInputTitle = loaded.title, savedInputText = loaded.text, versions = versions, retainedTitle = loaded.legacyTitle,
                    installRevision = ++install, pendingDraft = draft?.takeIf { it.title != (loaded.legacyTitle ?: loaded.title) || it.text != loaded.text })
                ready()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (request != opening) return@launch
                val message = if (error.message == "没有找到这篇文稿") "没有找到这篇文稿，请返回文稿列表"
                    else "暂时无法打开文稿，请返回后重试"
                mutableState.value = mutableState.value.copy(saving = false, error = message)
                onFailure(message)
            }
        }
    }

    fun change(title: String, text: String) {
        val current = mutableState.value
        if (title.length > MAX_NAME_CHARACTERS) {
            mutableState.value = current.copy(error = "文稿名称不能超过 1000 字，请缩短名称后重试"); return
        }
        if (text.length > 1_000_000) {
            mutableState.value = current.copy(error = "正文超过 100 万字，请拆分后输入"); return
        }
        if (current.title == title && current.text == text) return
        mutableState.value = current.copy(title = title, text = text, revision = current.revision + 1,
            dirty = title != current.savedInputTitle || text != current.savedInputText, error = null,
            retainedTitle = current.retainedTitle.takeIf { current.title == title })
    }
    fun position(start: Int, end: Int, scroll: Int) {
        val s = mutableState.value
        mutableState.value = s.copy(selectionStart = start.coerceIn(0, s.text.length),
            selectionEnd = end.coerceIn(0, s.text.length), scrollY = scroll.coerceAtLeast(0))
    }

    fun chooseDraft(restore: Boolean) {
        val s = mutableState.value
        val draft = s.pendingDraft ?: return
        if (s.saving || s.choosingDraft) return
        val request = opening
        if (restore) mutableState.value = s.copy(title = namePreview(draft.title), text = draft.text,
            retainedTitle = draft.title.takeIf { it.length > MAX_NAME_CHARACTERS },
            selectionStart = draft.selectionStart.coerceIn(0, draft.text.length),
            selectionEnd = draft.selectionEnd.coerceIn(0, draft.text.length),
            revision = s.revision + 1, persistedRevision = s.revision + 1,
            pendingDraft = null, dirty = true, error = null, installRevision = ++install)
        else {
            mutableState.value = s.copy(choosingDraft = true, error = null)
            scope.launch {
                try {
                    writes.withLock {
                        if (!owns(draft.documentId, request)) return@withLock
                        withContext(Dispatchers.IO) { repository.deleteDraft(draft.documentId) }
                        if (owns(draft.documentId, request)) mutableState.value = mutableState.value.copy(
                            pendingDraft = null, choosingDraft = false, error = null)
                    }
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    if (owns(draft.documentId, request)) mutableState.value = mutableState.value.copy(
                        choosingDraft = false, error = "无法放弃草稿，原草稿已保留，请检查空间后重试")
                }
            }
        }
    }

    fun flush() { scope.launch { persistCurrent() } }
    private suspend fun persistCurrent() = writes.withLock {
        val s = mutableState.value
        val id = s.document?.id ?: return@withLock
        val request = opening
        if (!s.dirty || s.pendingDraft != null || s.persistedRevision == s.revision) return@withLock
        try {
            withContext(Dispatchers.IO) { repository.saveDraft(
                EditorDraftEntity(id, s.title, s.text, s.selectionStart, s.selectionEnd, System.currentTimeMillis()), s.retainedTitle) }
            val latest = mutableState.value
            if (owns(id, request) && latest.revision == s.revision)
                mutableState.value = latest.copy(persistedRevision = s.revision, error = null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if (owns(id, request)) mutableState.value = mutableState.value.copy(error = "草稿保存失败，请检查存储空间并重试")
        }
    }

    fun save(after: (() -> Unit)? = null) {
        val submitted = mutableState.value
        val id = submitted.document?.id ?: return
        val request = opening
        if (submitted.saving || submitted.pendingDraft != null) return
        if (submitted.text.isBlank()) { mutableState.value = submitted.copy(error = "正文不能为空"); return }
        mutableState.value = submitted.copy(saving = true, error = null)
        scope.launch {
            var committed = false
            try {
                val loaded = writes.withLock {
                    withContext(Dispatchers.IO) {
                        repository.updateContent(id, submitted.title, submitted.text, submitted.retainedTitle)
                        committed = true
                        repository.load(id) ?: error("保存后无法读取文稿")
                    }
                }
                val versions = withContext(Dispatchers.IO) { repository.versions(id) }
                val latest = mutableState.value
                if (!owns(id, request)) return@launch
                // Keep the exact editor buffer/cursor; normalization is the stored document's concern.
                mutableState.value = latest.copy(document = loaded.document, versions = versions, saving = true,
                    savedInputTitle = submitted.title, savedInputText = submitted.text,
                    dirty = latest.title != submitted.title || latest.text != submitted.text, error = null)
                writes.withLock {
                    if (!owns(id, request)) return@withLock
                    if (mutableState.value.revision == submitted.revision)
                        withContext(Dispatchers.IO) { repository.deleteDraft(id) }
                    else {
                        val current = mutableState.value
                        withContext(Dispatchers.IO) { repository.saveDraft(EditorDraftEntity(id, current.title,
                            current.text, current.selectionStart, current.selectionEnd, System.currentTimeMillis()), current.retainedTitle) }
                        if (owns(id, request) && mutableState.value.revision == current.revision)
                            mutableState.value = mutableState.value.copy(persistedRevision = current.revision)
                    }
                }
                onSaved(loaded)
                if (owns(id, request)) {
                    val finished = mutableState.value
                    mutableState.value = finished.copy(saving = false)
                    if (finished.revision == submitted.revision) after?.invoke()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (!owns(id, request)) return@launch
                val latest = mutableState.value
                mutableState.value = latest.copy(saving = false,
                    savedInputTitle = if (committed) submitted.title else latest.savedInputTitle,
                    savedInputText = if (committed) submitted.text else latest.savedInputText,
                    dirty = if (committed) latest.title != submitted.title || latest.text != submitted.text else latest.dirty,
                    error = if (committed) "正式文稿已保存，后续整理未完成，请稍后重试保存；当前输入保留" else when (error.message) {
                    "文稿超过 100 万字，请拆分后保存",
                    "文稿整理后超过 100 万字，请拆分后保存",
                    "没有找到可朗读的文字" -> error.message
                    else -> "保存失败，输入仍在本页，请检查空间后再点保存"
                })
            }
        }
    }

    fun discard(after: () -> Unit) {
        if (mutableState.value.saving) return
        val id = mutableState.value.document?.id ?: return
        val request = opening
        scope.launch {
            try {
                writes.withLock {
                    withContext(Dispatchers.IO) { repository.deleteDraft(id) }
                    if (!owns(id, request)) return@withLock
                    mutableState.value = EditorUiState(installRevision = ++install)
                }
                if (opening == request && mutableState.value.document == null) after()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { if (owns(id, request)) mutableState.value = mutableState.value.copy(error = "无法放弃草稿，请检查存储空间后重试") }
        }
    }

    fun restoreVersion(versionId: String) {
        val s = mutableState.value
        val id = s.document?.id ?: return
        val request = opening
        if (s.saving || s.dirty) { mutableState.value = s.copy(error = "请先保存当前修改，再恢复历史版本"); return }
        mutableState.value = s.copy(saving = true)
        scope.launch {
            var committed = false
            try {
                val loaded = writes.withLock {
                    withContext(Dispatchers.IO) {
                        repository.restoreVersion(id, versionId)
                        committed = true
                        repository.load(id) ?: error("恢复后无法读取文稿")
                    }
                }
                if (!owns(id, request)) return@launch
                onSaved(loaded)
                if (owns(id, request)) open(id) {}
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owns(id, request)) mutableState.value = mutableState.value.copy(saving = false,
                    error = if (committed) "历史版本已恢复，页面刷新未完成，请返回后重新打开" else "恢复失败，请检查空间后重新选择版本")
            }
        }
    }

    fun clearVersionNameError() {
        mutableState.value = mutableState.value.copy(versionNameError = null)
    }

    fun renameVersion(versionId: String, title: String, after: () -> Unit) {
        val s = mutableState.value; val id = s.document?.id ?: return
        val request = opening
        if (s.saving) return
        mutableState.value = s.copy(saving = true, renamingVersion = true, versionNameError = null)
        scope.launch {
            var committed = false
            try {
                val versions = writes.withLock {
                    withContext(Dispatchers.IO) {
                        repository.renameVersion(id, versionId, title)
                        committed = true
                        repository.versions(id)
                    }
                }
                if (owns(id, request)) {
                    mutableState.value = mutableState.value.copy(saving = false, renamingVersion = false, versions = versions)
                    after()
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (owns(id, request)) mutableState.value = mutableState.value.copy(saving = false, renamingVersion = false,
                    versionNameError = if (committed) "历史名称已修改，列表刷新未完成，请返回后重新打开"
                        else "历史名称未修改，请检查名称和存储空间后重试；原版本仍保留")
            }
        }
    }
}
