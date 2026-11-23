// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.deviablo.email.BuildConfig
import io.github.deviablo.email.R
import io.github.deviablo.email.background.Notifier
import io.github.deviablo.email.data.Account
import io.github.deviablo.email.data.Conversation
import io.github.deviablo.email.data.Folder
import io.github.deviablo.email.data.FolderRole
import io.github.deviablo.email.data.MailRepository
import io.github.deviablo.email.data.PasswordVault
import io.github.deviablo.email.data.SearchFilter
import io.github.deviablo.email.data.StoredMessage
import io.github.deviablo.email.data.UndoToken
import io.github.deviablo.email.mail.imap.ImapAuthException
import io.github.deviablo.email.mail.imap.Security
import io.github.deviablo.email.mail.imap.ServerSettings
import io.github.deviablo.email.setup.DiscoveredSettings
import io.github.deviablo.email.setup.ServerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

data class AccountForm(
    val accountName: String = "",
    val displayName: String = "",
    val address: String = "",
    val host: String = "",
    val port: String = "993",
    val security: Security = Security.TLS,
    val username: String = "",
    val password: String = "",
    val smtpHost: String = "",
    val smtpPort: String = "465",
    val smtpSecurity: Security = Security.TLS,
)

data class SearchState(
    val query: String = "",
    val filters: Set<SearchFilter> = emptySet(),
    val results: List<Conversation> = emptyList(),
    val serverBusy: Boolean = false,
)

/** A short message at the bottom ("Gelöscht · Rückgängig"). */
data class Snack(
    val text: Int,
    val arg: String? = null,
    val undo: UndoToken? = null,
    val spamOf: List<StoredMessage> = emptyList(),
    val wasSpam: Boolean = false,
    /** "Undo" reopens the composer with this. */
    val reopen: ComposeRequest? = null,
)

data class MailUiState(
    val loaded: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val unreadByAccount: Map<Long, Int> = emptyMap(),
    val activeAccountId: Long? = null,
    val folders: List<Folder> = emptyList(),
    val currentFolderId: Long? = null,
    val conversations: List<Conversation> = emptyList(),
    val openKey: String? = null,
    val selection: Set<String> = emptySet(),
    val search: SearchState? = null,
    val syncing: Boolean = false,
    val syncError: Int? = null,
    val showAccountForm: Boolean = false,
    val formBusy: Boolean = false,
    val formError: Int? = null,
) {
    val activeAccount get() = accounts.firstOrNull { it.id == activeAccountId }
    val currentFolder get() = folders.firstOrNull { it.id == currentFolderId }
    val visibleConversations get() = search?.results ?: conversations
    val openConversation get() = visibleConversations.firstOrNull { it.key == openKey }
    fun folder(id: Long) = folders.firstOrNull { it.id == id }
}

