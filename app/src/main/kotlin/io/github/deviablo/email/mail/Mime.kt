// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail

import java.io.ByteArrayOutputStream
import java.net.URLDecoder
import java.nio.charset.Charset
import java.util.Base64
import java.util.Locale

/** Header block of a message or body part. Names are case-insensitive; values are unfolded. */
class Headers(val entries: List<Pair<String, String>>) {
    operator fun get(name: String): String? = entries.firstOrNull { it.first.equals(name, true) }?.second
    fun all(name: String): List<String> = entries.filter { it.first.equals(name, true) }.map { it.second }
}

/** "text/html; charset=utf-8" → type "text/html" plus parameters (RFC 2231 continuations decoded). */
data class HeaderValue(val value: String, val params: Map<String, String>) {
    companion object {
        fun parse(raw: String?): HeaderValue {
            if (raw == null) return HeaderValue("", emptyMap())
            val parts = splitParams(raw)
            val params = linkedMapOf<String, String>()
            val continued = sortedMapOf<String, MutableMap<Int, Pair<String, Boolean>>>()
            for (p in parts.drop(1)) {
                val eq = p.indexOf('=').takeIf { it > 0 } ?: continue
                var key = p.substring(0, eq).trim().lowercase(Locale.ROOT)
                val value = p.substring(eq + 1).trim().removeSurrounding("\"").replace("\\\"", "\"")
                val encoded = key.endsWith("*")
                if (encoded) key = key.removeSuffix("*")
                val star = key.lastIndexOf('*')
                if (star > 0 && key.substring(star + 1).toIntOrNull() != null) {
                    continued.getOrPut(key.substring(0, star)) { mutableMapOf() }[key.substring(star + 1).toInt()] = value to encoded
                } else {
                    params[key] = if (encoded) decode2231(value, null) else MimeWords.decode(value)
                }
            }
            for ((key, pieces) in continued) {
                var charset: String? = null
                val sb = StringBuilder()
                pieces.toSortedMap().forEach { (i, pair) ->
                    val (v, enc) = pair
                    sb.append(if (enc) decode2231(v, charset).also { if (i == 0) charset = v.substringBefore('\'', "") } else v)
                }
                params[key] = sb.toString()
            }
            return HeaderValue(parts.first().trim().lowercase(Locale.ROOT), params)
        }

        private fun splitParams(raw: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            var quoted = false
            for (c in raw) {
                when {
                    c == '"' -> { quoted = !quoted; sb.append(c) }
                    c == ';' && !quoted -> { out += sb.toString(); sb.clear() }
                    else -> sb.append(c)
                }
            }
            out += sb.toString()
            return out
        }

        /** RFC 2231: charset'lang'%XX… ; continuation pieces after the first carry no charset. */
        private fun decode2231(value: String, inheritedCharset: String?): String {
            val pieces = value.split('\'')
            val (charset, text) = if (pieces.size >= 3) pieces[0] to pieces.drop(2).joinToString("'") else (inheritedCharset ?: "UTF-8") to value
            return runCatching { URLDecoder.decode(text.replace("+", "%2B"), charset.ifEmpty { "UTF-8" }) }.getOrDefault(text)
        }
    }
}

class MimePart(val headers: Headers, private val rawBody: ByteArray, val children: List<MimePart>, val path: String) {
    val contentType: HeaderValue = HeaderValue.parse(headers["Content-Type"] ?: "text/plain")
    val mimeType: String get() = contentType.value.ifEmpty { "text/plain" }
    val disposition: HeaderValue = HeaderValue.parse(headers["Content-Disposition"])
    val contentId: String? = headers["Content-ID"]?.trim()?.removeSurrounding("<", ">")
    val filename: String? = disposition.params["filename"] ?: contentType.params["name"]
    val isMultipart get() = mimeType.startsWith("multipart/")
    val isAttachment get() = disposition.value == "attachment" || (filename != null && !mimeType.startsWith("text/") && contentId == null)

    /** Body after Content-Transfer-Encoding is undone. */
    fun bytes(): ByteArray = when (headers["Content-Transfer-Encoding"]?.trim()?.lowercase(Locale.ROOT)) {
        "base64" -> runCatching { Base64.getMimeDecoder().decode(rawBody.filter { !it.toInt().toChar().isWhitespace() }.toByteArray()) }.getOrDefault(ByteArray(0))
        "quoted-printable" -> Mime.decodeQuotedPrintable(rawBody)
        else -> rawBody
    }

    fun text(): String {
        val cs = contentType.params["charset"]?.let { runCatching { Charset.forName(Mime.charsetAlias(it)) }.getOrNull() } ?: Charsets.UTF_8
        return String(bytes(), cs)
    }

    fun walk(): Sequence<MimePart> = sequence {
        yield(this@MimePart)
        children.forEach { yieldAll(it.walk()) }
    }
}

data class Attachment(val path: String, val filename: String, val mimeType: String, val size: Int)

/** What the reader needs from a message. */
class ParsedMessage(val root: MimePart) {
    val headers get() = root.headers
    val html: String? = pickBody("text/html")
    val text: String? = pickBody("text/plain")
    val attachments: List<Attachment> = root.walk().filter { !it.isMultipart && it.isAttachment }
        .map { Attachment(it.path, it.filename ?: "attachment", it.mimeType, it.bytes().size) }.toList()

