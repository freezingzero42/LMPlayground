package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [HtmlPreviewTool] argument handling, page assembly and repair of the
 * escaping mistakes small models make. Robolectric supplies a real org.json
 * (the android.jar stubs throw on the plain JVM).
 */
@RunWith(RobolectricTestRunner::class)
class HtmlPreviewToolTest {

    private val tool = HtmlPreviewTool()

    private fun args(
        html: String,
        css: String? = null,
        js: String? = null,
        title: String? = null,
    ): String = JSONObject().put("html", html).apply {
        if (css != null) put("css", css)
        if (js != null) put("javascript", js)
        if (title != null) put("title", title)
    }.toString()

    private fun ok(arguments: String): HtmlPreviewTool.Preview {
        val r = HtmlPreviewTool.parse(arguments)
        assertTrue("expected Ok, got $r", r is HtmlPreviewTool.ParseResult.Ok)
        return (r as HtmlPreviewTool.ParseResult.Ok).preview
    }

    // ── Acknowledgement and errors ──────────────────────────────────

    @Test
    fun `valid page is acknowledged without echoing the html`() {
        val result = JSONObject(tool.execute(args("<h1>Hi</h1>", title = "Greeting")))
        assertEquals("rendered", result.getString("status"))
        assertFalse(result.toString().contains("<h1>"))
        assertFalse(result.has("warnings"))
    }

    @Test
    fun `missing blank or tagless html is an error`() {
        assertTrue(JSONObject(tool.execute("{}")).has("error"))
        assertTrue(JSONObject(tool.execute(args("   "))).has("error"))
        assertTrue(JSONObject(tool.execute(args("just some words"))).has("error"))
    }

    @Test
    fun `malformed json is an error, not a crash`() {
        assertTrue(JSONObject(tool.execute("not json")).has("error"))
    }

    @Test
    fun `oversized page is rejected`() {
        val big = "<p>" + "x".repeat(HtmlPreviewTool.MAX_HTML_CHARS) + "</p>"
        assertTrue(JSONObject(tool.execute(args(big))).getString("error").contains("too long"))
    }

    @Test
    fun `title is optional and whitespace is collapsed`() {
        assertEquals("", ok(args("<p>x</p>")).title)
        assertEquals("Tip calculator", ok(args("<p>x</p>", title = "  Tip \n calculator ")).title)
    }

    // ── Assembly ────────────────────────────────────────────────────

    @Test
    fun `fragment is wrapped with css in head and js at end of body`() {
        val doc = ok(args("<h1 class=\"t\">Hi</h1>", css = ".t{color:red}", js = "console.log(1)")).html
        assertTrue(doc.startsWith("<!DOCTYPE html>"))
        assertTrue(doc.contains("name=\"viewport\""))
        val head = doc.substring(0, doc.indexOf("</head>"))
        assertTrue(head.contains("<style>\n.t{color:red}\n</style>"))
        val body = doc.substring(doc.indexOf("<body>"))
        assertTrue(body.indexOf("<h1") < body.indexOf("<script>"))
        assertTrue(body.contains("console.log(1)"))
    }

    @Test
    fun `full document gets css and js spliced in, not wrapped again`() {
        val page = "<!DOCTYPE html><html><head><title>x</title></head><body><p>a</p></body></html>"
        val doc = ok(args(page, css = "p{color:blue}", js = "go()")).html
        assertEquals(1, Regex("<html").findAll(doc).count())
        assertTrue(doc.indexOf("p{color:blue}") < doc.indexOf("</head>"))
        assertTrue(doc.indexOf("go()") < doc.indexOf("</body>"))
        assertTrue(doc.contains("charset"))
    }

    @Test
    fun `full document without head gets one`() {
        val doc = ok(args("<html><body><p>a</p></body></html>", css = "p{}")).html
        assertTrue(doc.indexOf("<head>") < doc.indexOf("p{}"))
        assertTrue(doc.indexOf("p{}") < doc.indexOf("<body>"))
    }

    // ── Repair ──────────────────────────────────────────────────────

    @Test
    fun `surrounding markdown fence is stripped from every field`() {
        val doc = ok(args("```html\n<div>ok</div>\n```", css = "```css\ndiv{color:red}\n```")).html
        assertTrue(doc.contains("<div>ok</div>"))
        assertFalse(doc.contains("```"))
        assertTrue(doc.contains("<style>\ndiv{color:red}\n</style>"))
    }

