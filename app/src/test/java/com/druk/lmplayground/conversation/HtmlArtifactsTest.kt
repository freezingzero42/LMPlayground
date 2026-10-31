package com.druk.lmplayground.conversation

import com.druk.lmplayground.conversation.HtmlArtifacts.Segment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [HtmlArtifacts] is pure Kotlin, so these run on the plain JVM. */
class HtmlArtifactsTest {

    private fun artifacts(text: String) = HtmlArtifacts.split(text).filterIsInstance<Segment.Artifact>()
    private fun texts(text: String) = HtmlArtifacts.split(text).filterIsInstance<Segment.Text>()

    // ── Splitting ───────────────────────────────────────────────────

    @Test
    fun `plain reply is a single text segment`() {
        val segments = HtmlArtifacts.split("Hello **world**")
        assertEquals(listOf(Segment.Text("Hello **world**")), segments)
    }

    @Test
    fun `html block becomes an artifact between text segments`() {
        val reply = "Here is your page:\n\n```html\n<h1>Hi</h1>\n<p>x</p>\n```\n\nEnjoy!"
        val segments = HtmlArtifacts.split(reply)
        assertEquals(3, segments.size)
        assertEquals(Segment.Text("Here is your page:"), segments[0])
        assertEquals(Segment.Artifact("<h1>Hi</h1>\n<p>x</p>", ordinal = 0, complete = true), segments[1])
        assertEquals(Segment.Text("Enjoy!"), segments[2])
    }

    @Test
    fun `other code blocks stay in the text with their fences`() {
        val reply = "Code:\n```kotlin\nval x = 1\n```\nDone"
        val segments = HtmlArtifacts.split(reply)
        assertEquals(1, segments.size)
        assertEquals(reply, (segments[0] as Segment.Text).text)
    }

    @Test
    fun `html inside another fenced block is not an artifact`() {
        val reply = "````markdown\n```html\n<p>x</p>\n```\n````"
        assertTrue(artifacts(reply).isEmpty())
    }

    @Test
    fun `unclosed html block while streaming is an incomplete artifact`() {
        val streaming = "Sure!\n```html\n<!DOCTYPE html>\n<html><body><h1>Ti"
        val a = artifacts(streaming).single()
        assertFalse(a.complete)
        assertTrue(a.code.endsWith("<h1>Ti"))
        assertEquals("Sure!", texts(streaming).single().text)
    }

    @Test
    fun `fence opened on the last line gives an empty incomplete artifact`() {
        val a = artifacts("Building it:\n```html").single()
        assertEquals("", a.code)
        assertFalse(a.complete)
    }

    @Test
    fun `ordinals count artifacts and are stable as the reply grows`() {
        val one = "```html\n<p>a</p>\n```\ntext\n```html\n<p>b"
        val two = "$one</p>\n```\nmore"
        assertEquals(listOf(0, 1), artifacts(one).map { it.ordinal })
        assertEquals(listOf(0, 1), artifacts(two).map { it.ordinal })
        assertTrue(artifacts(two).all { it.complete })
    }

    @Test
    fun `language tag is case insensitive and accepts htm and attributes`() {
        assertEquals(1, artifacts("```HTML\n<p>x</p>\n```").size)
        assertEquals(1, artifacts("```htm\n<p>x</p>\n```").size)
        assertEquals(1, artifacts("```html title=\"index.html\"\n<p>x</p>\n```").size)
        assertEquals(1, artifacts("~~~html\n<p>x</p>\n~~~").size)
        assertEquals(1, artifacts("   ```html\n<p>x</p>\n   ```").size)
    }

    @Test
    fun `unlabeled fence counts only when it holds a full document`() {
        assertEquals(1, artifacts("```\n<!DOCTYPE html>\n<html><body></body></html>\n```").size)
        assertEquals(1, artifacts("```\n<html lang=\"en\"></html>\n```").size)
        assertTrue(artifacts("```\n<p>just a snippet</p>\n```").isEmpty())
        assertTrue(artifacts("```\nnpm install\n```").isEmpty())
    }

    @Test
    fun `closing fence must be at least as long as the opening one`() {
        val reply = "````html\n<pre>```</pre>\n````\nafter"
        val a = artifacts(reply).single()
        assertEquals("<pre>```</pre>", a.code)
        assertTrue(a.complete)
    }

    // ── Preview document ────────────────────────────────────────────

    @Test
    fun `fragment is wrapped into a full page with viewport`() {
        val doc = HtmlArtifacts.buildDocument("<h1>Hi</h1>")
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("name=\"viewport\""))
        assertTrue(doc.contains("<body>\n<h1>Hi</h1>"))
    }

    @Test
    fun `full document is kept and gets charset and viewport if missing`() {
        val page = "<!DOCTYPE html><html><head><title>T</title></head><body><p>a</p></body></html>"
        val doc = HtmlArtifacts.buildDocument(page)
        assertEquals(1, Regex("<html").findAll(doc).count())
        assertTrue(doc.contains("charset=\"utf-8\""))
        assertTrue(doc.contains("name=\"viewport\""))
        assertTrue(doc.contains("<body><p>a</p></body>"))
    }

    @Test
    fun `existing viewport is not duplicated`() {
        val page = "<html><head><meta name=\"viewport\" content=\"width=device-width\"></head><body></body></html>"
        assertEquals(1, Regex("viewport").findAll(HtmlArtifacts.buildDocument(page)).count())
    }

    @Test
    fun `separate css and js blocks are folded into a single artifact`() {
        val reply = "```html\n<button id=\"b\">Go</button>\n```\n\n```css\nbutton{color:red}\n```\n\n" +
            "```javascript\nb.onclick = () => 1;\n```"
        val a = artifacts(reply).single()
        val doc = HtmlArtifacts.previewDocument(reply, a)
        assertTrue(doc.indexOf("button{color:red}") < doc.indexOf("</head>"))
        assertTrue(doc.indexOf("<button id=\"b\">") < doc.indexOf("b.onclick"))
    }

    @Test
    fun `with several html artifacts each previews on its own`() {
        val reply = "```html\n<p>a</p>\n```\n```html\n<p>b</p>\n```\n```css\np{color:red}\n```"
        val list = artifacts(reply)
        assertEquals(2, list.size)
        val doc = HtmlArtifacts.previewDocument(reply, list[1])
        assertTrue(doc.contains("<p>b</p>"))
        assertFalse(doc.contains("<p>a</p>"))
        assertFalse(doc.contains("p{color:red}"))
    }

    @Test
    fun `cdn links and template strings survive untouched`() {
        val page = "<script src=\"https://cdn.tailwindcss.com\"></script>\n" +
            "<p id=\"o\"></p><script>o.textContent = `a ${'$'}{1}\\n`;</script>"
        val doc = HtmlArtifacts.buildDocument(page)
        assertTrue(doc.contains(page))
    }

    // ── Title ───────────────────────────────────────────────────────

    @Test
    fun `title comes from title tag, then h1, else blank`() {
        assertEquals("Tip & Split", HtmlArtifacts.title("<title> Tip &amp; Split </title><h1>Other</h1>"))
        assertEquals("Snake game", HtmlArtifacts.title("<h1 class=\"x\">Snake <b>game</b></h1>"))
        assertEquals("", HtmlArtifacts.title("<p>no heading</p>"))
    }
}
