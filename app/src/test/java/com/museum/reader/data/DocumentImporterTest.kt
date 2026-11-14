package com.museum.reader.data

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentImporterTest {
    @Test fun nestedTextboxesPreserveEverySurroundingTextSpanInOrder() {
        val xml = """<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main" xmlns:v="urn:schemas-microsoft-com:vml"><w:body>
            <w:p><w:r><w:t>外层前文</w:t></w:r><w:r><w:pict><v:shape><v:textbox><w:txbxContent>
            <w:p><w:r><w:t>框内前文</w:t></w:r><w:r><w:pict><v:shape><v:textbox><w:txbxContent>
            <w:p><w:r><w:t>深层文字</w:t></w:r></w:p>
            </w:txbxContent></v:textbox></v:shape></w:pict></w:r><w:r><w:t>框内后文</w:t></w:r></w:p>
            </w:txbxContent></v:textbox></v:shape></w:pict></w:r><w:r><w:t>外层后文</w:t></w:r></w:p>
            <w:p><w:r><w:t>下一段</w:t></w:r></w:p></w:body></w:document>""".trimIndent()
        assertEquals("外层前文\n\n框内前文\n\n深层文字\n\n框内后文\n\n外层后文\n\n下一段",
            DocumentImporter.parseWordXml(xml.toByteArray()))
    }

    @Test
    fun `decodes utf8 bom and gb18030`() {
        val text = "三星堆讲解词"
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, DocumentImporter.decodeText(utf8))
        assertEquals(text, DocumentImporter.decodeText(text.toByteArray(Charset.forName("GB18030"))))
    }

    @Test
    fun `extracts paragraphs tabs and breaks from docx`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>第一段</w:t></w:r><w:r><w:tab/><w:t>续文</w:t></w:r></w:p>
                <w:p><w:r><w:t>第二段</w:t><w:br/><w:t>换行</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent().toByteArray()
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("word/document.xml"))
            zip.write(xml)
            zip.closeEntry()
        }

        assertEquals("第一段\t续文\n\n第二段\n换行", DocumentImporter.parseDocx(output.toByteArray()))
    }
    @Test fun utf16BothOrdersAndGbkPreserveCharacters() {
        val text = "中文与 English 标点。\n第二段。"
        for ((name, bom) in listOf("UTF-16LE" to byteArrayOf(0xff.toByte(), 0xfe.toByte()),
            "UTF-16BE" to byteArrayOf(0xfe.toByte(), 0xff.toByte()))) {
            assertEquals(text, DocumentImporter.decodeText(bom + text.toByteArray(Charset.forName(name))))
        }
        assertEquals(text, DocumentImporter.decodeText(text.toByteArray(Charset.forName("GBK"))))
    }
    @Test fun damagedDeclaredEncodingIsRejectedWithoutReplacementCharacters() {
        assertThrows(IllegalStateException::class.java) {
            DocumentImporter.decodeText(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x41))
        }
        assertThrows(IllegalStateException::class.java) {
            DocumentImporter.decodeText(byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte(), 0xff.toByte()))
        }
    }
    @Test fun malformedAndImageOnlyDocxAreHandledExplicitly() {
        assertThrows(Exception::class.java) { DocumentImporter.parseDocx(byteArrayOf(1, 2, 3)) }
        val body = "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"><w:body><w:p/></w:body></w:document>"
        assertEquals("", DocumentImporter.parseDocx(docx("word/document.xml" to body.toByteArray())))
        assertThrows(Exception::class.java) { DocumentImporter.parseDocx(docx("word/document.xml" to "<broken>".toByteArray())) }
    }
    @Test fun expandedNonBodyEntriesAreBoundedBeforeXmlParsing() {
        val oversized = ByteArray(65 * 1024 * 1024)
        val zip = docx("word/media/oversized.bin" to oversized, "word/document.xml" to "<x/>".toByteArray())
        val error = assertThrows(IllegalArgumentException::class.java) { DocumentImporter.parseDocx(zip) }
        assertTrue(error.message.orEmpty().contains("64 MB"))
    }
    @Test fun docxDtdCannotReadExternalOrExpandInternalEntities() {
        val xml = "<!DOCTYPE w:document [<!ENTITY value 'unexpected'>]><w:document xmlns:w=\"urn:test\"><w:p><w:t>&value;</w:t></w:p></w:document>"
        assertThrows(IllegalArgumentException::class.java) { DocumentImporter.parseWordXml(xml.toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) { DocumentImporter.parseWordXml(xml.toByteArray(Charsets.UTF_16)) }
    }
    private fun docx(vararg entries: Pair<String, ByteArray>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zip -> entries.forEach { (name, bytes) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        } }
        return output.toByteArray()
    }

}