    @Test
    fun `fully double-escaped page is decoded`() {
        // What the engine hands us when the model JSON-escaped the page twice.
        val escaped = """<style>\n.card{color:red}\n</style>\n<div class=\"card\">Hi</div>"""
        val doc = ok(args(escaped)).html
        assertTrue(doc.contains("<div class=\"card\">Hi</div>"))
        assertTrue(doc.contains("\n.card{color:red}\n"))
        assertFalse(doc.contains("\\n"))
    }

    @Test
    fun `escaped attribute quotes are fixed but script strings are kept`() {
        val html = "<div class=\\\"card\\\">\n<p>x</p>\n</div>\n" +
            "<script>el.innerHTML = \"<b class=\\\"x\\\">\";</script>"
        val doc = ok(args(html)).html
        assertTrue(doc.contains("<div class=\"card\">"))
        assertTrue(doc.contains("el.innerHTML = \"<b class=\\\"x\\\">\";"))
    }

    @Test
    fun `correct page with escapes inside javascript is untouched`() {
        val html = "<p id=\"o\"></p>\n<script>o.textContent = 'a\\nb' + \"\\\"q\\\"\";</script>"
        assertTrue(ok(args(html)).html.contains(html))
    }

    @Test
    fun `backticks and template strings are left alone`() {
        val js = "const s = `a ${'$'}{1}`;"
        assertTrue(ok(args("<p>x</p>", js = js)).html.contains(js))
    }

    // ── Warnings ────────────────────────────────────────────────────

    @Test
    fun `external resources are reported back to the model`() {
        val html = "<link rel=\"stylesheet\" href=\"https://cdn.example.com/a.css\">" +
            "<script src=\"//cdn.tailwindcss.com\"></script><p>x</p>"
        val result = JSONObject(tool.execute(args(html, css = "@import url('https://fonts.example.com/f.css');")))
        assertEquals("rendered", result.getString("status"))
        val warnings = result.getJSONArray("warnings").toString()
        assertTrue(warnings.contains("https://cdn.example.com/a.css"))
        assertTrue(warnings.contains("//cdn.tailwindcss.com"))
        assertTrue(warnings.contains("https://fonts.example.com/f.css"))
    }

    // ── Markdown fallback ───────────────────────────────────────────

    @Test
    fun `html css and js code blocks combine into one preview`() {
        val reply = "Here you go:\n\n```html\n<button id=\"b\">Go</button>\n```\n\n" +
            "```css\nbutton{color:red}\n```\n\n```javascript\nb.onclick = () => 1;\n```\nEnjoy!"
        val previews = HtmlPreviewTool.previewsFromMarkdown(reply)
        assertEquals(1, previews.size)
        val doc = previews[0].html
        assertTrue(doc.contains("<button id=\"b\">Go</button>"))
        assertTrue(doc.contains("button{color:red}"))
        assertTrue(doc.contains("b.onclick"))
    }

    @Test
    fun `no html block means no preview`() {
        assertTrue(HtmlPreviewTool.previewsFromMarkdown("```css\na{}\n```\n```kotlin\nval x = 1\n```").isEmpty())
        assertTrue(HtmlPreviewTool.previewsFromMarkdown("plain text").isEmpty())
    }

    @Test
    fun `several html blocks become separate previews`() {
        val reply = "```html\n<p>a</p>\n```\ntext\n```html\n<p>b</p>\n```"
        assertEquals(2, HtmlPreviewTool.previewsFromMarkdown(reply).size)
    }

    // ── Registry ────────────────────────────────────────────────────

    @Test
    fun `tool is exposed through the registry with html css and javascript`() {
        val registry = ToolRegistry().apply {
            register(HtmlPreviewTool())
            setToolEnabled(HtmlPreviewTool.NAME, true)
        }
        val tools = JSONArray(registry.toOpenAIToolsJson())
        assertEquals(1, tools.length())
        val fn = tools.getJSONObject(0).getJSONObject("function")
        assertEquals(HtmlPreviewTool.NAME, fn.getString("name"))
        val params = fn.getJSONObject("parameters")
        assertEquals("html", params.getJSONArray("required").getString(0))
        val props = params.getJSONObject("properties")
        assertTrue(props.has("css"))
        assertTrue(props.has("javascript"))
    }
}
