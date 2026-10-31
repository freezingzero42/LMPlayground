package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import org.json.JSONException
import org.json.JSONObject

/**
 * `render_html` — lets the model show the user a live, interactive HTML
 * preview inside the chat.
 *
 * The tool itself does almost nothing at execution time: it validates and
 * normalizes the page, then acknowledges it. The page is rendered by the chat
 * UI (see `conversation/HtmlPreview.kt`) straight from the tool call's
 * persisted arguments, so previews survive reopening a conversation without
 * any extra storage.
 *
 * The result sent back to the model is deliberately tiny — echoing the page
 * would double its cost in a small on-device context window.
 */
class HtmlPreviewTool : Tool {
    override val name = NAME

    override val description =
        "Show the user a live, interactive preview of an HTML page in the chat. " +
            "Use it whenever the user asks for a web page, UI, form, widget, chart, animation or small game. " +
            "Pass ONE complete, self-contained document: put all CSS in <style> and all JavaScript in <script>. " +
            "The preview is offline: external scripts, stylesheets, fonts and images will NOT load, " +
            "so use inline SVG, <canvas> or data: URIs instead. " +
            "The user can already see and run the page, so do not repeat the code in your reply; " +
            "just briefly say what you built."

    override val parametersSchema =
        """{"type":"object","properties":{""" +
            """"html":{"type":"string","description":"Complete HTML document, e.g. '<!DOCTYPE html><html><head><style>...</style></head><body>...<script>...</script></body></html>'."},""" +
            """"title":{"type":"string","description":"Short title shown above the preview, e.g. 'Tip calculator'."}""" +
            """},"required":["html"]}"""

    override fun execute(arguments: String): String {
        val parsed = when (val r = parse(arguments)) {
            is ParseResult.Ok -> r.preview
            is ParseResult.Error -> return errorJson(r.message)
        }
        return JSONObject()
            .put("status", "rendered")
            .put("title", parsed.title)
            .put("characters", parsed.html.length)
            .toString()
    }

    /** A validated page ready to render. */
    data class Preview(val html: String, val title: String)

    sealed interface ParseResult {
        data class Ok(val preview: Preview) : ParseResult
        data class Error(val message: String) : ParseResult
    }

    companion object {
        const val NAME = "render_html"

        /**
         * Upper bound on page size. On-device context windows are a few
         * thousand tokens, so a real page is far below this; the cap guards
         * the UI, Room and history replay against a runaway generation.
         */
        const val MAX_HTML_CHARS = 100_000

        /** Used when the model omits a title. The UI substitutes a localized label. */
        const val DEFAULT_TITLE = ""

        private const val MAX_TITLE_CHARS = 80

        /**
         * Parse and validate tool arguments. Shared by [execute] and the chat UI
         * so both agree on exactly what gets rendered.
         */
        fun parse(arguments: String): ParseResult {
            val args = try {
                JSONObject(arguments)
            } catch (_: JSONException) {
                return ParseResult.Error("Arguments must be a JSON object with an \"html\" string.")
            }
            val rawHtml = args.optString("html", "")
            val html = normalizeHtml(rawHtml)
            if (html.isBlank()) {
                return ParseResult.Error("Missing \"html\": pass the complete HTML document as a string.")
            }
            if (html.length > MAX_HTML_CHARS) {
                return ParseResult.Error(
                    "HTML is too long (${html.length} characters, limit $MAX_HTML_CHARS). Make the page smaller."
                )
            }
            if (!html.contains('<')) {
                return ParseResult.Error("\"html\" does not contain any HTML tags.")
            }
            val title = args.optString("title", DEFAULT_TITLE)
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(MAX_TITLE_CHARS)
            return ParseResult.Ok(Preview(html = html, title = title))
        }

        /**
         * Small models often wrap the page in a Markdown fence even inside a
         * JSON argument (```html ... ```). Strip one surrounding fence; leave
         * everything else untouched.
         */
        fun normalizeHtml(raw: String): String {
            val trimmed = raw.trim()
            val fence = Regex("^```[A-Za-z0-9_-]*\\s*\\n(.*?)\\n?```$", RegexOption.DOT_MATCHES_ALL)
            return fence.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
        }

        private fun errorJson(message: String): String =
            JSONObject().put("error", message).toString()
    }
}
