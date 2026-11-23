// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import android.content.Context
import io.github.deviablo.email.background.Notifier
import io.github.deviablo.email.mail.Compose
import io.github.deviablo.email.mail.Draft
import io.github.deviablo.email.mail.Mailbox
import io.github.deviablo.email.mail.MessageWriter
import io.github.deviablo.email.mail.Mime
import io.github.deviablo.email.mail.Original
import io.github.deviablo.email.mail.ParsedMessage
import io.github.deviablo.email.mail.imap.ImapClient
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import org.jsoup.Jsoup

/** Undo information for "Gelöscht · Rückgängig": the queued ops to cancel. */
data class UndoToken(val opIds: List<Long>)

/**
 * The app's single access point to mail data: the UI, the background worker and notification
 * actions all go through here, so local changes and the server queue stay consistent.
 */
class MailRepository private constructor(context: Context) {
    private val appContext = context
    val db = MailDatabase(context)
    val bodies = BodyStore(context)
    val settings = Settings(context)
    private val sync = MailSync(db, bodies)
    private val pool = ImapPool()
    private val syncLocks = mutableMapOf<Long, Any>()

    private val _changes = MutableSharedFlow<Long>(extraBufferCapacity = 16)
    /** Emits an account id whenever its data changed (sync, action, background). */
    val changes: SharedFlow<Long> = _changes

    private val _sendEvents = MutableSharedFlow<SendEvent>(extraBufferCapacity = 8)
    /** Sent / could not send, for the snackbar while the app is open. */
    val sendEvents: SharedFlow<SendEvent> = _sendEvents
    private val outbox = OutboxProcessor(db, { _sendEvents.tryEmit(it) })

    companion object {
        const val UNDO_MS = 8_000L
        @Volatile private var instance: MailRepository? = null
        fun get(context: Context): MailRepository =
            instance ?: synchronized(this) { instance ?: MailRepository(context.applicationContext).also { instance = it } }
    }

    fun changed(accountId: Long) {
        _changes.tryEmit(accountId)
    }

    fun password(accountId: Long): String = PasswordVault.decrypt(db.encryptedPassword(accountId) ?: error("no password stored"))

    // --- sync ---

    /** Blocking; one sync per account at a time. */
    fun syncAccount(account: Account): SyncResult {
        val lock = synchronized(syncLocks) { syncLocks.getOrPut(account.id) { Any() } }
        synchronized(lock) {
            val password = password(account.id)
            val result = sync.sync(account, password) { client -> outbox.process(account, password, client) }
            autoEmpty(account)
            changed(account.id)
            return result
        }
    }

    fun check(settings: io.github.deviablo.email.mail.imap.ServerSettings, password: String) = sync.check(settings, password)

    fun pruneBodies() {
        val ids = db.readableDatabase.rawQuery("SELECT id FROM messages", null).use { c -> buildSet { while (c.moveToNext()) add(c.getLong(0)) } }
        bodies.prune(ids)
    }

    private fun autoEmpty(account: Account) {
        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
        for (folder in db.folders(account.id)) {
            val mode = when (folder.role) {
                FolderRole.TRASH -> settings.emptyTrash
                FolderRole.SPAM -> settings.emptySpam
                else -> continue
            }
            if (mode != AutoEmpty.AFTER_30_DAYS) continue
            // Counted from when the app first saw the mail there, not from its date (Linux app).
            deletePermanently(db.messagesOlderThanInFolder(folder.id, cutoff))
        }
    }

    // --- reading ---

    fun conversations(accountId: Long, folder: Folder): List<Conversation> {
        val inFolder = db.messagesInFolder(folder.id)
        val grouping = settings.groupConversations
        val allowed = if (folder.role in MailDatabase.SOLO_ROLES) setOf(folder.id)
        else db.folders(accountId).filter { it.role !in MailDatabase.SOLO_ROLES }.map { it.id }.toSet()
        val related = if (grouping && folder.role !in MailDatabase.SOLO_ROLES) db.messagesInThreads(accountId, inFolder.map { it.threadKey }.toSet()) else emptyList()
        return Threading.conversations(inFolder, related, grouping, allowed)
    }

    /** The complete message: from the local copy or downloaded now (blocking). */
    fun loadMessage(m: StoredMessage): ParsedMessage? {
        bodies.get(m.id)?.let { return Mime.parse(it) }
        if (m.uid <= 0) return null // still on its way to another folder
        val account = db.accounts().firstOrNull { it.id == m.accountId } ?: return null
        val folder = db.folder(m.folderId) ?: return null
        val raw = pool.use(account, password(account.id)) { client ->
            client.select(folder.rawName)
            client.uidFetchRaw(m.uid)
        } ?: return null
        bodies.put(m.id, raw)
        val parsed = Mime.parse(raw)
        val text = parsed.text ?: parsed.html?.let { Jsoup.parse(it).text() } ?: ""
        db.setBodyText(m.id, text.take(100_000))
        return parsed
    }

