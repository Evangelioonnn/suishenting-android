package com.museum.reader.data

import org.json.JSONArray
import org.json.JSONException

internal const val MAX_NAME_CHARACTERS = 1_000

internal fun checkedName(value: String, label: String): String = value.also {
    require(it.length <= MAX_NAME_CHARACTERS) { "${label}不能超过 1000 字，请缩短名称后重试" }
}

internal data class HistorySentence(val id: String, val text: String)

/** A parseable JSON array alone does not make a usable historical reference. */
internal fun historySentences(payload: String): List<HistorySentence> {
    try {
        val array = JSONArray(payload)
        val ids = HashSet<String>()
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            require(item.opt("id") is String && item.opt("text") is String) { "备份历史句子记录缺少编号或文字" }
            val id = item.requiredId("id")
            val text = item.getString("text")
            require(ids.add(id) && text.isNotBlank() && text.length <= 1_000_000) { "备份历史句子记录重复或内容无效" }
            HistorySentence(id, text)
        }
    } catch (error: JSONException) {
        throw IllegalArgumentException("备份历史句子记录不完整，请重新选择有效备份", error)
    }
}
