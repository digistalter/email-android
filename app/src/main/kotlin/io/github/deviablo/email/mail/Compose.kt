// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale

data class Mailbox(val name: String, val email: String) {
    /** "Lena Koch <lena@example.org>" or just the address. */
    fun display(): String = if (name.isBlank()) email else "$name <$email>"

    /** The name, else the part before @. */
    fun short(): String = name.ifBlank { email.substringBefore('@') }
}

class FileAttachment(val name: String, val mimeType: String, val data: ByteArray)

/** Everything the user is writing. [messageId] stays the same for every saved version and the sent mail. */
data class Draft(
    val messageId: String,
    val to: List<Mailbox> = emptyList(),
    val cc: List<Mailbox> = emptyList(),
    val bcc: List<Mailbox> = emptyList(),
    val subject: String = "",
    /** The inside of <body>; pictures as data: URIs. */
    val html: String = "",
    val inReplyTo: String? = null,
    val references: List<String> = emptyList(),
    val attachments: List<FileAttachment> = emptyList(),
) {
    /** Everyone who gets the message, for the SMTP envelope. */
    fun recipients(): List<String> = (to + cc + bcc).map { it.email }.distinctBy { it.lowercase() }
}

enum class ReplyKind { REPLY, REPLY_ALL, FORWARD }

/** What a reply or forward needs from the original message. */
data class Original(
    val from: List<Mailbox>,
    val replyTo: List<Mailbox>,
    val to: List<Mailbox>,
    val cc: List<Mailbox>,
    val bcc: List<Mailbox>,
    val subject: String,
    val messageId: String?,
    val inReplyTo: String?,
    val references: List<String>,
    val dateMillis: Long?,
    /** Safe body HTML (sanitised, inline pictures as data: URIs). */
    val html: String,
    val attachments: List<FileAttachment>,
)

/** Texts that go into replies and forwards, in the language of the app. */
class QuoteTexts(
    /** "On {date}, {name} wrote:" */
    val wrote: String,
    val forwarded: String,
    val from: String,
    val date: String,
    val subject: String,
    val to: String,
    val formatDate: (Long) -> String,
)

object Compose {
    private val random = SecureRandom()

    /** A random Message-ID in the sender's domain (without <>); it never contains the device name. */
    fun newMessageId(sender: String): String {
        val domain = sender.substringAfterLast('@', "").trim().ifEmpty { "localhost" }
        return "%016x%016x@%s".format(random.nextLong(), random.nextLong(), domain)
    }

    // --- addresses ---

    private val emailPattern = Regex("""^[^\s@<>(),;:"\[\]]+@[^\s@<>(),;:"\[\]]+\.[^\s@<>(),;:"\[\]]+$""")

    fun isValidAddress(email: String): Boolean = emailPattern.matches(email)

    /** "Lena Koch <lena@example.org>", "lena@example.org" or "\"Koch, Lena\" <lena@…>". */
    fun parseMailbox(input: String): Mailbox? {
        val s = input.trim()
        if (s.isEmpty()) return null
        val lt = s.lastIndexOf('<')
        return if (lt >= 0 && s.endsWith(">")) {
            val email = s.substring(lt + 1, s.length - 1).trim()
            val name = s.substring(0, lt).trim().removeSurrounding("\"").replace("\\\"", "\"").trim()
            if (isValidAddress(email)) Mailbox(MimeWords.decode(name), email) else null
        } else if (isValidAddress(s)) Mailbox("", s) else null
    }