    // --- actions (local at once, server via queue) ---

    private fun flagOp(m: StoredMessage, flag: String, value: Boolean) {
        if (m.uid <= 0) return
        db.addOp(PendingOp(0, m.accountId, "flag", m.id, m.folderId, m.uid, null, flag, value, 0))
    }

    fun setRead(messages: List<StoredMessage>, read: Boolean) {
        if (read) Notifier.withdraw(appContext, messages.map { it.id })
        for (m in messages.filter { it.seen != read }) {
            db.setFlags(m.id, seen = read)
            flagOp(m, "\\Seen", read)
        }
        messages.firstOrNull()?.let { changed(it.accountId) }
    }

    fun setStar(m: StoredMessage, star: Boolean) {
        db.setFlags(m.id, flagged = star)
        flagOp(m, "\\Flagged", star)
        changed(m.accountId)
    }

    /** Read marks the whole conversation; unread and star affect the newest message (Linux app). */
    fun markConversationRead(c: Conversation, read: Boolean) =
        if (read) setRead(c.messages, true) else setRead(listOf(c.newest), false)

    fun move(messages: List<StoredMessage>, target: Folder): UndoToken {
        Notifier.withdraw(appContext, messages.map { it.id })
        val ids = mutableListOf<Long>()
        for (m in messages) {
            if (m.folderId == target.id) continue
            val pending = if (m.uid <= 0) db.ops(m.accountId, Long.MAX_VALUE).firstOrNull { it.kind == "move" && it.messageRowId == m.id } else null
            if (pending != null) {
                // Still on its way: change the destination instead of queueing a second move.
                db.deleteOp(pending.id)
                if (pending.folderId == target.id) { db.restoreLocally(m.id, pending.folderId, pending.uid); continue }
                db.moveLocally(m.id, target.id)
                ids += db.addOp(pending.copy(id = 0, targetFolderId = target.id, notBefore = System.currentTimeMillis() + UNDO_MS))
            } else {
                db.moveLocally(m.id, target.id)
                ids += db.addOp(PendingOp(0, m.accountId, "move", m.id, m.folderId, m.uid, target.id, null, false, System.currentTimeMillis() + UNDO_MS))
            }
        }
        messages.firstOrNull()?.let { changed(it.accountId) }
        return UndoToken(ids)
    }

    /** Moves to the trash; in the trash (or without one) deletes for good, then without undo. */
    fun delete(messages: List<StoredMessage>): UndoToken? {
        val first = messages.firstOrNull() ?: return null
        val folders = db.folders(first.accountId)
        val trash = folders.firstOrNull { it.role == FolderRole.TRASH }
        val current = folders.firstOrNull { it.id == first.folderId }
        return if (trash == null || current?.role == FolderRole.TRASH) {
            deletePermanently(messages); null
        } else move(messages, trash)
    }

    fun deletePermanently(messages: List<StoredMessage>) {
        for (m in messages) {
            if (m.uid > 0) db.addOp(PendingOp(0, m.accountId, "delete", m.id, m.folderId, m.uid, null, null, false, 0))
            bodies.drop(m.id)
        }
        db.deleteRows(messages.map { it.id })
        messages.firstOrNull()?.let { changed(it.accountId) }
    }

    fun markSpam(messages: List<StoredMessage>, spam: Boolean): UndoToken? {
        val first = messages.firstOrNull() ?: return null
        val folders = db.folders(first.accountId)
        val target = folders.firstOrNull { it.role == if (spam) FolderRole.SPAM else FolderRole.INBOX } ?: return null
        messages.map { it.fromAddress }.distinct().forEach { db.setSpamSender(first.accountId, it, spam) }
        return move(messages, target)
    }

    /** Takes back queued moves that have not reached the server yet. */
    fun undo(token: UndoToken, spamSendersOf: List<StoredMessage> = emptyList(), wasSpam: Boolean = false): Boolean {
        var all = true
        var account = -1L
        for (id in token.opIds) {
            val op = db.op(id) ?: run { all = false; null } ?: continue
            if (op.kind == "move") db.restoreLocally(op.messageRowId, op.folderId, op.uid)
            db.deleteOp(op.id)
            account = op.accountId
        }
        if (account > 0) spamSendersOf.map { it.fromAddress }.distinct().forEach { db.setSpamSender(account, it, !wasSpam) }
        if (account > 0) changed(account)
        return all
    }

    fun emptyFolder(folder: Folder) = deletePermanently(db.messagesInFolder(folder.id, Int.MAX_VALUE))

    // --- writing ---

    private fun sender(account: Account) = Mailbox(account.displayName, account.address)

