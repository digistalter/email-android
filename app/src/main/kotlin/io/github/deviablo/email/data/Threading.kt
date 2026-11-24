// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import java.util.Locale

enum class SearchFilter { UNREAD, STARRED, ATTACHMENT, THIS_FOLDER }

/** A conversation as the list and the reader show it. */
data class Conversation(
    val key: String,
    /** Messages in the current folder (actions act on these). */
    val inFolder: List<StoredMessage>,
    /** All messages of the conversation, oldest first. */
    val messages: List<StoredMessage>,
) {
    val newest get() = messages.last()
    val unread get() = messages.any { !it.seen }
    val flagged get() = messages.any { it.flagged }
    val hasAttachment get() = messages.any { it.hasAttachment }
    val subject get() = newest.subject
    val dateMillis get() = newest.dateMillis
}

object Threading {
    private val prefix = Regex("""^\s*((re|aw|wg|fw|fwd|sv|vs|antw|r|rif|tr|ref)\s*(\[\d+])?\s*:\s*)+""", RegexOption.IGNORE_CASE)

    /** "Re: AW: Wohnung" → "wohnung"; used to match replies without References. */
    fun normalizeSubject(subject: String): String = subject.replace(prefix, "").trim().lowercase(Locale.ROOT)

    /** "Re: AW: Wohnung" → "Wohnung": the conversation's title, as in the Linux app. */
    fun displaySubject(subject: String): String = subject.replace(prefix, "").trim()

    fun isReply(subject: String): Boolean = prefix.containsMatchIn(subject)

    /**
     * Groups the messages of a folder into conversations. [related] holds the other messages of the
     * same threads (e.g. own replies in "Sent"). With [grouping] off, every message stands alone.
     */
    fun conversations(
        inFolder: List<StoredMessage>,
        related: List<StoredMessage>,
        grouping: Boolean,
        allowedFolders: Set<Long>,
    ): List<Conversation> {
        if (!grouping) return inFolder.map { Conversation("m:${it.id}", listOf(it), listOf(it)) }.sortedByDescending { it.dateMillis }
        val byKey = inFolder.groupBy { it.threadKey }
        val relatedByKey = related.filter { it.folderId in allowedFolders }.groupBy { it.threadKey }
        return byKey.map { (key, own) ->
            // The same mail can sit in two folders (e.g. Gmail labels): show it once.
            val all = (own + relatedByKey[key].orEmpty()).distinctBy { it.messageId ?: "row:${it.id}" }.sortedBy { it.dateMillis }
            Conversation(key, own, all)
        }.sortedByDescending { it.dateMillis }
    }

    /** "Lena, Ich" – first names (or address) of the senders, oldest first, "me" for own mails. */
    fun senders(c: Conversation, ownAddresses: Set<String>, me: String): String =
        c.messages.map { m ->
            if (m.fromAddress.lowercase() in ownAddresses) me
            else m.fromName.ifBlank { m.fromAddress }.let { name -> if (c.messages.size > 1) name.substringBefore(' ') else name }
        }.distinct().joinToString(", ")
}