    /** Splits at commas and semicolons outside quotes and angle brackets. Invalid parts are returned separately. */
    fun parseAddresses(input: String): Pair<List<Mailbox>, List<String>> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var angle = false
        for (c in input) {
            when {
                c == '"' -> { quoted = !quoted; current.append(c) }
                c == '<' && !quoted -> { angle = true; current.append(c) }
                c == '>' && !quoted -> { angle = false; current.append(c) }
                (c == ',' || c == ';' || c == '\n') && !quoted && !angle -> { parts += current.toString(); current.clear() }
                else -> current.append(c)
            }
        }
        parts += current.toString()
        val good = mutableListOf<Mailbox>()
        val bad = mutableListOf<String>()
        for (p in parts.map { it.trim() }.filter { it.isNotEmpty() }) {
            parseMailbox(p)?.let { good += it } ?: run { bad += p }
        }
        return good to bad
    }

    // --- replies ---

    fun replyRecipients(original: Original, kind: ReplyKind, own: String): Pair<List<Mailbox>, List<Mailbox>> {
        fun isOwn(m: Mailbox) = m.email.equals(own, ignoreCase = true)
        val fromMe = original.from.any(::isOwn)
        val to = when {
            fromMe -> original.to
            original.replyTo.isNotEmpty() -> original.replyTo
            else -> original.from
        }.toMutableList()
        var cc = emptyList<Mailbox>()
        if (kind == ReplyKind.REPLY_ALL) {
            if (!fromMe) to += original.to
            cc = original.cc
        }
        val seen = mutableSetOf<String>()
        val keep = { m: Mailbox -> !isOwn(m) && seen.add(m.email.lowercase()) }
        var finalTo = to.filter(keep)
        val finalCc = cc.filter(keep)
        // A note to oneself: answer oneself.
        if (finalTo.isEmpty() && fromMe) finalTo = original.from
        return finalTo to finalCc
    }

    fun replySubject(subject: String, kind: ReplyKind): String {
        val s = subject.trim()
        val (prefix, known) = if (kind == ReplyKind.FORWARD) "Fwd: " to listOf("fwd:", "fw:", "wg:") else "Re: " to listOf("re:", "aw:")
        return if (known.any { s.lowercase().startsWith(it) }) s else prefix + s
    }

    fun signatureBlock(signature: String): String =
        if (signature.isBlank()) "" else "<div class=\"signature\">-- <br>$signature</div>"

    fun newDraft(sender: String, signature: String) = Draft(
        messageId = newMessageId(sender),
        html = "<div><br></div><div><br></div>" + signatureBlock(signature),
    )

    /** A reply or forward of [original], with the quote below the signature (Linux app). */
    fun replyDraft(original: Original, kind: ReplyKind, own: String, signature: String, texts: QuoteTexts, messageId: String? = null): Draft {
        val references = (original.references + listOfNotNull(original.messageId)).distinct()
        val date = original.dateMillis?.let(texts.formatDate).orEmpty()
        val sender = original.from.firstOrNull()?.short().orEmpty()
        var draft = Draft(messageId = messageId ?: newMessageId(own), subject = replySubject(original.subject, kind), references = references)
        val quote = if (kind == ReplyKind.FORWARD) {
            draft = draft.copy(attachments = original.attachments)
            fun list(l: List<Mailbox>) = l.joinToString(", ") { it.display() }
            val header = StringBuilder("---------- ${escape(texts.forwarded)} ----------<br>")
            for ((label, value) in listOf(texts.from to list(original.from), texts.date to date, texts.subject to original.subject, texts.to to list(original.to))) {
                if (value.isNotEmpty()) header.append(escape(label)).append(": ").append(escape(value)).append("<br>")
            }
            "<div>$header</div><br><div>${original.html}</div>"
        } else {
            val (to, cc) = replyRecipients(original, kind, own)
            draft = draft.copy(to = to, cc = cc, inReplyTo = original.messageId)
            val line = texts.wrote.replace("{date}", date).replace("{name}", sender)
            "<div>${escape(line)}</div><blockquote type=\"cite\">${original.html}</blockquote>"
        }
        return draft.copy(html = "<div><br></div><div><br></div>" + signatureBlock(signature) + "<br>" + quote)
    }

    /** Reads a mail (a saved draft or an original) into the pieces the composer needs. */
    fun readOriginal(raw: ByteArray): Original {
        val parsed = Mime.parse(raw)
        val h = parsed.root.headers
        fun list(name: String) = parseAddresses(MimeWords.decode(h.all(name).joinToString(", "))).first
        fun ids(value: String?) = Regex("<[^<>\\s]+>").findAll(value.orEmpty()).map { it.value.removeSurrounding("<", ">") }.toList()
        val html = parsed.html?.let { HtmlSafety.editorBody(it) { cid -> parsed.inlineImage(cid)?.let { p -> p.mimeType to p.bytes() } } }
            ?: textToHtml(parsed.text.orEmpty())
        val attachments = parsed.attachments.mapNotNull { a -> parsed.part(a.path)?.let { FileAttachment(a.filename, a.mimeType, it.bytes()) } }
        return Original(
            from = list("From"), replyTo = list("Reply-To"), to = list("To"), cc = list("Cc"), bcc = list("Bcc"),
            subject = MimeWords.decode(h["Subject"]).replace(Regex("\\s+"), " ").trim(),
            messageId = ids(h["Message-ID"]).firstOrNull(),
            inReplyTo = ids(h["In-Reply-To"]).firstOrNull(),
            references = ids(h["References"]),
            dateMillis = h["Date"]?.let { parseDate(it) },
            html = html,
            attachments = attachments,
        )
    }

    /** A saved draft, ready to be edited again. */
    fun readDraft(raw: ByteArray, sender: String): Draft {
        val o = readOriginal(raw)
        return Draft(
            messageId = o.messageId ?: newMessageId(sender), to = o.to, cc = o.cc, bcc = o.bcc, subject = o.subject,
            html = o.html, inReplyTo = o.inReplyTo, references = o.references, attachments = o.attachments,
        )
    }

    private fun parseDate(value: String): Long? {
        val cleaned = value.replace(Regex("\\s*\\(.*\\)\\s*$"), "").trim()
        for (pattern in listOf("EEE, d MMM yyyy HH:mm:ss Z", "d MMM yyyy HH:mm:ss Z", "EEE, d MMM yyyy HH:mm Z")) {
            runCatching { return SimpleDateFormat(pattern, Locale.US).parse(cleaned)?.time }
        }
        return null
    }

    fun escape(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    fun textToHtml(text: String): String =
        text.split("\n").joinToString("") { line -> "<div>" + (if (line.isBlank()) "<br>" else escape(line.trimEnd('\r'))) + "</div>" }

    /** The plain-text version: paragraphs and breaks kept, lists get dashes, quotes "> ", links show their address. */
    fun htmlToText(html: String): String {
        val out = StringBuilder()
        var depth = 0
        val lists = ArrayDeque<IntArray?>() // null = bullet list, else running number
        fun lineStart() = out.isEmpty() || out.last() == '\n'
        fun newline() { out.append('\n') }
        fun block() { if (!lineStart()) newline() }
        fun prefix() { if (lineStart() && depth > 0) out.append("> ".repeat(depth)) }
        fun walk(node: Node) {
            when (node) {
                is TextNode -> {
                    val t = node.wholeText.replace(Regex("[\\s ]+"), " ")
                    if (t.isBlank() && lineStart()) return
                    prefix()
                    out.append(if (lineStart()) t.trimStart() else t)
                }
                is Element -> {
                    val tag = node.normalName()
                    when (tag) {
                        "style", "script", "head", "title" -> return
                        "br" -> { prefix(); newline(); return }
                        "img" -> { node.attr("alt").takeIf { it.isNotBlank() }?.let { prefix(); out.append("[$it]") }; return }
                    }
                    val isBlock = tag in setOf("div", "p", "blockquote", "ul", "ol", "li", "h1", "h2", "h3", "h4", "h5", "h6", "table", "tr", "pre", "hr")
                    if (isBlock) block()
                    when (tag) {
                        "blockquote" -> depth++
                        "ul" -> lists.addLast(null)
                        "ol" -> lists.addLast(intArrayOf(0))
                        "li" -> { prefix(); val l = lists.lastOrNull(); if (l != null) { l[0]++; out.append("${l[0]}. ") } else out.append("- ") }
                        "hr" -> { prefix(); out.append("----"); newline() }
                    }
                    node.childNodes().forEach(::walk)
                    when (tag) {
                        "blockquote" -> { block(); depth-- }
                        "ul", "ol" -> lists.removeLastOrNull()
                        "a" -> {
                            val href = node.attr("href")
                            if (href.isNotBlank() && !href.startsWith("mailto:") && href != node.text().trim()) out.append(" <").append(href).append(">")
                        }
                        "p" -> { block(); newline() }
                    }
                    if (isBlock) block()
                }
            }
        }
        Jsoup.parseBodyFragment(html).body().childNodes().forEach(::walk)
        // "> " on empty quoted lines becomes ">", and a quote does not end in an empty line.
        val lines = out.toString().lines().map { it.trimEnd() }.toMutableList()
        while (lines.isNotEmpty() && (lines.last().isEmpty() || lines.last().all { it == '>' })) lines.removeAt(lines.size - 1)
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim('\n') + "\n"
    }

    /** Replaces every data: picture with a cid: reference; the same picture used twice is attached once. */
    fun extractImages(html: String, messageId: String): Pair<String, List<Triple<String, String, ByteArray>>> {
        val doc = Jsoup.parseBodyFragment(html)
        val images = mutableListOf<Triple<String, String, ByteArray>>()
        for (img in doc.select("img[src^=data:]")) {
            val value = img.attr("src")
            val meta = value.substringAfter("data:").substringBefore(',')
            val mime = meta.removeSuffix(";base64").trim().lowercase()
            if (!meta.endsWith(";base64") || !mime.startsWith("image/")) { img.remove(); continue }
            val data = runCatching { Base64.getMimeDecoder().decode(value.substringAfter(',')) }.getOrNull()
            if (data == null) { img.remove(); continue }
            val existing = images.firstOrNull { it.third.contentEquals(data) }
            val cid = existing?.first ?: "part${images.size + 1}.$messageId".also { images += Triple(it, mime, data) }
            img.attr("src", "cid:$cid")
        }
        doc.outputSettings().prettyPrint(false)
        return doc.body().html() to images
    }

    /** Removes the Bcc header, which recipients must not see. */
    fun stripBcc(raw: ByteArray): ByteArray {
        val end = headerEnd(raw)
        val head = String(raw, 0, end, Charsets.ISO_8859_1)
        val out = StringBuilder()
        var skipping = false
        for (line in head.split(Regex("(?<=\n)"))) {
            if (line.isEmpty()) continue
            if (line[0] == ' ' || line[0] == '\t') { if (!skipping) out.append(line); continue }
            skipping = line.length >= 4 && line.substring(0, 4).equals("bcc:", ignoreCase = true)
            if (!skipping) out.append(line)
        }
        return out.toString().toByteArray(Charsets.ISO_8859_1) + raw.copyOfRange(end, raw.size)
    }

    private fun headerEnd(raw: ByteArray): Int {
        for (i in 0 until raw.size - 3) if (raw[i] == '\r'.code.toByte() && raw[i + 1] == '\n'.code.toByte() && raw[i + 2] == '\r'.code.toByte() && raw[i + 3] == '\n'.code.toByte()) return i + 2
        return raw.size
    }

    fun mimeTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"; "jpg", "jpeg" -> "image/jpeg"; "gif" -> "image/gif"; "webp" -> "image/webp"; "heic" -> "image/heic"
        "pdf" -> "application/pdf"; "txt" -> "text/plain"; "html", "htm" -> "text/html"; "csv" -> "text/csv"
        "zip" -> "application/zip"; "doc" -> "application/msword"; "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"; "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "odt" -> "application/vnd.oasis.opendocument.text"; "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "ics" -> "text/calendar"; "vcf" -> "text/vcard"; "mp3" -> "audio/mpeg"; "mp4" -> "video/mp4"
        else -> "application/octet-stream"
    }
}

