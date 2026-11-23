// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.deviablo.email.BuildConfig
import io.github.deviablo.email.R
import io.github.deviablo.email.data.Account
import io.github.deviablo.email.data.Folder
import io.github.deviablo.email.data.FolderRole
import io.github.deviablo.email.data.MailDatabase
import io.github.deviablo.email.data.MailSync
import io.github.deviablo.email.data.PasswordVault
import io.github.deviablo.email.data.StoredMessage
import io.github.deviablo.email.mail.imap.ImapAuthException
import io.github.deviablo.email.mail.imap.Security
import io.github.deviablo.email.mail.imap.ServerSettings
import kotlinx.coroutines.Dispatchers
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
)

data class MailUiState(
    val loaded: Boolean = false,
    val accounts: List<Account> = emptyList(),
    val activeAccountId: Long? = null,
    val folders: List<Folder> = emptyList(),
    val currentFolderId: Long? = null,
    val messages: List<StoredMessage> = emptyList(),
    val syncing: Boolean = false,
    val syncError: Int? = null,
    val showAccountForm: Boolean = false,
    val formBusy: Boolean = false,
    val formError: Int? = null,
) {
    val activeAccount get() = accounts.firstOrNull { it.id == activeAccountId }
    val currentFolder get() = folders.firstOrNull { it.id == currentFolderId }
}

class MailViewModel(app: Application) : AndroidViewModel(app) {
    private val db = MailDatabase(app)
    private val sync = MailSync(db)
    private val _state = MutableStateFlow(MailUiState())
    val state: StateFlow<MailUiState> = _state

    init {
        viewModelScope.launch {
            val accounts = io { db.accounts() }
            _state.update { it.copy(loaded = true, accounts = accounts, activeAccountId = accounts.firstOrNull()?.id, showAccountForm = accounts.isEmpty()) }
            reload()
            syncActive()
        }
    }

    fun openFolder(folderId: Long) {
        _state.update { it.copy(currentFolderId = folderId) }
        viewModelScope.launch { reload() }
    }

    fun switchAccount(accountId: Long) {
        _state.update { it.copy(activeAccountId = accountId, currentFolderId = null, syncError = null) }
        viewModelScope.launch { reload(); syncActive() }
    }

    fun showAccountForm(show: Boolean) = _state.update { it.copy(showAccountForm = show, formError = null) }

    fun syncActive() {
        val account = _state.value.activeAccount ?: return
        if (_state.value.syncing) return
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, syncError = null) }
            val error = io {
                runCatching {
                    val password = PasswordVault.decrypt(db.encryptedPassword(account.id) ?: error("no password"))
                    sync.sync(account, password)
                }.exceptionOrNull()
            }
            _state.update { it.copy(syncing = false, syncError = error?.let(::friendlyError)) }
            reload()
        }
    }

    fun addAccount(form: AccountForm) {
        val port = form.port.toIntOrNull()
        if (form.address.isBlank() || form.host.isBlank() || port == null || form.password.isEmpty()) {
            _state.update { it.copy(formError = R.string.error_form_incomplete) }
            return
        }
        val username = form.username.ifBlank { form.address.trim() }
        val settings = ServerSettings(form.host.trim(), port, form.security, username)
        viewModelScope.launch {
            _state.update { it.copy(formBusy = true, formError = null) }
            val error = io { runCatching { sync.check(settings, form.password) }.exceptionOrNull() }
            if (error != null) {
                _state.update { it.copy(formBusy = false, formError = friendlyError(error)) }
                return@launch
            }
            val account = Account(
                id = 0,
                name = form.accountName.ifBlank { form.address.substringAfter('@').substringBefore('.').replaceFirstChar { it.uppercase() } },
                displayName = form.displayName.trim(),
                address = form.address.trim(),
                imapHost = settings.host, imapPort = port, imapSecurity = form.security, username = username,
            )
            val id = io { db.insertAccount(account, PasswordVault.encrypt(form.password)) }
            val accounts = io { db.accounts() }
            _state.update { it.copy(formBusy = false, showAccountForm = false, accounts = accounts, activeAccountId = id, currentFolderId = null) }
            reload()
            syncActive()
        }
    }

    private suspend fun reload() {
        val s = _state.value
        val accountId = s.activeAccountId ?: return
        val folders = io { db.folders(accountId) }
        val folderId = s.currentFolderId?.takeIf { id -> folders.any { it.id == id } }
            ?: folders.firstOrNull { it.role == FolderRole.INBOX }?.id
        val messages = folderId?.let { io { db.messages(it) } }.orEmpty()
        _state.update { it.copy(folders = folders, currentFolderId = folderId, messages = messages) }
    }

    private fun friendlyError(e: Throwable): Int {
        // Class and message only: they never contain the password.
        android.util.Log.w("E-Mail", "Connection problem: ${e.javaClass.name}: ${e.message}", if (BuildConfig.DEBUG) e else null)
        return when (e) {
        is ImapAuthException -> R.string.error_login
        is UnknownHostException -> R.string.error_unknown_host
        is SSLException -> R.string.error_tls
        is ConnectException, is SocketTimeoutException -> R.string.error_unreachable
        else -> R.string.error_generic
        }
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
