package com.druk.lmplayground.conversation

/**
 * Finds HTML "artifacts" in an assistant reply: the web pages a model writes
 * as Markdown code blocks (```html ... ```). The chat shows each one as an
 * artifact card with a Live preview button (see HtmlArtifactCard.kt) instead
 * of plain code text — the same flow as the big hosted assistants: the model
 * writes the page once, the user taps to run it.
 *
 * Pure Kotlin with no Android dependencies, so it is unit-tested on the JVM.
 */
object HtmlArtifacts {

    sealed interface Segment {
        /** Ordinary reply text (Markdown, including non-HTML code blocks). */
        data class Text(val text: String) : Segment

        /**
         * An HTML code block. [ordinal] counts artifacts within the reply and
         * is stable while the reply streams (new text only appends).
         * [complete] is false while the closing fence hasn't arrived yet.
         */
        data class Artifact(
            val code: String,
            val ordinal: Int,
            val complete: Boolean,
        ) : Segment
    }

    private val HTML_LANGS = setOf("html", "htm", "xhtml")
    private val CSS_LANGS = setOf("css")
    private val JS_LANGS = setOf("js", "javascript", "mjs")

    private val OPEN_FENCE = Regex("^ {0,3}(`{3,}|~{3,})[ \\t]*([^`\\s]*)[^`]*$")
    private val FULL_DOCUMENT = Regex("^\\s*(<!doctype\\s+html|<html[\\s>])", RegexOption.IGNORE_CASE)

    /** Closing fence for [fence]: same character (` and ~ aren't regex-special), at least as long. */
    private fun closingFence(fence: String): Regex =
        Regex("^ {0,3}${fence[0]}{${fence.length},}[ \\t]*$")