/** Writes RFC 5322 / MIME messages. The Bcc header stays in (drafts, "Sent"); [Compose.stripBcc] removes it for sending. */
object MessageWriter {
    private val random = SecureRandom()

    fun build(from: Mailbox, draft: Draft, dateMillis: Long): ByteArray {
        val (html, images) = Compose.extractImages(draft.html, clean(draft.messageId, id = true))
        val text = Compose.htmlToText(html)
        val document = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head><body>$html</body></html>"

        val out = StringBuilder()
        fun header(name: String, value: String) { out.append(name).append(": ").append(value).append("\r\n") }
        header("From", mailbox(from))
        if (draft.to.isNotEmpty()) header("To", draft.to.joinToString(",\r\n ") { mailbox(it) })
        if (draft.cc.isNotEmpty()) header("Cc", draft.cc.joinToString(",\r\n ") { mailbox(it) })
        if (draft.bcc.isNotEmpty()) header("Bcc", draft.bcc.joinToString(",\r\n ") { mailbox(it) })
        header("Subject", encodeHeader(clean(draft.subject)))
        header("Date", SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).format(Date(dateMillis)))
        header("Message-ID", "<${clean(draft.messageId, id = true)}>")
        draft.inReplyTo?.let { header("In-Reply-To", "<${clean(it, id = true)}>") }
        if (draft.references.isNotEmpty()) header("References", draft.references.joinToString("\r\n ") { "<${clean(it, id = true)}>" })
        header("User-Agent", "E-Mail")
        header("MIME-Version", "1.0")

