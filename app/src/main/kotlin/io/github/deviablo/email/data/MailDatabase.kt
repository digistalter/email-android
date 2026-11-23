// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import io.github.deviablo.email.mail.imap.MessageHeader
import io.github.deviablo.email.mail.imap.Security

data class Account(
    val id: Long,
    val name: String,
    val displayName: String,
    val address: String,
    val imapHost: String,
    val imapPort: Int,
    val imapSecurity: Security,
    val username: String,
    val smtpHost: String = "",
    val smtpPort: Int = 465,
    val smtpSecurity: Security = Security.TLS,
    val smtpUsername: String = "",
    val signature: String = "",
    val notify: Boolean = true,
)

enum class FolderRole { INBOX, DRAFTS, SENT, ARCHIVE, SPAM, TRASH, OTHER }

data class Folder(val id: Long, val accountId: Long, val rawName: String, val name: String, val role: FolderRole, val unread: Int, val total: Int)

data class StoredMessage(
    val id: Long,
    val accountId: Long,
    val folderId: Long,
    val uid: Long,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val recipients: String,
    val dateMillis: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
    val messageId: String?,
    val threadKey: String,
)

data class PendingOp(
    val id: Long,
    val accountId: Long,
    val kind: String,
    val messageRowId: Long,
    val folderId: Long,
    val uid: Long,
    val targetFolderId: Long?,
    val flag: String?,
    val value: Boolean,
    val notBefore: Long,
)

/**
 * Local cache of accounts, folders and messages. The server stays the source of truth; local
 * changes wait in `ops` until they reached it (they survive restarts and dead spots).
 */
