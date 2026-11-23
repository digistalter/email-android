// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import io.github.deviablo.email.mail.imap.ImapClient
import io.github.deviablo.email.mail.imap.ImapCommandException
import io.github.deviablo.email.mail.imap.ImapFolder
import io.github.deviablo.email.mail.imap.MessageHeader
import io.github.deviablo.email.mail.imap.ServerSettings
import java.io.IOException
import java.util.Date

/** Assigns the standard roles: SPECIAL-USE attributes first, well-known names as fallback. */
object FolderRoles {
    private val byAttribute = mapOf(
        "\\drafts" to FolderRole.DRAFTS, "\\sent" to FolderRole.SENT, "\\archive" to FolderRole.ARCHIVE,
        "\\junk" to FolderRole.SPAM, "\\trash" to FolderRole.TRASH,
    )
    private val byName = mapOf(
        FolderRole.DRAFTS to setOf("drafts", "entwürfe", "draft"),
        FolderRole.SENT to setOf("sent", "gesendet", "sent items", "sent messages", "gesendete objekte", "gesendete elemente"),
        FolderRole.ARCHIVE to setOf("archive", "archiv"),
        FolderRole.SPAM to setOf("spam", "junk", "junk e-mail", "junk-e-mail", "unerwünscht"),
        FolderRole.TRASH to setOf("trash", "papierkorb", "deleted items", "deleted messages", "gelöschte elemente", "gelöschte objekte"),
    )

    /** Names used when a standard folder is missing on the server and gets created. */
    val defaultNames = mapOf(
        FolderRole.DRAFTS to "Drafts", FolderRole.SENT to "Sent", FolderRole.ARCHIVE to "Archive",
        FolderRole.SPAM to "Spam", FolderRole.TRASH to "Trash",
    )

    fun assign(folders: List<ImapFolder>): Map<String, FolderRole> {
        val result = mutableMapOf<String, FolderRole>()
        val taken = mutableSetOf<FolderRole>()
        folders.firstOrNull { it.rawName.equals("INBOX", true) }?.let { result[it.rawName] = FolderRole.INBOX; taken += FolderRole.INBOX }
        for (f in folders) {
            val role = f.attributes.firstNotNullOfOrNull { byAttribute[it.lowercase()] } ?: continue
            if (role !in taken && f.rawName !in result) { result[f.rawName] = role; taken += role }
        }
        for (f in folders) {
            if (f.rawName in result) continue
            val leaf = f.delimiter?.let { d -> f.name.substringAfterLast(d) } ?: f.name
            val role = byName.entries.firstOrNull { (_, names) -> leaf.lowercase() in names }?.key ?: continue
            if (role !in taken) { result[f.rawName] = role; taken += role }
        }
        return folders.associate { it.rawName to (result[it.rawName] ?: FolderRole.OTHER) }
    }
}

data class SyncResult(
    /** New unread inbox mails that arrived after the first sync – candidates for a notification. */
    val newInboxMail: List<StoredMessage> = emptyList(),
    /** Local changes the server refused; they were undone locally. */
    val rejected: Int = 0,
)

/**
 * Brings one account up to date, following the Linux app's concept:
 * queued local changes first, then headers of the last 30 days, flags and deletions, and step by
 * step the older history (200 per folder and run, newest first). Bodies load when opened.
 */
