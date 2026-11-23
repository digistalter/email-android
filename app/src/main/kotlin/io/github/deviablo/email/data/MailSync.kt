// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import io.github.deviablo.email.mail.imap.ImapClient
import io.github.deviablo.email.mail.imap.ImapFolder
import io.github.deviablo.email.mail.imap.ServerSettings
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

/**
 * First step of the sync from the concept: headers of the last 30 days in all folders.
 * Bodies, flag changes and the full history follow in later steps.
 */
class MailSync(private val db: MailDatabase, private val connect: (ServerSettings) -> ImapClient = { ImapClient.connect(it) }) {

    companion object {
        const val INITIAL_DAYS = 30
        private const val BATCH = 200
    }

    fun sync(account: Account, password: String) {
        val client = connect(ServerSettings(account.imapHost, account.imapPort, account.imapSecurity, account.username))
        try {
            client.login(account.username, password)
            val remote = client.list().filter { it.selectable }
            val roles = FolderRoles.assign(remote)
            val ids = remote.associate { it.rawName to db.upsertFolder(account.id, it.rawName, it.name, roles.getValue(it.rawName)) }
            db.removeFoldersExcept(account.id, ids.values)

            val since = Date(System.currentTimeMillis() - INITIAL_DAYS * 24L * 3600 * 1000)
            // Inbox first: that is what the user is looking at.
            for (folder in remote.sortedBy { if (roles[it.rawName] == FolderRole.INBOX) 0 else 1 }) {
                val folderId = ids.getValue(folder.rawName)
                val selected = client.select(folder.rawName)
                val validity = selected.uidValidity
                if (validity != null && validity != db.uidValidity(folderId)) db.resetFolder(folderId, validity)
                val known = db.knownUids(folderId)
                val missing = client.uidSearchSince(since).filter { it !in known }.sortedDescending()
                missing.chunked(BATCH).forEach { batch -> db.insertHeaders(folderId, client.uidFetchHeaders(batch)) }
            }
            client.logout()
        } finally {
            client.close()
        }
    }

    /** Connects and logs in once, so the account form can report problems before saving anything. */
    fun check(settings: ServerSettings, password: String) {
        connect(settings).use { client ->
            client.login(settings.username, password)
            client.logout()
        }
    }
}