    fun part(path: String): MimePart? = root.walk().firstOrNull { it.path == path }
    fun inlineImage(cid: String): MimePart? = root.walk().firstOrNull { it.contentId.equals(cid, true) }

    private fun pickBody(type: String): String? {
        // In multipart/alternative the last matching part is the richest; skip attachments.
        return root.walk().filter { it.mimeType == type && !it.isAttachment }.lastOrNull()?.text()
    }
}

object Mime {
    fun parse(raw: ByteArray): ParsedMessage = ParsedMessage(parsePart(raw, "1", top = true))

    fun parseHeaders(raw: ByteArray): Headers = Headers(splitHeaderBlock(raw).first)

    private fun parsePart(raw: ByteArray, path: String, top: Boolean = false): MimePart {
        val (headerList, body) = splitHeaderBlock(raw)
        val headers = Headers(headerList)
        val ct = HeaderValue.parse(headers["Content-Type"] ?: "text/plain")
        val children = when {
            ct.value.startsWith("multipart/") -> {
                val boundary = ct.params["boundary"]
                if (boundary == null) emptyList()
                else splitMultipart(body, boundary).mapIndexed { i, b -> parsePart(b, if (top) "${i + 1}" else "$path.${i + 1}") }
            }
            ct.value == "message/rfc822" -> listOf(parsePart(body, "$path.1"))
            else -> emptyList()
        }
        return MimePart(headers, body, children, if (top && children.isEmpty()) "1" else path)
    }

    private fun splitHeaderBlock(raw: ByteArray): Pair<List<Pair<String, String>>, ByteArray> {
        var end = -1
        var bodyStart = raw.size
        var i = 0
        while (i < raw.size) {
            if (raw[i] == '\n'.code.toByte()) {
                if (i + 1 < raw.size && raw[i + 1] == '\n'.code.toByte()) { end = i; bodyStart = i + 2; break }
                if (i + 2 < raw.size && raw[i + 1] == '\r'.code.toByte() && raw[i + 2] == '\n'.code.toByte()) { end = i; bodyStart = i + 3; break }
            }
            i++
        }
        if (end < 0) end = raw.size
        // Headers are ASCII in theory; UTF-8 (RFC 6532) in practice, Latin-1 as last resort.
        val headerText = String(raw, 0, end, Charsets.UTF_8).let { if ('�' in it) String(raw, 0, end, Charsets.ISO_8859_1) else it }
        val list = mutableListOf<Pair<String, String>>()
        for (line in headerText.split('\n').map { it.trimEnd('\r') }) {
            if (line.isEmpty()) continue
            if ((line[0] == ' ' || line[0] == '\t') && list.isNotEmpty()) {
                val (k, v) = list.removeAt(list.lastIndex)
                list += k to (v + " " + line.trim())
            } else {
                val colon = line.indexOf(':').takeIf { it > 0 } ?: continue
                list += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
            }
        }
        return list to raw.copyOfRange(minOf(bodyStart, raw.size), raw.size)
    }

    private fun splitMultipart(body: ByteArray, boundary: String): List<ByteArray> {
        val text = String(body, Charsets.ISO_8859_1) // 1:1 byte mapping keeps binary parts intact
        val delimiter = "--$boundary"
        val parts = mutableListOf<ByteArray>()
        var pos = text.indexOf(delimiter)
        while (pos >= 0) {
            var start = pos + delimiter.length
            if (text.startsWith("--", start)) break
            start = text.indexOf('\n', start).let { if (it < 0) return parts else it + 1 }
            var next = text.indexOf("\n$delimiter", start)
            if (next < 0) next = text.length
            var end = next
            if (end > start && text[end - 1] == '\r') end--
            parts += text.substring(start, end).toByteArray(Charsets.ISO_8859_1)
            pos = if (next < text.length) next + 1 else -1
        }
        return parts
    }

    fun decodeQuotedPrintable(input: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(input.size)
        var i = 0
        while (i < input.size) {
            val b = input[i].toInt()
            if (b == '='.code) {
                if (i + 1 < input.size && (input[i + 1] == '\n'.code.toByte())) { i += 2; continue }
                if (i + 2 < input.size && input[i + 1] == '\r'.code.toByte() && input[i + 2] == '\n'.code.toByte()) { i += 3; continue }
                if (i + 2 < input.size) {
                    val hex = String(byteArrayOf(input[i + 1], input[i + 2]), Charsets.US_ASCII).toIntOrNull(16)
                    if (hex != null) { out.write(hex); i += 3; continue }
                }
            }
            out.write(b)
            i++
        }
        return out.toByteArray()
    }

    /** Common mislabels: ks_c_5601-1987 is really windows-949, "utf8" is UTF-8, ascii is a subset of Latin-1. */
    fun charsetAlias(name: String): String = when (name.trim().lowercase(Locale.ROOT)) {
        "utf8" -> "UTF-8"
        "ks_c_5601-1987" -> "x-windows-949"
        "us-ascii", "ascii", "iso-8859-1", "latin1" -> "windows-1252"
        else -> name.trim()
    }
}