class MailSync(
    private val db: MailDatabase,
    private val bodies: BodyStore,
    private val connect: (ServerSettings) -> ImapClient = { ImapClient.connect(it) },
) {
    companion object {
        const val INITIAL_DAYS = 30
        private const val BATCH = 200
        private const val NOTIFY_MAX_AGE = 2L * 24 * 3600 * 1000
    }

    fun sync(account: Account, password: String, outbox: ((ImapClient) -> Unit)? = null): SyncResult {
        val client = connect(account.imapSettings())
        try {
            client.login(account.username, password)
            var rejected = runOps(client, account)
            outbox?.invoke(client)

            var remote = client.list().filter { it.selectable }
            var roles = FolderRoles.assign(remote)
            if (createMissingStandardFolders(client, remote, roles)) {
                remote = client.list().filter { it.selectable }
                roles = FolderRoles.assign(remote)
            }
            val ids = remote.associate { it.rawName to db.upsertFolder(account.id, it.rawName, it.name, roles.getValue(it.rawName)) }
            db.removeFoldersExcept(account.id, ids.values)

            val newMail = mutableListOf<StoredMessage>()
            val spamSenders = db.spamSenders(account.id)
            val spamFolderId = ids.entries.firstOrNull { roles[it.key] == FolderRole.SPAM }?.value
            for (folder in remote.sortedBy { if (roles[it.rawName] == FolderRole.INBOX) 0 else 1 }) {
                val role = roles.getValue(folder.rawName)
                val inserted = syncFolder(client, account, folder, ids.getValue(folder.rawName), role)
                if (role == FolderRole.INBOX) {
                    for (m in inserted) {
                        if (m.fromAddress.lowercase() in spamSenders && spamFolderId != null) {
                            db.moveLocally(m.id, spamFolderId)
                            db.addOp(PendingOp(0, account.id, "move", m.id, m.folderId, m.uid, spamFolderId, null, false, 0))
                        } else if (!m.seen && System.currentTimeMillis() - m.dateMillis < NOTIFY_MAX_AGE) {
                            newMail += m
                        }
                    }
                }
            }
            rejected += runOps(client, account) // e.g. spam filtering from this run
            client.logout()
            return SyncResult(newMail, rejected)
        } finally {
            client.close()
        }
    }

    /** Returns the rows inserted in this run (only for folders that were known before). */
    private fun syncFolder(client: ImapClient, account: Account, folder: ImapFolder, folderId: Long, role: FolderRole): List<StoredMessage> {
        val selected = client.select(folder.rawName)
        val knownBefore = db.uidValidity(folderId)
        val validity = selected.uidValidity
        if (validity != null && validity != knownBefore) {
            db.resetFolder(folderId, validity)
            bodies.dropFolder(folderId)
        }
        val firstRun = knownBefore == null || (validity != null && validity != knownBefore)

        // Which mails are in the folder is decided by UID SEARCH ALL, never by the EXISTS count.
        val all = client.uidSearch("ALL").toSet()
        val known = db.knownUids(folderId)
        if (all.isNotEmpty() || selected.exists == 0L) {
            db.removeVanished(folderId, all, 0)
        }
        // If the search found nothing although the server counts mails, keep everything (Linux lesson).

        val since = Date(System.currentTimeMillis() - INITIAL_DAYS * 24L * 3600 * 1000)
        val recent = if (firstRun || !db.isComplete(folderId)) client.uidSearchSince(since).toSet() else emptySet()
        val missingRecent = (recent + all.filter { it > (known.maxOrNull() ?: 0) }).filter { it !in known }.sortedDescending()
        val inserted = mutableListOf<Long>()
        missingRecent.chunked(BATCH).forEach { inserted += insert(client, account, folderId, role, it) }

        // Flags of everything we already know.
        val knownNow = db.knownUids(folderId).filter { it in all || all.isEmpty() }
        knownNow.chunked(1000).forEach { db.applyServerFlags(folderId, client.uidFetchFlags(it)) }

        // Older history, newest first, one batch per run.
        val older = all.filter { it !in db.knownUids(folderId) }.sortedDescending().take(BATCH)
        if (older.isNotEmpty()) insert(client, account, folderId, role, older)
        db.setComplete(folderId, all.all { it in db.knownUids(folderId) })

        return if (firstRun) emptyList() else inserted.mapNotNull { db.message(it) }
    }

    /** Loads and stores headers of specific mails (used by the server search). */
    fun insertHeaders(client: ImapClient, account: Account, folder: Folder, uids: List<Long>) = insert(client, account, folder.id, folder.role, uids)

    private fun insert(client: ImapClient, account: Account, folderId: Long, role: FolderRole, uids: List<Long>): List<Long> {
        val headers = client.uidFetchHeaders(uids)
        return db.insertHeaders(account.id, folderId, role, headers) { h -> threadKey(account.id, role, folderId, h) }
    }

    private fun threadKey(accountId: Long, role: FolderRole, folderId: Long, h: MessageHeader): String {
        if (role in MailDatabase.SOLO_ROLES) return "solo:$folderId:${h.uid}"
        val refs = (h.references + listOfNotNull(h.inReplyTo)).distinct()
        val subject = if (Threading.isReply(h.subject)) Threading.normalizeSubject(h.subject) else null
        return db.findThreadKey(accountId, refs + listOfNotNull(h.messageId), subject, h.date?.time ?: 0)
            ?: h.messageId ?: "uid:$folderId:${h.uid}"
    }

    private fun createMissingStandardFolders(client: ImapClient, remote: List<ImapFolder>, roles: Map<String, FolderRole>): Boolean {
        if (remote.none { it.rawName.equals("INBOX", true) }) return false
        val present = roles.values.toSet()
        var created = false
        for ((role, name) in FolderRoles.defaultNames) {
            if (role in present || remote.any { it.rawName.equals(name, true) }) continue
            runCatching { client.create(name); created = true }
        }
        return created
    }

    /** Sends due local changes to the server. Returns how many the server refused. */
    private fun runOps(client: ImapClient, account: Account): Int {
        val ops = db.ops(account.id, System.currentTimeMillis())
        if (ops.isEmpty()) return 0
        var rejected = 0
        val folders = db.folders(account.id).associateBy { it.id }
        for ((folderId, group) in ops.groupBy { it.folderId }) {
            val folder = folders[folderId] ?: run { group.forEach { db.deleteOp(it.id) }; continue }
            client.select(folder.rawName, readOnly = false)
            for (op in group) {
                try {
                    when (op.kind) {
                        "flag" -> client.uidStore(listOf(op.uid), op.value, op.flag ?: continue)
                        "move" -> {
                            val target = op.targetFolderId?.let { folders[it] } ?: run { db.deleteOp(op.id); continue }
                            client.uidMove(listOf(op.uid), target.rawName)
                            bodies.drop(op.messageRowId)
                            db.deleteRows(listOf(op.messageRowId)) // reappears with its new UID when the target syncs
                        }
                        "delete" -> client.uidDelete(listOf(op.uid))
                    }
                    db.deleteOp(op.id)
                } catch (e: ImapCommandException) {
                    // The server said no: undo locally so the user sees the real state again.
                    rejected++
                    if (op.kind == "move") db.restoreLocally(op.messageRowId, op.folderId, op.uid)
                    db.deleteOp(op.id)
                } catch (e: IOException) {
                    throw e // keep the queue; next sync retries
                }
            }
        }
        return rejected
    }

    /** Connects and logs in once, so the setup can report problems before saving anything. */
    fun check(settings: ServerSettings, password: String) {
        connect(settings).use { client ->
            client.login(settings.username, password)
            client.logout()
        }
    }
}

fun Account.imapSettings() = ServerSettings(imapHost, imapPort, imapSecurity, username)
fun Account.smtpSettings() = ServerSettings(smtpHost, smtpPort, smtpSecurity, smtpUsername.ifBlank { username })