    /** Saves a version of a draft; the server copy follows with the next sync. */
    fun saveDraft(account: Account, draft: Draft) {
        db.queueDraft(account.id, draft.messageId, MessageWriter.build(sender(account), draft, System.currentTimeMillis()))
        changed(account.id)
    }

    /** Puts the mail into the outbox; it leaves with the next sync, even after the app was closed. */
    fun send(account: Account, draft: Draft) {
        db.queueSend(account.id, draft.messageId, MessageWriter.build(sender(account), draft, System.currentTimeMillis()), draft.recipients())
        changed(account.id)
    }

    fun discardDraft(account: Account, messageId: String) {
        db.queueDiscard(account.id, messageId)
        changed(account.id)
    }

    /** The original of a reply or forward, or a saved draft, read from the full message (blocking). */
    fun original(m: StoredMessage): Original? {
        loadMessage(m) ?: return null
        return bodies.get(m.id)?.let { Compose.readOriginal(it) }
    }

    fun draftFrom(m: StoredMessage, account: Account): Draft? {
        loadMessage(m) ?: return null
        return bodies.get(m.id)?.let { Compose.readDraft(it, account.address) }
    }

    fun knownAddresses(account: Account): List<Mailbox> {
        val own = db.accounts().map { it.address.lowercase() }.toSet()
        return db.knownAddresses(account.id).filter { it.second !in own }.map { Mailbox(it.first, it.second) }
    }

    // --- search ---

    fun search(accountId: Long, fts: String, folderId: Long?, filters: Set<SearchFilter>, extraIds: Set<Long>): List<Conversation> {
        val local = db.search(accountId, fts, folderId)
        val extra = extraIds.mapNotNull { db.message(it) }.filter { folderId == null || it.folderId == folderId }
        val hits = (local + extra).distinctBy { it.id }
        val convs = Threading.conversations(hits.sortedByDescending { it.dateMillis }, emptyList(), settings.groupConversations, emptySet())
        return convs.filter { c ->
            (SearchFilter.UNREAD !in filters || c.unread) &&
                (SearchFilter.STARRED !in filters || c.flagged) &&
                (SearchFilter.ATTACHMENT !in filters || c.hasAttachment)
        }.take(200)
    }

    /** Asks the server (IMAP SEARCH TEXT) and loads headers of hits not known yet. Returns local row ids of all hits. */
    fun searchOnServer(account: Account, words: List<String>, folderId: Long?): Set<Long> {
        val folders = db.folders(account.id).filter { if (folderId != null) it.id == folderId else it.role != FolderRole.TRASH && it.role != FolderRole.SPAM }
        val hits = mutableSetOf<Long>()
        pool.use(account, password(account.id)) { client ->
            for (folder in folders) {
                client.select(folder.rawName)
                val uids = words.map { client.uidSearchText(it).toSet() }.reduce { a, b -> a intersect b }
                if (uids.isEmpty()) continue
                val known = db.knownUids(folder.id)
                val missing = uids.filter { it !in known }.sortedDescending().take(200)
                if (missing.isNotEmpty()) sync.insertHeaders(client, account, folder, missing)
                uids.forEach { uid -> db.messageByUid(folder.id, uid)?.let { hits += it.id } }
            }
        }
        return hits
    }

    fun allowImages(accountId: Long, domain: String) = db.allowImages(accountId, domain)
    fun imagesAllowed(accountId: Long, domain: String) = settings.alwaysShowImages || db.imagesAllowed(accountId, domain)

    fun closeConnections() = pool.closeAll()

    /** Connection for quick interactive work (opening a mail); reused for a minute. */
    private class ImapPool {
        private val clients = mutableMapOf<Long, Pair<ImapClient, Long>>()

        fun <T> use(account: Account, password: String, block: (ImapClient) -> T): T = synchronized(this) {
            val now = System.currentTimeMillis()
            var entry = clients[account.id]
            if (entry != null && now - entry.second > 60_000) {
                if (runCatching { entry!!.first.noop() }.isFailure) { entry.first.close(); entry = null; clients.remove(account.id) }
            }
            val reused = entry?.first
            if (reused != null) {
                try {
                    val result = block(reused)
                    clients[account.id] = reused to now
                    return@synchronized result
                } catch (e: java.io.IOException) {
                    // The kept connection broke in the meantime (network change, server timeout): once more with a new one.
                    reused.close(); clients.remove(account.id)
                } catch (e: Exception) {
                    reused.close(); clients.remove(account.id)
                    throw e
                }
            }
            val client = ImapClient.connect(account.imapSettings()).also { it.login(account.username, password) }
            try {
                val result = block(client)
                clients[account.id] = client to now
                result
            } catch (e: Exception) {
                client.close(); clients.remove(account.id)
                throw e
            }
        }

        fun closeAll() = synchronized(this) {
            clients.values.forEach { runCatching { it.first.logout() } }
            clients.clear()
        }
    }
}
