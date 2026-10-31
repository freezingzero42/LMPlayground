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
 * [HtmlPreviewTool] argument handling. Robolectric supplies a real org.json
 * (the android.jar stubs throw on the plain JVM).
 */
@RunWith(RobolectricTestRunner::class)
class HtmlPreviewToolTest {

    private val tool = HtmlPreviewTool()

    private fun args(html: String, title: String? = null): String =
        JSONObject().put("html", html).apply { if (title != null) put("title", title) }.toString()

    private fun ok(arguments: String): HtmlPreviewTool.Preview {
        val r = HtmlPreviewTool.parse(arguments)
        assertTrue("expected Ok, got $r", r is HtmlPreviewTool.ParseResult.Ok)
        return (r as HtmlPreviewTool.ParseResult.Ok).preview
    }

    @Test
    fun `valid page is acknowledged without echoing the html`() {
        val html = "<!DOCTYPE html><html><body><h1>Hi</h1></body></html>"
        val result = JSONObject(tool.execute(args(html, "Greeting")))
        assertEquals("rendered", result.getString("status"))
        assertEquals("Greeting", result.getString("title"))
        assertEquals(html.length, result.getInt("characters"))
        assertFalse(result.toString().contains("<h1>"))
    }

    @Test
    fun `title is optional and whitespace is collapsed`() {
        assertEquals("", ok(args("<p>x</p>")).title)
        assertEquals("Tip calculator", ok(args("<p>x</p>", "  Tip \n calculator ")).title)
    }

    @Test
    fun `missing or blank html is an error`() {
        assertTrue(JSONObject(tool.execute("{}")).has("error"))
        assertTrue(JSONObject(tool.execute(args("   "))).has("error"))
    }

    @Test
    fun `malformed json is an error, not a crash`() {
        assertTrue(JSONObject(tool.execute("not json")).has("error"))
    }

    @Test
    fun `text without tags is rejected`() {
        assertTrue(JSONObject(tool.execute(args("just some words"))).has("error"))
    }

    @Test
    fun `oversized page is rejected`() {
        val big = "<p>" + "x".repeat(HtmlPreviewTool.MAX_HTML_CHARS) + "</p>"
        val error = JSONObject(tool.execute(args(big))).getString("error")
        assertTrue(error.contains("too long"))
    }

    @Test
    fun `surrounding markdown fence is stripped`() {
        val fenced = "```html\n<div>ok</div>\n```"
        assertEquals("<div>ok</div>", ok(args(fenced)).html)
        // A bare fence without a language tag too.
        assertEquals("<b>x</b>", ok(args("```\n<b>x</b>\n```")).html)
    }

    @Test
    fun `backticks inside the page are left alone`() {
        val html = "<script>const s = `a ${'$'}{1}`;</script>"
        assertEquals(html, ok(args(html)).html)
    }

    @Test
    fun `tool is exposed through the registry when enabled`() {
        val registry = ToolRegistry().apply {
            register(HtmlPreviewTool())
            setToolEnabled(HtmlPreviewTool.NAME, true)
        }
        val tools = JSONArray(registry.toOpenAIToolsJson())
        assertEquals(1, tools.length())
        val fn = tools.getJSONObject(0).getJSONObject("function")
        assertEquals(HtmlPreviewTool.NAME, fn.getString("name"))
        assertEquals("html", fn.getJSONObject("parameters").getJSONArray("required").getString(0))
    }
}
