package com.museum.reader.data

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.OpenableColumns
import com.museum.reader.model.DocumentSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.helpers.DefaultHandler

data class ImportedDocument(
    val title: String,
    val text: String,
    val source: DocumentSource,
)

object DocumentImporter {
    private const val MAX_IMPORT_BYTES = 20L * 1024L * 1024L
    private const val MAX_DOCUMENT_XML_BYTES = 8L * 1024L * 1024L
    private const val MAX_DOCX_EXPANDED_BYTES = 64L * 1024L * 1024L
    private const val MAX_DOCX_ENTRIES = 10_000
    private const val MAX_TEXT_CHARACTERS = 1_000_000

    fun import(contentResolver: ContentResolver, uri: Uri): ImportedDocument {
        val fileName = queryFileName(contentResolver, uri) ?: "导入的讲解词"
        queryFileSize(contentResolver, uri)?.let { size ->
            if (size > MAX_IMPORT_BYTES) error("文件超过 20 MB，请精简后再导入")
        }
        val bytes = contentResolver.openInputStream(uri)?.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0L
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                total += count
                if (total > MAX_IMPORT_BYTES) error("文件超过 20 MB，请精简后再导入")
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("无法读取所选文件")

        val extension = fileName.substringAfterLast('.', "").lowercase()
        val mimeType = contentResolver.getType(uri).orEmpty().lowercase()
        val (text, source) = when (extension) {
            "txt" -> decodeText(bytes) to DocumentSource.TXT
            "docx" -> parseDocx(bytes) to DocumentSource.DOCX
            "doc" -> error("暂不支持旧版 .doc，请在 Word 中另存为 .docx 后再导入")
            else -> when {
                mimeType == "text/plain" || mimeType.startsWith("text/") -> decodeText(bytes) to DocumentSource.TXT
                mimeType.contains("wordprocessingml") || looksLikeDocx(bytes) -> parseDocx(bytes) to DocumentSource.DOCX
                mimeType == "application/msword" -> error("暂不支持旧版 .doc，请在 Word 中另存为 .docx 后再导入")
                else -> error("无法识别文件格式，仅支持 TXT 和 DOCX")
            }
        }
        if (text.isBlank()) error("文件中没有可朗读的文字")
        if (text.length > MAX_TEXT_CHARACTERS || com.museum.reader.domain.TextSegmenter.segment(text).normalizedText.length > MAX_TEXT_CHARACTERS) error("文稿超过 100 万字，请拆分后导入")
        return ImportedDocument(
            title = fileName.substringBeforeLast('.').trim().ifBlank { "导入的讲解词" },
            text = text,
            source = source,
        )
    }

    internal fun decodeText(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return when {
            bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) ->
                decodeStrict(bytes.copyOfRange(3, bytes.size), StandardCharsets.UTF_8)
            bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) ->
                decodeStrict(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16LE)
            bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) ->
                decodeStrict(bytes.copyOfRange(2, bytes.size), StandardCharsets.UTF_16BE)
            else -> decodeStrictUtf8(bytes) ?: decodeStrict(bytes, charset("GB18030"))
        }.replace("\u0000", "").trim()
    }

    private fun decodeStrict(bytes: ByteArray, charset: java.nio.charset.Charset): String = try {
        charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        error("文字编码损坏或无法识别，请另存为 UTF-8 后导入")
    }
    private fun decodeStrictUtf8(bytes: ByteArray): String? = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) { null }

    internal fun parseDocx(bytes: ByteArray): String {
        var documentXml: ByteArray? = null
        var expanded = 0L
        val names = HashSet<String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var entry = zip.nextEntry
            while (entry != null) {
                require(names.add(entry.name) && names.size <= MAX_DOCX_ENTRIES) { "DOCX 包含重复条目或文件过多" }
                val isBody = !entry.isDirectory && entry.name == "word/document.xml"
                val body = if (isBody) ByteArrayOutputStream() else null
                var entryBytes = 0L
                while (true) {
                    val count = zip.read(buffer)
                    if (count < 0) break
                    entryBytes += count
                    expanded += count
                    require(expanded <= MAX_DOCX_EXPANDED_BYTES) { "DOCX 解压内容超过 64 MB，请精简或拆分文件" }
                    require(!isBody || entryBytes <= MAX_DOCUMENT_XML_BYTES) { "DOCX 正文解压后过大，请精简或拆分文件" }
                    body?.write(buffer, 0, count)
                }
                if (isBody) documentXml = body!!.toByteArray()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        val xml = documentXml ?: error("这不是有效的 DOCX 文件，或文件已经损坏")
        return parseWordXml(xml).trim()
    }

    internal fun parseWordXml(xml: ByteArray): String {
        require(xml.size <= MAX_DOCUMENT_XML_BYTES) { "DOCX 正文解压后过大，请精简或拆分文件" }
        // DOCX does not require DTD. Reject it even where Android's SAX feature is unsupported.
        require(!xml.toString(StandardCharsets.ISO_8859_1).replace("\u0000", "").contains("<!DOCTYPE", ignoreCase = true)) {
            "DOCX 包含不支持的外部文档声明，请在 Word 中另存后重试"
        }
        val paragraphs = mutableListOf<String>()
        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val handler = object : DefaultHandler() {
            private var paragraph: StringBuilder? = null
            private val outerParagraphs = java.util.ArrayDeque<StringBuilder>()
            private var insideText = false

            private fun finishSpan() {
                paragraph?.toString()?.trim()?.takeIf(String::isNotEmpty)?.let(paragraphs::add)
                paragraph?.setLength(0)
            }

            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                when (localName ?: qName?.substringAfter(':')) {
                    "p" -> {
                        paragraph?.let { outer ->
                            finishSpan()
                            outerParagraphs.addLast(outer)
                        }
                        paragraph = StringBuilder()
                    }
                    "t" -> insideText = true
                    "tab" -> paragraph?.append('\t')
                    "br", "cr" -> paragraph?.append('\n')
                }
            }

            override fun characters(ch: CharArray, start: Int, length: Int) {
                if (insideText) paragraph?.append(ch, start, length)
            }

            override fun endElement(uri: String?, localName: String?, qName: String?) {
                when (localName ?: qName?.substringAfter(':')) {
                    "t" -> insideText = false
                    "p" -> {
                        finishSpan()
                        paragraph = if (outerParagraphs.isEmpty()) null else outerParagraphs.removeLast()
                    }
                }
            }
        }
        factory.newSAXParser().parse(ByteArrayInputStream(xml), handler)
        return paragraphs.joinToString("\n\n")
    }

    private fun queryFileName(contentResolver: ContentResolver, uri: Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0) else uri.lastPathSegment
        } finally {
            cursor?.close()
        }
    }

    private fun queryFileSize(contentResolver: ContentResolver, uri: Uri): Long? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            if (cursor?.moveToFirst() == true && !cursor.isNull(0)) cursor.getLong(0) else null
        } finally {
            cursor?.close()
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun looksLikeDocx(bytes: ByteArray): Boolean =
        bytes.startsWith(byteArrayOf('P'.code.toByte(), 'K'.code.toByte()))
}