class MailDatabase(context: Context) : SQLiteOpenHelper(context, "mail.db", null, 2) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL, display_name TEXT NOT NULL, address TEXT NOT NULL,
                imap_host TEXT NOT NULL, imap_port INTEGER NOT NULL, imap_security TEXT NOT NULL, username TEXT NOT NULL,
                smtp_host TEXT NOT NULL DEFAULT '', smtp_port INTEGER NOT NULL DEFAULT 465, smtp_security TEXT NOT NULL DEFAULT 'TLS',
                smtp_username TEXT NOT NULL DEFAULT '',
                signature TEXT NOT NULL DEFAULT '', notify INTEGER NOT NULL DEFAULT 1,
                password BLOB NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                raw_name TEXT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL,
                uid_validity INTEGER, complete INTEGER NOT NULL DEFAULT 0,
                UNIQUE(account_id, raw_name))""",
        )
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
                uid INTEGER NOT NULL, subject TEXT NOT NULL,
                from_name TEXT NOT NULL, from_address TEXT NOT NULL, recipients TEXT NOT NULL DEFAULT '',
                date_ms INTEGER NOT NULL, seen INTEGER NOT NULL, flagged INTEGER NOT NULL, has_attachment INTEGER NOT NULL,
                message_id TEXT, in_reply_to TEXT, refs TEXT NOT NULL DEFAULT '',
                thread_key TEXT NOT NULL, first_seen_ms INTEGER NOT NULL, body_cached INTEGER NOT NULL DEFAULT 0,
                UNIQUE(folder_id, uid))""",
        )
        db.execSQL("CREATE INDEX messages_by_date ON messages(folder_id, date_ms DESC)")
        db.execSQL("CREATE INDEX messages_by_thread ON messages(account_id, thread_key)")
        db.execSQL("CREATE INDEX messages_by_msgid ON messages(account_id, message_id)")
        db.execSQL(
            """CREATE TABLE ops (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                kind TEXT NOT NULL, message_row INTEGER NOT NULL, folder_id INTEGER NOT NULL, uid INTEGER NOT NULL,
                target_folder_id INTEGER, flag TEXT, value INTEGER NOT NULL DEFAULT 0, not_before INTEGER NOT NULL)""",
        )
        db.execSQL("CREATE TABLE image_senders (account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, domain TEXT NOT NULL, PRIMARY KEY(account_id, domain))")
        db.execSQL("CREATE TABLE spam_senders (account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE, address TEXT NOT NULL, PRIMARY KEY(account_id, address))")
        db.execSQL("CREATE VIRTUAL TABLE search USING fts4(subject, sender, recipients, body, tokenize=unicode61)")
        db.execSQL(
            """CREATE TABLE outbox (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                kind TEXT NOT NULL, message_id TEXT NOT NULL, raw BLOB NOT NULL, recipients TEXT NOT NULL,
                replaces_draft_uid INTEGER, attempts INTEGER NOT NULL DEFAULT 0, next_try INTEGER NOT NULL DEFAULT 0, last_error TEXT)""",
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Only development builds ever had version 1; nothing worth migrating.
        listOf("outbox", "search", "spam_senders", "image_senders", "ops", "messages", "folders", "accounts").forEach { db.execSQL("DROP TABLE IF EXISTS $it") }
        onCreate(db)
    }

    // --- accounts ---

    private fun Account.values(encryptedPassword: ByteArray?) = ContentValues().apply {
        put("name", name); put("display_name", displayName); put("address", address)
        put("imap_host", imapHost); put("imap_port", imapPort); put("imap_security", imapSecurity.name); put("username", username)
        put("smtp_host", smtpHost); put("smtp_port", smtpPort); put("smtp_security", smtpSecurity.name); put("smtp_username", smtpUsername)
        put("signature", signature); put("notify", notify)
        if (encryptedPassword != null) put("password", encryptedPassword)
    }

    fun insertAccount(a: Account, encryptedPassword: ByteArray): Long = writableDatabase.insertOrThrow("accounts", null, a.values(encryptedPassword))

    fun updateAccount(a: Account, encryptedPassword: ByteArray? = null) {
        writableDatabase.update("accounts", a.values(encryptedPassword), "id = ?", arrayOf(a.id.toString()))
    }

    fun deleteAccount(id: Long) {
        writableDatabase.delete("search", "docid IN (SELECT id FROM messages WHERE account_id = ?)", arrayOf(id.toString()))
        writableDatabase.delete("accounts", "id = ?", arrayOf(id.toString()))
    }

    fun accounts(): List<Account> = readableDatabase.rawQuery(
        "SELECT id, name, display_name, address, imap_host, imap_port, imap_security, username, smtp_host, smtp_port, smtp_security, smtp_username, signature, notify FROM accounts ORDER BY id", null,
    ).use { c ->
        c.list {
            Account(getLong(0), getString(1), getString(2), getString(3), getString(4), getInt(5), Security.valueOf(getString(6)), getString(7),
                getString(8), getInt(9), Security.valueOf(getString(10)), getString(11), getString(12), getInt(13) != 0)
        }
    }

    fun encryptedPassword(accountId: Long): ByteArray? =
        readableDatabase.rawQuery("SELECT password FROM accounts WHERE id = ?", arrayOf(accountId.toString())).use { c ->
            if (c.moveToFirst()) c.getBlob(0) else null
        }

    // --- folders ---

    fun upsertFolder(accountId: Long, rawName: String, name: String, role: FolderRole): Long {
        val db = writableDatabase
        db.rawQuery("SELECT id FROM folders WHERE account_id = ? AND raw_name = ?", arrayOf(accountId.toString(), rawName)).use { c ->
            if (c.moveToFirst()) {
                val id = c.getLong(0)
                db.update("folders", ContentValues().apply { put("name", name); put("role", role.name) }, "id = ?", arrayOf(id.toString()))
                return id
            }
        }
        return db.insertOrThrow("folders", null, ContentValues().apply {
            put("account_id", accountId); put("raw_name", rawName); put("name", name); put("role", role.name)
        })
    }

    fun removeFoldersExcept(accountId: Long, keepIds: Collection<Long>) {
        if (keepIds.isEmpty()) return // never wipe everything because of one odd LIST answer
        writableDatabase.delete("search", "docid IN (SELECT id FROM messages WHERE account_id = ? AND folder_id NOT IN (${keepIds.joinToString(",")}))", arrayOf(accountId.toString()))
        writableDatabase.delete("folders", "account_id = ? AND id NOT IN (${keepIds.joinToString(",")})", arrayOf(accountId.toString()))
    }

    fun folders(accountId: Long): List<Folder> = readableDatabase.rawQuery(
        """SELECT f.id, f.account_id, f.raw_name, f.name, f.role,
                  (SELECT COUNT(*) FROM messages m WHERE m.folder_id = f.id AND m.seen = 0),
                  (SELECT COUNT(*) FROM messages m WHERE m.folder_id = f.id)
           FROM folders f WHERE f.account_id = ?""",
        arrayOf(accountId.toString()),
    ).use { c -> c.list { Folder(getLong(0), getLong(1), getString(2), getString(3), FolderRole.valueOf(getString(4)), getInt(5), getInt(6)) } }
        .sortedWith(compareBy<Folder> { it.role.ordinal }.thenBy { it.name.lowercase() })

    fun folder(id: Long): Folder? = readableDatabase.rawQuery("SELECT account_id FROM folders WHERE id = ?", arrayOf(id.toString())).use { c ->
        if (c.moveToFirst()) folders(c.getLong(0)).firstOrNull { it.id == id } else null
    }

    fun uidValidity(folderId: Long): Long? =
        readableDatabase.rawQuery("SELECT uid_validity FROM folders WHERE id = ?", arrayOf(folderId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    fun isComplete(folderId: Long): Boolean =
        readableDatabase.rawQuery("SELECT complete FROM folders WHERE id = ?", arrayOf(folderId.toString())).use { it.moveToFirst() && it.getInt(0) != 0 }

    fun setComplete(folderId: Long, complete: Boolean) {
        writableDatabase.update("folders", ContentValues().apply { put("complete", complete) }, "id = ?", arrayOf(folderId.toString()))
    }

    /** A new UIDVALIDITY means all known UIDs of this folder are meaningless: drop them. */
    fun resetFolder(folderId: Long, uidValidity: Long) {
        writableDatabase.inTransaction {
            delete("search", "docid IN (SELECT id FROM messages WHERE folder_id = ?)", arrayOf(folderId.toString()))
            delete("messages", "folder_id = ?", arrayOf(folderId.toString()))
            update("folders", ContentValues().apply { put("uid_validity", uidValidity); put("complete", false) }, "id = ?", arrayOf(folderId.toString()))
        }
    }

    // --- messages ---

    fun knownUids(folderId: Long): Set<Long> =
        readableDatabase.rawQuery("SELECT uid FROM messages WHERE folder_id = ? AND uid > 0", arrayOf(folderId.toString())).use { c -> c.list { getLong(0) }.toSet() }

    fun insertHeaders(accountId: Long, folderId: Long, role: FolderRole, headers: List<MessageHeader>, threadKeyFor: (MessageHeader) -> String): List<Long> {
        val inserted = mutableListOf<Long>()
        val now = System.currentTimeMillis()
        writableDatabase.inTransaction {
            headers.forEach { h ->
                val key = threadKeyFor(h)
                val id = insertWithOnConflict("messages", null, ContentValues().apply {
                    put("account_id", accountId); put("folder_id", folderId); put("uid", h.uid); put("subject", h.subject)
                    put("from_name", h.from?.name.orEmpty()); put("from_address", h.from?.address.orEmpty())
                    put("recipients", h.recipients.joinToString(", ") { if (it.name.isBlank()) it.address else "${it.name} <${it.address}>" })
                    put("date_ms", h.date?.time ?: 0L); put("seen", h.seen); put("flagged", h.flagged)
                    put("has_attachment", h.hasAttachment); put("message_id", h.messageId); put("in_reply_to", h.inReplyTo)
                    put("refs", h.references.joinToString(" ")); put("thread_key", key); put("first_seen_ms", now)
                }, SQLiteDatabase.CONFLICT_IGNORE)
                if (id > 0) {
                    inserted += id
                    insert("search", null, ContentValues().apply {
                        put("docid", id); put("subject", h.subject)
                        put("sender", "${h.from?.name.orEmpty()} ${h.from?.address.orEmpty()}")
                        put("recipients", h.recipients.joinToString(" ") { "${it.name} ${it.address}" }); put("body", "")
                    })
                    // A late parent pulls already known replies into its conversation.
                    if (role !in SOLO_ROLES && h.messageId != null && key != "solo:${id}") {
                        execSQL(
                            """UPDATE messages SET thread_key = ? WHERE account_id = ? AND thread_key IN
                               (SELECT thread_key FROM messages WHERE account_id = ? AND (in_reply_to = ? OR refs LIKE ?) AND thread_key NOT LIKE 'solo:%')""",
                            arrayOf<Any?>(key, accountId, accountId, h.messageId, "%${h.messageId}%"),
                        )
                    }
                }
            }
        }
        return inserted
    }

    fun setBodyText(messageRowId: Long, text: String) {
        writableDatabase.inTransaction {
            update("messages", ContentValues().apply { put("body_cached", true) }, "id = ?", arrayOf(messageRowId.toString()))
            update("search", ContentValues().apply { put("body", text) }, "docid = ?", arrayOf(messageRowId.toString()))
        }
    }

    /** Finds the conversation an incoming header belongs to (References/In-Reply-To, then subject). */
    fun findThreadKey(accountId: Long, refs: List<String>, normalizedSubject: String?, dateMillis: Long): String? {
        val db = readableDatabase
        if (refs.isNotEmpty()) {
            val marks = refs.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT thread_key FROM messages WHERE account_id = ? AND message_id IN ($marks) AND thread_key NOT LIKE 'solo:%' LIMIT 1",
                arrayOf(accountId.toString()) + refs.toTypedArray(),
            ).use { if (it.moveToFirst()) return it.getString(0) }
        }
        if (normalizedSubject != null && normalizedSubject.isNotBlank()) {
            val window = 90L * 24 * 3600 * 1000
            db.rawQuery(
                """SELECT thread_key, subject FROM messages WHERE account_id = ? AND thread_key NOT LIKE 'solo:%'
                   AND date_ms BETWEEN ? AND ? ORDER BY date_ms DESC LIMIT 200""",
                arrayOf(accountId.toString(), (dateMillis - window).toString(), (dateMillis + window).toString()),
            ).use { c ->
                while (c.moveToNext()) if (Threading.normalizeSubject(c.getString(1)) == normalizedSubject) return c.getString(0)
            }
        }
        return null
    }

    fun messagesInFolder(folderId: Long, limit: Int = 2000): List<StoredMessage> =
        query("WHERE folder_id = ? ORDER BY date_ms DESC LIMIT $limit", arrayOf(folderId.toString()))

    fun messagesInThreads(accountId: Long, keys: Collection<String>): List<StoredMessage> {
        if (keys.isEmpty()) return emptyList()
        return keys.chunked(500).flatMap { chunk ->
            query("WHERE account_id = ? AND thread_key IN (${chunk.joinToString(",") { "?" }}) ORDER BY date_ms", arrayOf(accountId.toString()) + chunk)
        }
    }

    fun message(id: Long): StoredMessage? = query("WHERE id = ?", arrayOf(id.toString())).firstOrNull()

    fun search(accountId: Long, ftsQuery: String, folderId: Long?, limit: Int = 400): List<StoredMessage> {
        val folderFilter = if (folderId != null) "AND folder_id = $folderId" else
            "AND folder_id NOT IN (SELECT id FROM folders WHERE role IN ('TRASH','SPAM'))"
        return if (ftsQuery.isBlank()) {
            query("WHERE account_id = ? $folderFilter ORDER BY date_ms DESC LIMIT $limit", arrayOf(accountId.toString()))
        } else {
            query("WHERE account_id = ? $folderFilter AND id IN (SELECT docid FROM search WHERE search MATCH ?) ORDER BY date_ms DESC LIMIT $limit", arrayOf(accountId.toString(), ftsQuery))
        }
    }

    fun messageByUid(folderId: Long, uid: Long): StoredMessage? = query("WHERE folder_id = ? AND uid = ?", arrayOf(folderId.toString(), uid.toString())).firstOrNull()

    private fun query(where: String, args: Array<String>): List<StoredMessage> = readableDatabase.rawQuery(
        """SELECT id, account_id, folder_id, uid, subject, from_name, from_address, recipients, date_ms, seen, flagged, has_attachment, message_id, thread_key
           FROM messages $where""", args,
    ).use { c ->
        c.list {
            StoredMessage(getLong(0), getLong(1), getLong(2), getLong(3), getString(4), getString(5), getString(6), getString(7), getLong(8),
                getInt(9) != 0, getInt(10) != 0, getInt(11) != 0, getStringOrNull(12), getString(13))
        }
    }

    fun setFlags(messageRowId: Long, seen: Boolean? = null, flagged: Boolean? = null) {
        val v = ContentValues()
        seen?.let { v.put("seen", it) }
        flagged?.let { v.put("flagged", it) }
        if (v.size() > 0) writableDatabase.update("messages", v, "id = ?", arrayOf(messageRowId.toString()))
    }

    /** Server flags win unless a local change for that message is still waiting in the queue. */
    fun applyServerFlags(folderId: Long, flags: Map<Long, io.github.deviablo.email.mail.imap.ImapClient.Flags>) {
        val pending = readableDatabase.rawQuery("SELECT DISTINCT uid FROM ops WHERE folder_id = ?", arrayOf(folderId.toString())).use { c -> c.list { getLong(0) }.toSet() }
        writableDatabase.inTransaction {
            flags.forEach { (uid, f) ->
                if (uid in pending) return@forEach
                update("messages", ContentValues().apply { put("seen", f.seen); put("flagged", f.flagged) }, "folder_id = ? AND uid = ?", arrayOf(folderId.toString(), uid.toString()))
            }
        }
    }

    /** Removes local copies of mails that are gone from the server (except ones with queued changes). */
    fun removeVanished(folderId: Long, onServer: Set<Long>, from: Long) {
        val pending = readableDatabase.rawQuery("SELECT DISTINCT uid FROM ops WHERE folder_id = ?", arrayOf(folderId.toString())).use { c -> c.list { getLong(0) }.toSet() }
        val gone = readableDatabase.rawQuery("SELECT id, uid FROM messages WHERE folder_id = ? AND uid >= ?", arrayOf(folderId.toString(), from.toString())).use { c ->
            c.list { getLong(0) to getLong(1) }
        }.filter { (_, uid) -> uid > 0 && uid !in onServer && uid !in pending }.map { it.first }
        deleteRows(gone)
    }

    fun deleteRows(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        writableDatabase.inTransaction {
            ids.chunked(500).forEach { chunk ->
                val list = chunk.joinToString(",")
                delete("search", "docid IN ($list)", null)
                delete("messages", "id IN ($list)", null)
            }
        }
    }

    /** Local part of a move: the mail shows up in the target folder at once, with a temporary negative UID. */
    fun moveLocally(messageRowId: Long, targetFolderId: Long) {
        writableDatabase.update("messages", ContentValues().apply { put("folder_id", targetFolderId); put("uid", -messageRowId) }, "id = ?", arrayOf(messageRowId.toString()))
    }

    fun restoreLocally(messageRowId: Long, folderId: Long, uid: Long) {
        writableDatabase.update("messages", ContentValues().apply { put("folder_id", folderId); put("uid", uid) }, "id = ?", arrayOf(messageRowId.toString()))
    }

    fun messagesOlderThanInFolder(folderId: Long, firstSeenBefore: Long): List<StoredMessage> =
        query("WHERE folder_id = ? AND first_seen_ms < ? AND uid > 0", arrayOf(folderId.toString(), firstSeenBefore.toString()))

    fun knownSenders(accountId: Long, prefix: String, limit: Int = 8): List<Pair<String, String>> = readableDatabase.rawQuery(
        """SELECT from_name, from_address, MAX(date_ms) d FROM messages WHERE account_id = ? AND from_address != ''
           AND (from_address LIKE ? OR from_name LIKE ?) GROUP BY lower(from_address) ORDER BY d DESC LIMIT $limit""",
        arrayOf(accountId.toString(), "$prefix%", "%$prefix%"),
    ).use { c -> c.list { getString(0) to getString(1) } }

    // --- queue of local changes ---

    fun addOp(op: PendingOp): Long = writableDatabase.insertOrThrow("ops", null, ContentValues().apply {
        put("account_id", op.accountId); put("kind", op.kind); put("message_row", op.messageRowId); put("folder_id", op.folderId)
        put("uid", op.uid); put("target_folder_id", op.targetFolderId); put("flag", op.flag); put("value", op.value); put("not_before", op.notBefore)
    })

    fun ops(accountId: Long, dueBefore: Long): List<PendingOp> = readableDatabase.rawQuery(
        "SELECT id, account_id, kind, message_row, folder_id, uid, target_folder_id, flag, value, not_before FROM ops WHERE account_id = ? AND not_before <= ? ORDER BY id",
        arrayOf(accountId.toString(), dueBefore.toString()),
    ).use { c -> c.list { PendingOp(getLong(0), getLong(1), getString(2), getLong(3), getLong(4), getLong(5), if (isNull(6)) null else getLong(6), getStringOrNull(7), getInt(8) != 0, getLong(9)) } }

    fun op(id: Long): PendingOp? = readableDatabase.rawQuery(
        "SELECT id, account_id, kind, message_row, folder_id, uid, target_folder_id, flag, value, not_before FROM ops WHERE id = ?", arrayOf(id.toString()),
    ).use { c -> c.list { PendingOp(getLong(0), getLong(1), getString(2), getLong(3), getLong(4), getLong(5), if (isNull(6)) null else getLong(6), getStringOrNull(7), getInt(8) != 0, getLong(9)) }.firstOrNull() }

    fun deleteOp(id: Long) {
        writableDatabase.delete("ops", "id = ?", arrayOf(id.toString()))
    }

    fun hasOps(accountId: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM ops WHERE account_id = ? LIMIT 1", arrayOf(accountId.toString())).use { it.moveToFirst() }

    // --- privacy lists ---

    fun imagesAllowed(accountId: Long, domain: String): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM image_senders WHERE account_id = ? AND domain = ?", arrayOf(accountId.toString(), domain.lowercase())).use { it.moveToFirst() }

    fun allowImages(accountId: Long, domain: String) {
        writableDatabase.insertWithOnConflict("image_senders", null, ContentValues().apply { put("account_id", accountId); put("domain", domain.lowercase()) }, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun spamSenders(accountId: Long): Set<String> =
        readableDatabase.rawQuery("SELECT address FROM spam_senders WHERE account_id = ?", arrayOf(accountId.toString())).use { c -> c.list { getString(0) }.toSet() }

    fun setSpamSender(accountId: Long, address: String, spam: Boolean) {
        if (address.isBlank()) return
        if (spam) writableDatabase.insertWithOnConflict("spam_senders", null, ContentValues().apply { put("account_id", accountId); put("address", address.lowercase()) }, SQLiteDatabase.CONFLICT_IGNORE)
        else writableDatabase.delete("spam_senders", "account_id = ? AND address = ?", arrayOf(accountId.toString(), address.lowercase()))
    }

    // --- outbox ---
    // Kinds as in the Linux app: "draft" (save, replacing older versions with the same Message-ID),
    // "send" (SMTP, then a copy in "Sent"), "sent" (sent already; filing and draft removal left),
    // "discard" (remove every saved version). Message-IDs are stored without <>.

    data class OutboxItem(val id: Long, val accountId: Long, val kind: String, val messageId: String, val raw: ByteArray, val recipients: List<String>, val attempts: Int, val lastError: String?)

    fun queueDraft(accountId: Long, messageId: String, raw: ByteArray) = queue(accountId, "draft", messageId, raw, emptyList())

    /** Waiting drafts of the message are dropped and its saved versions leave the drafts list at once. */
    fun queueSend(accountId: Long, messageId: String, raw: ByteArray, recipients: List<String>) = queue(accountId, "send", messageId, raw, recipients)

    fun queueDiscard(accountId: Long, messageId: String) = queue(accountId, "discard", messageId, ByteArray(0), emptyList())

    private fun queue(accountId: Long, kind: String, messageId: String, raw: ByteArray, recipients: List<String>): Long {
        var id = 0L
        writableDatabase.inTransaction {
            delete("outbox", "account_id = ? AND message_id = ? AND kind IN ('draft', 'discard')", arrayOf(accountId.toString(), messageId))
            id = insertOrThrow("outbox", null, ContentValues().apply {
                put("account_id", accountId); put("kind", kind); put("message_id", messageId); put("raw", raw)
                put("recipients", recipients.joinToString("\n"))
            })
        }
        if (kind != "draft") hideDrafts(accountId, messageId)
        return id
    }

    /** Local copies of a draft in the drafts folder; the server copies go with the outbox. */
    fun hideDrafts(accountId: Long, messageId: String) {
        val ids = readableDatabase.rawQuery(
            "SELECT m.id FROM messages m JOIN folders f ON f.id = m.folder_id WHERE m.account_id = ? AND f.role = ? AND m.message_id = ?",
            arrayOf(accountId.toString(), FolderRole.DRAFTS.name, "<$messageId>"),
        ).use { c -> c.list { getLong(0) } }
        deleteRows(ids)
    }

    fun outbox(accountId: Long, now: Long): List<OutboxItem> = readableDatabase.rawQuery(
        "SELECT id, account_id, kind, message_id, raw, recipients, attempts, last_error FROM outbox WHERE account_id = ? AND next_try <= ? ORDER BY id",
        arrayOf(accountId.toString(), now.toString()),
    ).use { c -> c.list { OutboxItem(getLong(0), getLong(1), getString(2), getString(3), getBlob(4), getString(5).split('\n').filter { it.isNotBlank() }, getInt(6), getStringOrNull(7)) } }

    fun outboxDone(id: Long) {
        writableDatabase.delete("outbox", "id = ?", arrayOf(id.toString()))
    }

    fun outboxKind(id: Long, kind: String) {
        writableDatabase.execSQL("UPDATE outbox SET kind = ?, next_try = 0, last_error = NULL WHERE id = ?", arrayOf<Any?>(kind, id))
    }

    fun outboxRetry(id: Long, nextTry: Long, error: String?) {
        writableDatabase.execSQL("UPDATE outbox SET attempts = attempts + 1, next_try = ?, last_error = ? WHERE id = ?", arrayOf<Any?>(nextTry, error, id))
    }

    /** Mails still waiting to go out (all accounts or one). */
    fun unsentCount(accountId: Long? = null): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM outbox WHERE kind = 'send'" + if (accountId != null) " AND account_id = $accountId" else "", null,
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun hasOutbox(accountId: Long): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM outbox WHERE account_id = ? LIMIT 1", arrayOf(accountId.toString()),
    ).use { it.moveToFirst() }

    fun pendingDraftsCount(accountId: Long): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM outbox WHERE account_id = ? AND kind = 'draft'", arrayOf(accountId.toString())).use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** Addresses for the recipient suggestions: senders and recipients of this account's mail, most frequent first. */
    fun knownAddresses(accountId: Long): List<Pair<String, String>> {
        val counts = HashMap<String, Pair<String, Int>>()
        fun add(name: String, email: String) {
            val key = email.trim().lowercase()
            if (key.isEmpty() || !key.contains('@')) return
            val old = counts[key]
            counts[key] = (if (old != null && old.first.isNotBlank()) old.first else name.trim()) to ((old?.second ?: 0) + 1)
        }
        readableDatabase.rawQuery("SELECT from_name, from_address, recipients FROM messages WHERE account_id = ? ORDER BY date_ms DESC LIMIT 5000", arrayOf(accountId.toString())).use { c ->
            while (c.moveToNext()) {
                add(c.getString(0), c.getString(1))
                io.github.deviablo.email.mail.Compose.parseAddresses(c.getString(2)).first.forEach { add(it.name, it.email) }
            }
        }
        return counts.entries.sortedByDescending { it.value.second }.map { it.value.first to it.key }
    }

    private inline fun <T> Cursor.list(row: Cursor.() -> T): List<T> = buildList { while (moveToNext()) add(row()) }
    private fun Cursor.getStringOrNull(i: Int): String? = if (isNull(i)) null else getString(i)

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try { block(); setTransactionSuccessful() } finally { endTransaction() }
    }

    companion object {
        /** Trash, spam and drafts show only their own mails and never join other conversations. */
        val SOLO_ROLES = setOf(FolderRole.TRASH, FolderRole.SPAM, FolderRole.DRAFTS)
    }
}
