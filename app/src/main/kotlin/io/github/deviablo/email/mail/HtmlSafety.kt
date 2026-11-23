// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.net.IDN
import java.net.URI

/**
 * Turns mail HTML into something safe to show: no scripts, no forms, no frames, no event handlers,
 * no data: links. Remote images are kept as data-src until the user allows them, so nothing is
 * loaded from the net by default. The WebView additionally runs without JavaScript and network.
 */
object HtmlSafety {
    private val safelist: Safelist = Safelist.relaxed()
        .addTags("center", "font", "hr", "span", "div", "table", "tbody", "thead", "tfoot", "tr", "td", "th", "colgroup", "col", "style")
        .addAttributes(":all", "style", "class", "id", "dir", "align", "valign", "bgcolor", "width", "height", "title", "lang")
        .addAttributes("table", "border", "cellpadding", "cellspacing", "background")
        .addAttributes("td", "colspan", "rowspan", "nowrap", "background")
        .addAttributes("th", "colspan", "rowspan", "nowrap")
        .addAttributes("font", "color", "face", "size")
        .addAttributes("img", "src", "alt", "border", "data-src")
        .addAttributes("a", "href", "name", "target")
        .addProtocols("img", "src", "cid", "http", "https")
        .addProtocols("a", "href", "http", "https", "mailto", "tel")
        .preserveRelativeLinks(false)

    private const val MAX_INLINE_BYTES = 8 * 1024 * 1024

    data class Result(val html: String, val blockedImages: Boolean)

    /**
     * [inline] resolves a Content-ID to (mime type, bytes). WebView never asks for cid: URLs, so
     * inline images are embedded as data: URLs instead.
     */
    fun prepare(
        html: String,
        allowRemoteImages: Boolean,
        darkMode: Boolean,
        inline: ((String) -> Pair<String, ByteArray>?)? = null,
    ): Result {
        val dirty = Jsoup.parse(html)
        dirty.select("script, iframe, frame, object, embed, form, input, button, link, meta, base").remove()
        val clean: Document = Cleaner(safelist).clean(dirty)
        var blocked = false
        for (img in clean.select("img[src]")) {
            val src = img.attr("src")
            if (src.startsWith("cid:", ignoreCase = true)) {
                val found = inline?.invoke(src.substring(4))?.takeIf { it.second.size <= MAX_INLINE_BYTES }
                if (found == null) img.removeAttr("src")
                else img.attr("src", "data:${found.first};base64," + java.util.Base64.getEncoder().encodeToString(found.second))
                continue
            }
            if (src.startsWith("http", ignoreCase = true) && !allowRemoteImages) {
                img.attr("data-src", src)
                img.removeAttr("src")
                img.attr("style", "display:none")
                blocked = true
            }
        }
        // CSS can load images too (background:url(...)).
        for (el in clean.select("[style]")) {
            val style = el.attr("style")
            if (!allowRemoteImages && style.contains("url(", ignoreCase = true)) {
                el.attr("style", style.replace(Regex("""url\([^)]*\)""", RegexOption.IGNORE_CASE), "none")); blocked = true
            }
        }
        for (style in clean.select("style")) {
            if (!allowRemoteImages && style.data().contains("url(", ignoreCase = true)) {
                style.text(style.data().replace(Regex("""url\([^)]*\)""", RegexOption.IGNORE_CASE), "none")); blocked = true
            }
        }
        clean.select("a[href]").forEach { it.attr("target", "_blank") }
        return Result(page(clean.body().html(), darkMode, hasOwnBackground(clean)), blocked)
    }

    /**
     * Mail HTML for the composer (quotes, forwards, drafts): cleaned like [prepare], inline
     * pictures as data: URLs so they are sent again. Remote pictures stay as they are; the editor
     * never loads them.
     */
    fun editorBody(html: String, inline: ((String) -> Pair<String, ByteArray>?)? = null): String {
        val dirty = Jsoup.parse(html)
        dirty.select("script, iframe, frame, object, embed, form, input, button, link, meta, base, title").remove()
        val clean: Document = Cleaner(safelist).clean(dirty)
        for (img in clean.select("img[src^=cid:]")) {
            val found = inline?.invoke(img.attr("src").substring(4))?.takeIf { it.second.size <= MAX_INLINE_BYTES }
            if (found == null) img.remove()
            else img.attr("src", "data:${found.first};base64," + java.util.Base64.getEncoder().encodeToString(found.second))
        }
        clean.outputSettings().prettyPrint(false)
        return clean.body().html()
    }

    /** Plain text mails: escaped, links made clickable, shown in the app's text colours. */
    fun fromText(text: String, darkMode: Boolean): String {
        val escaped = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val linked = Regex("""https?://[^\s<>"]+""").replace(escaped) { "<a href=\"${it.value}\">${it.value}</a>" }
        return page("<div style=\"white-space:pre-wrap;word-wrap:break-word\">$linked</div>", darkMode, plainText = true)
    }

    private fun hasOwnBackground(doc: Document): Boolean =
        doc.body().attr("bgcolor").isNotBlank() || doc.select("[style*=background]").isNotEmpty()

    /**
     * HTML mails are designed for white paper; in dark mode they get a white sheet unless they set a
     * background themselves (Linux app). Plain text uses the app's own colours.
     */
    private fun page(body: String, darkMode: Boolean, ownBackground: Boolean = false, plainText: Boolean = false): String {
        val (bg, fg, link) = when {
            plainText && darkMode -> Triple("transparent", "#e3e3e3", "#a8c7fa")
            plainText -> Triple("transparent", "#1f1f1f", "#0b57d0")
            darkMode && !ownBackground -> Triple("#ffffff", "#1f1f1f", "#0b57d0")
            else -> Triple("transparent", "inherit", "#0b57d0")
        }
        val sheet = if (darkMode && !plainText) "padding:12px;border-radius:12px;" else ""
        return """<!doctype html><html><head><meta charset="utf-8">
            <meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src cid: data: https: http:; style-src 'unsafe-inline';">
            <meta name="viewport" content="width=device-width, initial-scale=1">
            <style>html,body{margin:0;padding:0;background:transparent}
            body{font-family:sans-serif;font-size:15px;line-height:1.45;color:$fg;overflow-wrap:anywhere}
            .sheet{background:$bg;$sheet}
            img{max-width:100%;height:auto} table{max-width:100%} a{color:$link}
            blockquote{margin:8px 0;padding-left:10px;border-left:3px solid #c4c7c5;color:#5f6368}
            </style></head><body><div class="sheet">$body</div></body></html>"""
    }

    /** The real host of a link, with a warning when it hides behind user info or punycode (Linux app). */
    fun linkWarning(href: String): String? = runCatching {
        val uri = URI(href)
        val host = uri.host ?: return null
        when {
            uri.rawUserInfo != null -> host
            host.split('.').any { it.startsWith("xn--") } -> IDN.toUnicode(host) + " ($host)"
            else -> null
        }
    }.getOrNull()

    fun senderDomain(address: String): String = address.substringAfterLast('@').lowercase()

    @Suppress("unused")
    private fun Element.isEmptyImage() = tagName() == "img" && attr("src").isBlank()
}
