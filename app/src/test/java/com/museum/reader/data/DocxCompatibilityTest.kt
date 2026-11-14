package com.museum.reader.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DocxCompatibilityTest {
    private fun p(text: String) = "<w:p><w:r><w:t>$text</w:t></w:r></w:p>"
    private fun choice(text: String, requires: String = "wps") = "<mc:Choice Requires=\"$requires\">${p(text)}</mc:Choice>"
    private fun fallback(text: String) = "<mc:Fallback>${p(text)}</mc:Fallback>"
    private fun alt(body: String) = "<mc:AlternateContent>$body</mc:AlternateContent>"
    private fun parse(body: String): String = DocumentImporter.parseWordXml(("""<w:document
        xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
        xmlns:mc="http://schemas.openxmlformats.org/markup-compatibility/2006"
        xmlns:wps="http://schemas.microsoft.com/office/word/2010/wordprocessingShape"
        xmlns:unknown="urn:unsupported"><w:body>""" + body + "</w:body></w:document>").toByteArray())

    @Test fun alternateTextboxesAppearOnceAndKeepOuterSpans() {
        val body = "<w:p><w:r><w:t>前文</w:t></w:r>" + alt(choice("文本框") + fallback("文本框")) +
            "<w:r><w:t>后文</w:t></w:r></w:p>"
        assertEquals("前文\n\n文本框\n\n后文", parse(body))
    }
    @Test fun fallbackAndSingleChoiceRemainReadable() {
        assertEquals("备用", parse(alt(fallback("备用"))))
        assertEquals("单分支", parse(alt(choice("单分支", "unknown"))))
        assertEquals("备用", parse(alt(choice("不支持", "unknown") + fallback("备用"))))
    }
    @Test fun firstSupportedChoiceIsSelectedAndRealRepeatedTextIsRetained() {
        assertEquals("可处理\n\n重复\n\n重复", parse(alt(choice("不支持", "unknown") + choice("可处理") +
            choice("后分支") + fallback("备用")) + p("重复") + p("重复")))
    }
    @Test fun nestedAlternateContentKeepsIndependentSelections() {
        val inner = alt(choice("内层选择") + fallback("内层备用"))
        val outer = alt("<mc:Choice Requires=\"wps\">" + p("选择前") + inner + p("选择后") +
            "</mc:Choice>" + fallback("外层备用"))
        assertEquals("选择前\n\n内层选择\n\n选择后", parse(outer))
    }
    @Test fun emptySupportedShapeUsesReadableFallbackOnce() {
        assertEquals("备用文字", parse(alt("<mc:Choice Requires=\"wps\"><wps:wsp/></mc:Choice>" + fallback("备用文字"))))
        assertEquals("备用文字", parse(alt(choice("   ") + fallback("备用文字"))))
        assertEquals("后续可读分支", parse(alt("<mc:Choice Requires=\"wps\"><wps:wsp/></mc:Choice>" + choice("后续可读分支") + fallback("备用文字"))))
    }
}