class MailViewModel(app: Application) : AndroidViewModel(app) {
    val repo = MailRepository.get(app)
    private val db = repo.db
    private val _state = MutableStateFlow(MailUiState())
    val state: StateFlow<MailUiState> = _state
    // A channel, not a SharedFlow: snacks sent while the list is not on screen (e.g. from the
    // composer) wait until it is.
    private val _snacks = Channel<Snack>(Channel.BUFFERED)
    val snacks: Flow<Snack> = _snacks.receiveAsFlow()
    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            val accounts = io { db.accounts() }
            val remembered = repo.settings.activeAccountId
            val active = accounts.firstOrNull { it.id == remembered }?.id ?: accounts.firstOrNull()?.id
            _state.update { it.copy(loaded = true, accounts = accounts, activeAccountId = active, showAccountForm = accounts.isEmpty()) }
            reload()
            syncActive()
            io { repo.pruneBodies() }
        }
        viewModelScope.launch {
            repo.changes.collect { accountId -> if (accountId == _state.value.activeAccountId) reload() else refreshUnreadCounts() }
        }
    }

    // --- navigation ---

    fun openFolder(folderId: Long) {
        _state.update { it.copy(currentFolderId = folderId, openKey = null, selection = emptySet(), search = null) }
        viewModelScope.launch { reload() }
    }

    fun switchAccount(accountId: Long) {
        repo.settings.activeAccountId = accountId
        _state.update { it.copy(activeAccountId = accountId, currentFolderId = null, openKey = null, selection = emptySet(), search = null, syncError = null) }
        viewModelScope.launch { reload(); syncActive() }
    }

    /** Opening sets "read" explicitly (Linux app), for the whole conversation. */
    fun openConversation(c: Conversation?) {
        _state.update { it.copy(openKey = c?.key) }
        if (c != null && c.unread) viewModelScope.launch { io { repo.setRead(c.messages, true) } }
    }

    /** From a notification: switch to the account and open the mail's conversation. */
    fun openMessage(accountId: Long, messageId: Long?) {
        viewModelScope.launch {
            if (_state.value.accounts.none { it.id == accountId }) return@launch
            val m = messageId?.let { io { db.message(it) } }
            if (accountId != _state.value.activeAccountId) repo.settings.activeAccountId = accountId
            _state.update { it.copy(activeAccountId = accountId, currentFolderId = m?.folderId, openKey = null, search = null, selection = emptySet(), showAccountForm = false) }
            reload()
            val c = m?.let { msg -> _state.value.conversations.firstOrNull { conv -> conv.messages.any { it.id == msg.id } } }
            if (c != null) openConversation(c)
            syncActive()
        }
    }

    fun showAccountForm(show: Boolean) = _state.update { it.copy(showAccountForm = show, formError = null) }

    // --- selection ---

    fun toggleSelection(c: Conversation) = _state.update {
        val sel = if (c.key in it.selection) it.selection - c.key else it.selection + c.key
        it.copy(selection = sel)
    }

    fun selectAll() = _state.update { it.copy(selection = it.visibleConversations.map { c -> c.key }.toSet()) }
    fun clearSelection() = _state.update { it.copy(selection = emptySet()) }
    private fun selected(): List<Conversation> = _state.value.let { s -> s.visibleConversations.filter { it.key in s.selection } }

    // --- actions ---

    fun setRead(convs: List<Conversation>, read: Boolean) = launchIo { convs.forEach { repo.markConversationRead(it, read) } }
    fun setReadMessage(m: StoredMessage, read: Boolean) = launchIo { repo.setRead(listOf(m), read) }
    fun setStar(m: StoredMessage, star: Boolean) = launchIo { repo.setStar(m, star) }
    fun setStar(c: Conversation, star: Boolean) = setStar(c.newest, star)

    fun delete(convs: List<Conversation>) {
        if (convs.isEmpty()) return
        closeIfOpen(convs)
        launchIo {
            val token = repo.delete(convs.flatMap { it.inFolder })
            if (token != null) {
                _snacks.trySend(if (convs.size == 1) Snack(R.string.deleted, undo = token) else Snack(R.string.deleted_many, convs.size.toString(), token))
            }
        }
    }

    fun deleteMessage(m: StoredMessage) = launchIo {
        val token = repo.delete(listOf(m))
        if (token != null) _snacks.trySend(Snack(R.string.deleted, undo = token))
    }

    fun move(convs: List<Conversation>, target: Folder) {
        closeIfOpen(convs)
        launchIo {
            val token = repo.move(convs.flatMap { it.inFolder }, target)
            _snacks.trySend(Snack(R.string.moved_to, target.name, token))
        }
    }

    fun spam(convs: List<Conversation>, spam: Boolean) {
        closeIfOpen(convs)
        launchIo {
            val messages = convs.flatMap { it.inFolder }
            val token = repo.markSpam(messages, spam) ?: return@launchIo
            _snacks.trySend(Snack(if (spam) R.string.marked_spam else R.string.moved_to, null, token, messages, spam))
        }
    }

    fun undo(snack: Snack) {
        val token = snack.undo ?: return
        launchIo { repo.undo(token, snack.spamOf, snack.wasSpam) }
    }

    fun emptyCurrentFolder() {
        val folder = _state.value.currentFolder ?: return
        launchIo { repo.emptyFolder(folder) }
    }

    fun selectedConversations() = selected()

    private fun closeIfOpen(convs: List<Conversation>) {
        _state.update { s -> s.copy(openKey = if (convs.any { it.key == s.openKey }) null else s.openKey, selection = emptySet()) }
    }

    // --- search ---

    fun openSearch() = _state.update { it.copy(search = SearchState(), openKey = null, selection = emptySet()) }
    fun closeSearch() = _state.update { it.copy(search = null, openKey = null) }

    fun updateSearch(query: String? = null, toggle: SearchFilter? = null) {
        val current = _state.value.search ?: SearchState()
        val filters = toggle?.let { if (it in current.filters) current.filters - it else current.filters + it } ?: current.filters
        val next = current.copy(query = query ?: current.query, filters = filters)
        _state.update { it.copy(search = next) }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(250)
            runSearch(next)
        }
    }

    private suspend fun runSearch(s: SearchState) {
        val account = _state.value.activeAccount ?: return
        val folder = _state.value.currentFolder
        val words = s.query.split(Regex("\\s+")).map { it.filter { c -> c.isLetterOrDigit() || c == '@' || c == '.' || c == '-' } }.filter { it.isNotEmpty() }
        if (words.isEmpty() && s.filters.none { it != SearchFilter.THIS_FOLDER }) {
            _state.update { st -> st.copy(search = st.search?.copy(results = emptyList())) }
            return
        }
        // Every word as a word prefix; quoting keeps OR/NOT/NEAR from acting as operators.
        val fts = words.joinToString(" ") { "\"${it.replace("\"", "")}*\"" }
        val folderId = folder?.id?.takeIf { SearchFilter.THIS_FOLDER in s.filters }
        val results = io { repo.search(account.id, if (words.isEmpty()) "" else fts, folderId, s.filters, emptySet()) }
        _state.update { st -> st.copy(search = st.search?.copy(results = results)) }
        // Bodies arrive only when opened, so the server is asked too (Linux app). Words with
        // umlauts would need IMAP literals in SEARCH; those searches stay local.
        if (words.isNotEmpty() && words.all { w -> w.all { it.code < 128 } }) {
            delay(250)
            _state.update { st -> st.copy(search = st.search?.copy(serverBusy = true)) }
            val serverHits = io { runCatching { repo.searchOnServer(account, words, folderId) }.getOrDefault(emptySet()) }
            val merged = if (serverHits.isEmpty()) results else io { repo.search(account.id, fts, folderId, s.filters, serverHits) }
            if (_state.value.search?.query == s.query) _state.update { st -> st.copy(search = st.search?.copy(results = merged, serverBusy = false)) }
        }
    }

    // --- sync and accounts ---

    fun snack(s: Snack) {
        _snacks.trySend(s)
    }

    private var lastSync = 0L

    /** Back in the app: fetch news if the last check is more than a minute ago. */
    fun onResume() {
        if (System.currentTimeMillis() - lastSync > 60_000) syncActive()
    }

    fun syncActive() {
        val account = _state.value.activeAccount ?: return
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, syncError = null) }
            lastSync = System.currentTimeMillis()
            val result = io { runCatching { repo.syncAccount(account) } }
            result.getOrNull()?.let { Notifier.newMail(getApplication(), account, it.newInboxMail) }
            _state.update { it.copy(syncing = false, syncError = result.exceptionOrNull()?.let(::friendlyError)) }
            if ((result.getOrNull()?.rejected ?: 0) > 0) _snacks.trySend(Snack(R.string.server_refused))
            reload()
        }
    }

    /** Debug builds only: adds the local test account without the setup screens. */
    fun addAccount(form: AccountForm) {
        val port = form.port.toIntOrNull() ?: return
        val settings = DiscoveredSettings(
            ServerConfig(form.host.trim(), port, form.security),
            ServerConfig(form.smtpHost.trim().ifBlank { form.host.trim() }, form.smtpPort.toIntOrNull() ?: 465, form.smtpSecurity),
            form.username.ifBlank { form.address.trim() },
        )
        viewModelScope.launch {
            val error = io { runCatching { repo.check(ServerSettings(settings.imap.host, port, form.security, settings.username), form.password) }.exceptionOrNull() }
            if (error != null) { friendlyError(error); return@launch }
            if (createAccount(form.address.trim(), form.displayName.trim(), form.accountName.ifBlank { "Test" }, settings, form.password)) showAccountForm(false)
        }
    }

    /** Stores a checked account, makes it the active one and starts loading its mail. */
    suspend fun createAccount(address: String, displayName: String, label: String, s: DiscoveredSettings, password: String): Boolean {
        val account = Account(
            id = 0, name = label, displayName = displayName, address = address,
            imapHost = s.imap.host, imapPort = s.imap.port, imapSecurity = s.imap.security, username = s.username,
            smtpHost = s.smtp.host, smtpPort = s.smtp.port, smtpSecurity = s.smtp.security,
        )
        val id = io { runCatching { db.insertAccount(account, PasswordVault.encrypt(password)) }.getOrNull() } ?: return false
        val accounts = io { db.accounts() }
        repo.settings.activeAccountId = id
        _state.update { it.copy(accounts = accounts, activeAccountId = id, currentFolderId = null, openKey = null, search = null, selection = emptySet()) }
        reload()
        syncActive()
        return true
    }

    fun reloadAccounts() = viewModelScope.launch {
        val accounts = io { db.accounts() }
        _state.update { s ->
            val active = accounts.firstOrNull { it.id == s.activeAccountId }?.id ?: accounts.firstOrNull()?.id
            s.copy(accounts = accounts, activeAccountId = active, showAccountForm = accounts.isEmpty())
        }
        reload()
    }

    private suspend fun reload() {
        val s = _state.value
        val accountId = s.activeAccountId ?: return
        val folders = io { db.folders(accountId) }
        val folder = folders.firstOrNull { it.id == s.currentFolderId } ?: folders.firstOrNull { it.role == FolderRole.INBOX }
        val conversations = folder?.let { io { repo.conversations(accountId, it) } }.orEmpty()
        _state.update { st ->
            st.copy(
                folders = folders, currentFolderId = folder?.id, conversations = conversations,
                selection = st.selection.filter { key -> conversations.any { it.key == key } }.toSet(),
            )
        }
        _state.value.search?.let { runSearch(it) }
        refreshUnreadCounts()
    }

    private suspend fun refreshUnreadCounts() {
        val counts = io { _state.value.accounts.associate { a -> a.id to (db.folders(a.id).firstOrNull { it.role == FolderRole.INBOX }?.unread ?: 0) } }
        _state.update { it.copy(unreadByAccount = counts) }
    }

    private fun friendlyError(e: Throwable): Int {
        // Class and message only: they never contain the password.
        Log.w("E-Mail", "Connection problem: ${e.javaClass.name}: ${e.message}", if (BuildConfig.DEBUG) e else null)
        return when (e) {
            is ImapAuthException -> R.string.error_login
            is UnknownHostException -> R.string.error_unknown_host
            is SSLException -> R.string.error_tls
            is ConnectException, is SocketTimeoutException -> R.string.error_unreachable
            else -> R.string.error_generic
        }
    }

    fun viewModelScopeLaunchIo(block: suspend () -> Unit) = launchIo(block)

    private fun launchIo(block: suspend () -> Unit) = viewModelScope.launch(Dispatchers.IO) { block() }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
