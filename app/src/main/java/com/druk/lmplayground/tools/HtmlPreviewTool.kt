package com.druk.lmplayground.tools

import com.druk.llamacpp.tools.Tool
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * `render_html` — lets the model show the user a live, interactive HTML
 * preview inside the chat.
 *
 * The page is split into `html`, `css` and `javascript` fields rather than one
 * whole document: small on-device models are far better at filling three
 * short fields than at producing a complete, correctly escaped document inside
 * a single JSON string, and it guarantees styles land in a <style> block.
 *
 * The preview has internet access, so pages may pull libraries (Tailwind,
 * Chart.js...), fonts and images from CDNs.
 *
 * The tool itself only validates and acknowledges. The chat UI renders the
 * page (see `conversation/HtmlPreview.kt`) from the tool call's persisted
 * arguments via [parse], so previews survive reopening a conversation.
 */
class HtmlPreviewTool : Tool {
    override val name = NAME

    override val description =
        "Show the user a live, interactive web page inside the chat. " +
            "Call this whenever the user asks for a web page, website, UI, form, calculator, widget, chart, animation or game. " +
            "Put the markup in \"html\", the styles in \"css\" and the code in \"javascript\"; the app builds the page from them. " +
            "Write normal HTML, CSS and JavaScript: no markdown code fences, and escape quotes and newlines only once, like any JSON string. " +
            "The preview is online: you may load libraries from a CDN with full https URLs " +
            "(e.g. <script src=\"https://cdn.tailwindcss.com\"></script> or https://cdn.jsdelivr.net/npm/chart.js), " +
            "web fonts, images by URL and public APIs with fetch(). Put those <script>/<link> tags in \"html\". " +
            "For simple pages, plain CSS in \"css\" is fastest. " +
            "After the call, answer with one short sentence; do not repeat the code."