        val textPart = part("text/plain; charset=utf-8", "quoted-printable", quotedPrintable(text.toByteArray()))
        val htmlPart = part("text/html; charset=utf-8", "quoted-printable", quotedPrintable(document.toByteArray()))
        val htmlWithImages = if (images.isEmpty()) htmlPart else multipart("related", listOf(htmlPart) + images.map { (cid, mime, data) ->
            part(mime, "base64", base64(data), extra = "Content-ID: <$cid>\r\nContent-Disposition: inline\r\n")
        }, extraType = "; type=\"text/html\"")
        var body = multipart("alternative", listOf(textPart, htmlWithImages))
        if (draft.attachments.isNotEmpty()) {
            body = multipart("mixed", listOf(body) + draft.attachments.map { a ->
                val name = clean(a.name).replace('/', '_').replace('\\', '_').ifBlank { "attachment" }
                part(
                    "${clean(a.mimeType, id = true).ifBlank { "application/octet-stream" }}; name=${paramValue(name)}", "base64", base64(a.data),
                    extra = "Content-Disposition: attachment; ${filenameParam(name)}\r\n",
                )
            })
        }
        out.append(body)
        return out.toString().toByteArray(Charsets.US_ASCII)
    }

    /** One MIME entity as text: its headers, a blank line, the body. */
    private fun part(type: String, encoding: String, body: String, extra: String = "") =
        "Content-Type: $type\r\nContent-Transfer-Encoding: $encoding\r\n$extra\r\n$body"

    private fun multipart(sub: String, parts: List<String>, extraType: String = ""): String {
        val boundary = "=_" + "%016x".format(random.nextLong())
        val sb = StringBuilder("Content-Type: multipart/$sub; boundary=\"$boundary\"$extraType\r\n\r\n")
        for (p in parts) sb.append("--").append(boundary).append("\r\n").append(p).append(if (p.endsWith("\r\n")) "" else "\r\n")
        sb.append("--").append(boundary).append("--\r\n")
        return sb.toString()
    }

    /** Header text without control characters, so pasted text cannot add headers of its own (e.g. a hidden Bcc). */
    private fun clean(text: String, id: Boolean = false): String =
        if (id) text.filter { !it.isISOControl() && !it.isWhitespace() && it != '<' && it != '>' }
        else text.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim()

    private fun mailbox(m: Mailbox): String {
        val email = clean(m.email, id = true)
        val name = clean(m.name)
        if (name.isEmpty()) return email
        val shown = when {
            name.any { it.code > 126 } -> encodeHeader(name)
            name.all { it.isLetterOrDigit() || it in " !#$%&'*+-/=?^_`{|}~" } -> name
            else -> "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        }
        return "$shown <$email>"
    }

    /** RFC 2047 B-encoding in UTF-8, split into words of at most 75 characters without cutting characters. */
    fun encodeHeader(text: String): String {
        if (text.all { it.code in 32..126 } && text.length <= 900) return text
        val words = mutableListOf<String>()
        val chunk = StringBuilder()
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val s = String(Character.toChars(cp))
            val n = s.toByteArray().size
            if (bytes + n > 45) { words += chunk.toString(); chunk.clear(); bytes = 0 }
            chunk.append(s); bytes += n
            i += Character.charCount(cp)
        }
        if (chunk.isNotEmpty()) words += chunk.toString()
        return words.joinToString("\r\n ") { "=?UTF-8?B?" + Base64.getEncoder().encodeToString(it.toByteArray()) + "?=" }
    }

    private fun paramValue(name: String): String =
        if (name.all { it.code in 32..126 }) "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" else "\"" + encodeHeader(name).replace("\r\n ", " ") + "\""

    /** filename="…" for old programs plus filename*=UTF-8''… (RFC 2231) when the name is not plain ASCII. */
    private fun filenameParam(name: String): String {
        if (name.all { it.code in 32..126 }) return "filename=" + paramValue(name)
        val pct = name.toByteArray().joinToString("") { b ->
            val c = b.toInt() and 0xff
            if (c in 0x30..0x39 || c in 0x41..0x5a || c in 0x61..0x7a || c.toChar() in "-._~") c.toChar().toString() else "%%%02X".format(c)
        }
        return "filename=${paramValue(name)};\r\n filename*=UTF-8''$pct"
    }

    private fun base64(data: ByteArray): String =
        Base64.getMimeEncoder(76, "\r\n".toByteArray()).encodeToString(data) + "\r\n"

    /** Quoted-printable (RFC 2045); line breaks become CRLF, lines are kept under 76 characters. */
    fun quotedPrintable(data: ByteArray): String {
        val out = StringBuilder()
        val line = StringBuilder()
        fun flushSoft() { out.append(line).append("=\r\n"); line.clear() }
        var i = 0
        while (i < data.size) {
            val c = data[i].toInt() and 0xff
            if (c == '\r'.code && i + 1 < data.size && data[i + 1].toInt() == '\n'.code) { i++; continue }
            if (c == '\n'.code) {
                // Trailing white space must be encoded, it may be stripped on the way.
                if (line.isNotEmpty() && (line.last() == ' ' || line.last() == '\t')) {
                    val last = line.last(); line.setLength(line.length - 1); line.append("=%02X".format(last.code))
                }
                out.append(line).append("\r\n"); line.clear(); i++; continue
            }
            val enc = if ((c in 33..126 && c != '='.code) || c == ' '.code || c == '\t'.code) c.toChar().toString() else "=%02X".format(c)
            // A line starting with "." or "From " is safe too, but encoding the dot avoids trouble with careless relays.
            val piece = if (line.isEmpty() && c == '.'.code) "=2E" else enc
            if (line.length + piece.length > 75) flushSoft()
            line.append(piece)
            i++
        }
        if (line.isNotEmpty() && (line.last() == ' ' || line.last() == '\t')) {
            val last = line.last(); line.setLength(line.length - 1); line.append("=%02X".format(last.code))
        }
        out.append(line)
        if (!out.endsWith("\r\n")) out.append("\r\n")
        return out.toString()
    }
}