    /**
     * Split a reply into text and artifact segments, following CommonMark
     * fence rules (up to 3 spaces of indent, ``` or ~~~, the closing fence at
     * least as long as the opening one). An unclosed HTML fence at the end —
     * the reply is still streaming — becomes an incomplete artifact.
     *
     * A fence without a language counts as HTML when its content is a full
     * document (starts with <!DOCTYPE html> or <html>), since some models
     * leave the language off.
     */
    fun split(text: String): List<Segment> {
        if (!text.contains("```") && !text.contains("~~~")) return listOf(Segment.Text(text))
        val lines = text.split('\n')
        val segments = mutableListOf<Segment>()
        val textBuf = StringBuilder()
        var ordinal = 0

        fun flushText() {
            if (textBuf.isNotBlank()) segments += Segment.Text(textBuf.toString().trim('\n'))
            textBuf.setLength(0)
        }

        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val open = OPEN_FENCE.matchEntire(line)
            if (open == null) {
                textBuf.append(line)
                if (i < lines.size - 1) textBuf.append('\n')
                i++
                continue
            }
            val fence = open.groupValues[1]
            val lang = open.groupValues[2].lowercase()
            // Find the closing fence: same character, at least as long.
            val close = closingFence(fence)
            var j = i + 1
            while (j < lines.size && !close.matches(lines[j])) j++
            val closed = j < lines.size
            val body = lines.subList(i + 1, minOf(j, lines.size)).joinToString("\n")

            val isHtml = lang in HTML_LANGS || (lang.isEmpty() && FULL_DOCUMENT.containsMatchIn(body))
            if (isHtml) {
                flushText()
                segments += Segment.Artifact(code = body, ordinal = ordinal++, complete = closed)
            } else {
                // Not ours: keep the whole block, fences included, as text so
                // the Markdown renderer shows it as an ordinary code block.
                val end = if (closed) j else lines.size - 1
                for (k in i..end) {
                    textBuf.append(lines[k])
                    if (k < lines.size - 1) textBuf.append('\n')
                }
            }
            i = if (closed) j + 1 else lines.size
        }
        flushText()
        return segments
    }

    /**
     * The page to run for [artifact]. When a reply holds exactly one HTML
     * artifact plus separate ```css / ```js blocks (a common way models split
     * a page), those are folded into the preview so it works as intended.
     */
    fun previewDocument(replyText: String, artifact: Segment.Artifact): String {
        val segments = split(replyText)
        val artifacts = segments.filterIsInstance<Segment.Artifact>()
        if (artifacts.size != 1) return buildDocument(artifact.code)
        val css = StringBuilder()
        val js = StringBuilder()
        for (block in otherCodeBlocks(replyText)) {
            when (block.first) {
                in CSS_LANGS -> css.append(block.second).append('\n')
                in JS_LANGS -> js.append(block.second).append('\n')
            }
        }
        return buildDocument(artifact.code, css.toString(), js.toString())
    }

    /** (language, body) for every closed, non-HTML fenced block in [text]. */
    private fun otherCodeBlocks(text: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        val lines = text.split('\n')
        var i = 0
        while (i < lines.size) {
            val open = OPEN_FENCE.matchEntire(lines[i])
            if (open == null) { i++; continue }
            val fence = open.groupValues[1]
            val lang = open.groupValues[2].lowercase()
            val close = closingFence(fence)
            var j = i + 1
            while (j < lines.size && !close.matches(lines[j])) j++
            if (j >= lines.size) break
            if (lang !in HTML_LANGS) out += lang to lines.subList(i + 1, j).joinToString("\n")
            i = j + 1
        }
        return out
    }

    /**
     * Applied before the page's own CSS, so anything the model writes wins.
     * Only used for fragments (no <html>/<!DOCTYPE>), to keep a bare snippet
     * readable on a phone-sized preview.
     */
    private const val BASE_CSS =
        "*,*::before,*::after{box-sizing:border-box}" +
            "html{-webkit-text-size-adjust:100%}" +
            "body{margin:0;padding:16px;font-family:system-ui,-apple-system,Roboto,sans-serif;line-height:1.5;color:#1f2937;background:#fff}" +
            "img,svg,canvas,video{max-width:100%}" +
            "button,input,select,textarea{font:inherit}"

    /**
     * A complete document for the WebView. A full document gets [css] / [js]
     * spliced in (and a charset if missing); a fragment is wrapped in a page
     * with a charset, a mobile viewport and [BASE_CSS].
     */
    fun buildDocument(html: String, css: String = "", js: String = ""): String {
        val styleTag = if (css.isNotBlank()) "<style>\n${css.trim()}\n</style>\n" else ""
        val scriptTag = if (js.isNotBlank()) "<script>\n${js.trim()}\n</script>\n" else ""
        val isFullDocument = Regex("<html[\\s>]|<!doctype\\s+html", RegexOption.IGNORE_CASE).containsMatchIn(html)
        if (!isFullDocument) {
            return buildString {
                append("<!DOCTYPE html>\n<html>\n<head>\n")
                append("<meta charset=\"utf-8\">\n")
                append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                append("<style>").append(BASE_CSS).append("</style>\n")
                append(styleTag)
                append("</head>\n<body>\n")
                append(html.trim()).append('\n')
                append(scriptTag)
                append("</body>\n</html>\n")
            }
        }
        var doc = html
        if (!Regex("<meta[^>]+charset", RegexOption.IGNORE_CASE).containsMatchIn(doc)) {
            doc = insertAfterOpenTag(doc, "head", "<meta charset=\"utf-8\">\n")
        }
        if (!Regex("<meta[^>]+name\\s*=\\s*[\"']?viewport", RegexOption.IGNORE_CASE).containsMatchIn(doc)) {
            doc = insertAfterOpenTag(doc, "head", "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
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

    /**
     * Suggested file name for downloading a page, from its [title]:
     * "Tip & Split Calculator" -> "tip-split-calculator.html". Letters in any
     * script are kept; a blank or symbol-only title falls back to "page.html".
     */
    fun fileName(title: String): String {
        val slug = title.lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
            .trim('-')
            .take(50)
            .trimEnd('-')
        return (slug.ifEmpty { "page" }) + ".html"
    }

    private val TITLE = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val H1 = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val TAG = Regex("<[^>]+>")

    /** A short title for the card: the page's <title>, else its first <h1>, else "". */
    fun title(code: String): String {
        val raw = TITLE.find(code)?.groupValues?.get(1) ?: H1.find(code)?.groupValues?.get(1) ?: return ""
        return raw.replace(TAG, "")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(60)
    }
}