    override val parametersSchema = JSONObject()
        .put("type", "object")
        .put(
            "properties",
            JSONObject()
                .put(
                    "title",
                    JSONObject()
                        .put("type", "string")
                        .put("description", "Short title shown above the preview, e.g. \"Tip calculator\"."),
                )
                .put(
                    "html",
                    JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "Body markup, e.g. \"<h1>Tip calculator</h1><input id=\\\"bill\\\" type=\\\"number\\\"><p id=\\\"out\\\"></p>\". " +
                                "A complete <!DOCTYPE html> document is also accepted.",
                        ),
                )
                .put(
                    "css",
                    JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "Styles for the page, e.g. \"body{font-family:sans-serif;padding:16px} h1{color:#2563eb}\".",
                        ),
                )
                .put(
                    "javascript",
                    JSONObject()
                        .put("type", "string")
                        .put(
                            "description",
                            "Script that runs after the page loads, e.g. " +
                                "\"document.getElementById('bill').oninput = e => out.textContent = (e.target.value * 0.15).toFixed(2);\".",
                        ),
                ),
        )
        .put("required", JSONArray().put("html"))
        .toString()

    override fun execute(arguments: String): String {
        val preview = when (val r = parse(arguments)) {
            is ParseResult.Ok -> r.preview
            is ParseResult.Error -> return JSONObject().put("error", r.message).toString()
        }
        return JSONObject()
            .put("status", "rendered")
            .put("title", preview.title)
            .put("note", "The user can see and use the page now. Reply with one short sentence; do not repeat the code.")
            .toString()
    }

    /** A validated page ready to render. [html] is the complete assembled document. */
    data class Preview(val html: String, val title: String)

    sealed interface ParseResult {
        data class Ok(val preview: Preview) : ParseResult
        data class Error(val message: String) : ParseResult
    }

    companion object {
        const val NAME = "render_html"

        /**
         * Upper bound on the assembled page. On-device context windows are a
         * few thousand tokens, so a real page is far below this; the cap guards
         * the UI, Room and history replay against a runaway generation.
         */
        const val MAX_HTML_CHARS = 100_000

        private const val MAX_TITLE_CHARS = 80

        /**
         * Applied before the model's own CSS, so anything the model writes wins.
         * Only used when the model sent a fragment rather than a full document;
         * it keeps a bare fragment readable on a phone-sized preview.
         */
        private const val BASE_CSS =
            "*,*::before,*::after{box-sizing:border-box}" +
                "html{-webkit-text-size-adjust:100%}" +
                "body{margin:0;padding:16px;font-family:system-ui,-apple-system,Roboto,sans-serif;line-height:1.5;color:#1f2937;background:#fff}" +
                "img,svg,canvas,video{max-width:100%}" +
                "button,input,select,textarea{font:inherit}"

        /**
         * Parse, repair and assemble tool arguments. Shared by [execute] and the
         * chat UI so both agree on exactly what gets rendered.
         */
        fun parse(arguments: String): ParseResult {
            val args = try {
                JSONObject(arguments)
            } catch (_: JSONException) {
                return ParseResult.Error(
                    "Arguments must be a JSON object, e.g. {\"html\": \"<h1>Hi</h1>\", \"css\": \"h1{color:red}\"}."
                )
            }
            val html = cleanField(args.optString("html", ""))
            val css = cleanField(args.optString("css", ""))
            val js = cleanField(args.optString("javascript", args.optString("js", "")))
            if (html.isBlank()) {
                return ParseResult.Error("Missing \"html\": put the page's markup in the \"html\" field.")
            }
            if (!html.contains('<')) {
                return ParseResult.Error("\"html\" must contain HTML tags, e.g. \"<h1>Hello</h1>\".")
            }
            val document = assemble(html, css, js)
            if (document.length > MAX_HTML_CHARS) {
                return ParseResult.Error(
                    "The page is too long (${document.length} characters, limit $MAX_HTML_CHARS). Make it smaller."
                )
            }
            val title = args.optString("title", "")
                .trim()
                .replace(Regex("\\s+"), " ")
                .take(MAX_TITLE_CHARS)
            return ParseResult.Ok(Preview(html = document, title = title))
        }

        /** Strip a surrounding markdown fence and undo double JSON escaping. */
        fun cleanField(raw: String): String = repairOverEscaping(stripFence(raw))

        /**
         * Small models often wrap a field in a Markdown fence even inside a
         * JSON argument (```html ... ```). Strip one surrounding fence; leave
         * everything else untouched.
         */
        fun stripFence(raw: String): String {
            val trimmed = raw.trim()
            val fence = Regex("^```[A-Za-z0-9_-]*\\s*\\n(.*?)\\n?```$", RegexOption.DOT_MATCHES_ALL)
            return fence.matchEntire(trimmed)?.groupValues?.get(1)?.trim() ?: trimmed
        }

        private val SCRIPT_BODY = Regex("(<script\\b[^>]*>)(.*?)(</script\\s*>)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

        /**
         * Undo the classic small-model mistake of JSON-escaping the page twice.
         * The engine's JSON parser then hands us `<div class=\"card\">\n<p>`
         * with literal backslashes. HTML still mostly renders, but every
         * `class=\"x\"` stops matching its selector and `\n` inside <style> is
         * a CSS escape that corrupts the next selector — so all styling is
         * silently lost.
         *
         * Two cases, chosen so correct pages are never touched:
         * - Fully double-escaped: no real newlines at all, but escape sequences
         *   in the markup outside <script>. Decode the whole string once.
         * - Escaped attribute quotes only (`=\"`) outside <script>, which is
         *   never valid HTML: fix quotes there, leaving script bodies alone
         *   (JS legitimately contains `"<a class=\"x\">"`).
         */
        fun repairOverEscaping(text: String): String {
            if (!text.contains('\\')) return text
            val outsideScripts = SCRIPT_BODY.replace(text) { it.groupValues[1] + it.groupValues[3] }
            val hasEscapesOutside = outsideScripts.contains("\\n") ||
                outsideScripts.contains("\\\"") ||
                outsideScripts.contains("\\t")
            if (!text.contains('\n') && hasEscapesOutside) {
                return unescapeJsonString(text)
            }
            if (outsideScripts.contains("=\\\"")) {
                val sb = StringBuilder()
                var cursor = 0
                for (m in SCRIPT_BODY.findAll(text)) {
                    sb.append(fixQuotes(text.substring(cursor, m.range.first)))
                    sb.append(m.value)
                    cursor = m.range.last + 1
                }
                sb.append(fixQuotes(text.substring(cursor)))
                return sb.toString()
            }
            return text
        }

        private fun fixQuotes(s: String): String = s.replace("\\\"", "\"").replace("\\/", "/")

        /** Decode one level of JSON string escaping. Unknown escapes are kept as-is. */
        fun unescapeJsonString(s: String): String {
            val out = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c != '\\' || i + 1 >= s.length) {
                    out.append(c); i++; continue
                }
                when (val n = s[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'b' -> { out.append('\b'); i += 2 }
                    'f' -> { out.append('\u000C'); i += 2 }
                    '"', '\'', '/', '\\' -> { out.append(n); i += 2 }
                    'u' -> {
                        val hex = s.substring(i + 2, minOf(i + 6, s.length))
                        val code = if (hex.length == 4) hex.toIntOrNull(16) else null
                        if (code != null) {
                            out.append(code.toChar()); i += 6
                        } else {
                            out.append(c); i++
                        }
                    }
                    else -> { out.append(c); i++ }
                }
            }
            return out.toString()
        }

        /**
         * Build the final document. A full document from the model gets the
         * extra CSS / JS spliced in; a fragment is wrapped in a proper page
         * with a charset, a mobile viewport and [BASE_CSS].
         */
        fun assemble(html: String, css: String, js: String): String {
            val styleTag = if (css.isNotBlank()) "<style>\n$css\n</style>\n" else ""
            val scriptTag = if (js.isNotBlank()) "<script>\n$js\n</script>\n" else ""
            val isFullDocument = Regex("<html[\\s>]|<!doctype\\s+html", RegexOption.IGNORE_CASE).containsMatchIn(html)
            if (!isFullDocument) {
                return buildString {
                    append("<!DOCTYPE html>\n<html>\n<head>\n")
                    append("<meta charset=\"utf-8\">\n")
                    append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                    append("<style>").append(BASE_CSS).append("</style>\n")
                    append(styleTag)
                    append("</head>\n<body>\n")
                    append(html).append('\n')
                    append(scriptTag)
                    append("</body>\n</html>\n")
                }
            }
            var doc = html
            if (!Regex("<meta[^>]+charset", RegexOption.IGNORE_CASE).containsMatchIn(doc)) {
                doc = insertAfterOpenTag(doc, "head", "<meta charset=\"utf-8\">\n")
            }
            if (styleTag.isNotEmpty()) {
                doc = insertBefore(doc, "</head>", styleTag) ?: insertAfterOpenTag(doc, "head", styleTag)
            }
            if (scriptTag.isNotEmpty()) {
                doc = insertBefore(doc, "</body>", scriptTag)
                    ?: insertBefore(doc, "</html>", scriptTag)
                    ?: (doc + "\n" + scriptTag)
            }
            return doc
        }

        /** Insert [content] before the last [closingTag] (case-insensitive), or null if absent. */
        private fun insertBefore(doc: String, closingTag: String, content: String): String? {
            val idx = doc.lastIndexOf(closingTag, ignoreCase = true)
            if (idx < 0) return null
            return doc.substring(0, idx) + content + doc.substring(idx)
        }

        /** Insert [content] right after `<tag ...>`; creates <head> after <html> if needed. */
        private fun insertAfterOpenTag(doc: String, tag: String, content: String): String {
            Regex("<$tag(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(doc)?.let {
                return doc.substring(0, it.range.last + 1) + "\n" + content + doc.substring(it.range.last + 1)
            }
            Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE).find(doc)?.let {
                return doc.substring(0, it.range.last + 1) + "\n<head>\n" + content + "</head>" +
                    doc.substring(it.range.last + 1)
            }
            return content + doc
        }

        private val HTML_FENCE = Regex(
            "```[ \\t]*(html|htm|xhtml|css|js|javascript)[ \\t]*\\r?\\n(.*?)```",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )

        /**
         * Fallback for models that write the page as Markdown code blocks
         * instead of calling the tool (or that can't call tools at all).
         * One ```html block plus any ```css / ```js blocks become one page;
         * several ```html blocks each become their own page.
         */
        fun previewsFromMarkdown(text: String): List<Preview> {
            val html = mutableListOf<String>()
            val css = StringBuilder()
            val js = StringBuilder()
            for (m in HTML_FENCE.findAll(text)) {
                val body = m.groupValues[2].trim()
                if (body.isEmpty()) continue
                when (m.groupValues[1].lowercase()) {
                    "css" -> css.append(body).append('\n')
                    "js", "javascript" -> js.append(body).append('\n')
                    else -> if (body.contains('<')) html += body
                }
            }
            return when (html.size) {
                0 -> emptyList()
                1 -> listOf(Preview(assemble(html[0], css.toString(), js.toString()), title = ""))
                else -> html.map { Preview(assemble(it, "", ""), title = "") }
            }.filter { it.html.length <= MAX_HTML_CHARS }
        }
    }
}
