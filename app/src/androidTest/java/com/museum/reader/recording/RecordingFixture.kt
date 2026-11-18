package com.museum.reader.recording

import android.content.Context
import com.museum.reader.data.AudioFiles
import java.io.File
import org.json.JSONObject

internal fun writeTestJournal(context: Context, pending: PendingRecording): File {
    val journal = File(context.filesDir, "recording-pending/${pending.id}.json")
    AudioFiles.writeAtomic(journal, JSONObject().put("documentId", pending.documentId)
        .put("sentenceId", pending.sentenceId).put("textHash", pending.textHash)
        .put("path", pending.path).toString())
    return journal
}
