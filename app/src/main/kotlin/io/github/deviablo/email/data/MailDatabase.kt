// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import android.content.ContentValues
import android.content.Context
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
)

enum class FolderRole { INBOX, DRAFTS, SENT, ARCHIVE, SPAM, TRASH, OTHER }

data class Folder(val id: Long, val accountId: Long, val rawName: String, val name: String, val role: FolderRole, val unread: Int)

data class StoredMessage(
    val id: Long,
    val folderId: Long,
    val uid: Long,
    val subject: String,
    val fromName: String,
    val fromAddress: String,
    val dateMillis: Long,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
)

/** Local cache of accounts, folders and message headers. The server stays the source of truth. */
class MailDatabase(context: Context) : SQLiteOpenHelper(context, "mail.db", null, 1) {

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE accounts (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL, display_name TEXT NOT NULL, address TEXT NOT NULL,
                imap_host TEXT NOT NULL, imap_port INTEGER NOT NULL, imap_security TEXT NOT NULL,
                username TEXT NOT NULL, password BLOB NOT NULL)""",
        )
        db.execSQL(
            """CREATE TABLE folders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                account_id INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                raw_name TEXT NOT NULL, name TEXT NOT NULL, role TEXT NOT NULL,
                uid_validity INTEGER,
                UNIQUE(account_id, raw_name))""",
        )
        db.execSQL(
            """CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                folder_id INTEGER NOT NULL REFERENCES folders(id) ON DELETE CASCADE,
                uid INTEGER NOT NULL, subject TEXT NOT NULL,
                from_name TEXT NOT NULL, from_address TEXT NOT NULL, date_ms INTEGER NOT NULL,
                seen INTEGER NOT NULL, flagged INTEGER NOT NULL, has_attachment INTEGER NOT NULL,
                message_id TEXT, in_reply_to TEXT,
                UNIQUE(folder_id, uid))""",
        )
        db.execSQL("CREATE INDEX messages_by_date ON messages(folder_id, date_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // --- accounts ---

    fun insertAccount(a: Account, encryptedPassword: ByteArray): Long = writableDatabase.insertOrThrow(
        "accounts", null,
        ContentValues().apply {
            put("name", a.name); put("display_name", a.displayName); put("address", a.address)
            put("imap_host", a.imapHost); put("imap_port", a.imapPort); put("imap_security", a.imapSecurity.name)
            put("username", a.username); put("password", encryptedPassword)
        },
    )

    fun accounts(): List<Account> = readableDatabase.rawQuery(
        "SELECT id, name, display_name, address, imap_host, imap_port, imap_security, username FROM accounts ORDER BY id", null,
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                Account(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5), Security.valueOf(c.getString(6)), c.getString(7)),
            )
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
        writableDatabase.delete("folders", "account_id = ? AND id NOT IN (${keepIds.joinToString(",")})", arrayOf(accountId.toString()))
    }

    fun folders(accountId: Long): List<Folder> = readableDatabase.rawQuery(
        """SELECT f.id, f.account_id, f.raw_name, f.name, f.role,
                  (SELECT COUNT(*) FROM messages m WHERE m.folder_id = f.id AND m.seen = 0)
           FROM folders f WHERE f.account_id = ?""",
        arrayOf(accountId.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(Folder(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), FolderRole.valueOf(c.getString(4)), c.getInt(5)))
        }
    }.sortedWith(compareBy<Folder> { it.role.ordinal }.thenBy { it.name.lowercase() })

    fun uidValidity(folderId: Long): Long? =
        readableDatabase.rawQuery("SELECT uid_validity FROM folders WHERE id = ?", arrayOf(folderId.toString())).use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }

    /** A new UIDVALIDITY means all known UIDs of this folder are meaningless: drop them. */
    fun resetFolder(folderId: Long, uidValidity: Long) {
        writableDatabase.inTransaction {
            delete("messages", "folder_id = ?", arrayOf(folderId.toString()))
            update("folders", ContentValues().apply { put("uid_validity", uidValidity) }, "id = ?", arrayOf(folderId.toString()))
        }
    }

    // --- messages ---

    fun knownUids(folderId: Long): Set<Long> =
        readableDatabase.rawQuery("SELECT uid FROM messages WHERE folder_id = ?", arrayOf(folderId.toString())).use { c ->
            buildSet { while (c.moveToNext()) add(c.getLong(0)) }
        }

    fun insertHeaders(folderId: Long, headers: List<MessageHeader>) {
        writableDatabase.inTransaction {
            headers.forEach { h ->
                insertWithOnConflict("messages", null, ContentValues().apply {
                    put("folder_id", folderId); put("uid", h.uid); put("subject", h.subject)
                    put("from_name", h.from?.name.orEmpty()); put("from_address", h.from?.address.orEmpty())
                    put("date_ms", h.date?.time ?: 0L); put("seen", h.seen); put("flagged", h.flagged)
                    put("has_attachment", h.hasAttachment); put("message_id", h.messageId); put("in_reply_to", h.inReplyTo)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
        }
    }

    fun messages(folderId: Long, limit: Int = 500): List<StoredMessage> = readableDatabase.rawQuery(
        """SELECT id, folder_id, uid, subject, from_name, from_address, date_ms, seen, flagged, has_attachment
           FROM messages WHERE folder_id = ? ORDER BY date_ms DESC LIMIT $limit""",
        arrayOf(folderId.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) add(
                StoredMessage(c.getLong(0), c.getLong(1), c.getLong(2), c.getString(3), c.getString(4), c.getString(5),
                    c.getLong(6), c.getInt(7) != 0, c.getInt(8) != 0, c.getInt(9) != 0),
            )
        }
    }

    private inline fun SQLiteDatabase.inTransaction(block: SQLiteDatabase.() -> Unit) {
        beginTransaction()
        try { block(); setTransactionSuccessful() } finally { endTransaction() }
    }
}
