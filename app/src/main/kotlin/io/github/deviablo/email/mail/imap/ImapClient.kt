// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail.imap

import io.github.deviablo.email.mail.MimeWords
import java.io.BufferedInputStream
import java.io.Closeable
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLPeerUnverifiedException
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

enum class Security { TLS, STARTTLS }

data class ServerSettings(val host: String, val port: Int, val security: Security, val username: String)

data class ImapFolder(val rawName: String, val name: String, val delimiter: String?, val attributes: Set<String>) {
    val selectable get() = attributes.none { it.equals("\\Noselect", true) || it.equals("\\NonExistent", true) }
}

data class SelectedFolder(val exists: Long, val uidValidity: Long?, val uidNext: Long?)

data class Address(val name: String, val address: String)

data class MessageHeader(
    val uid: Long,
    val subject: String,
    val from: Address?,
    val date: Date?,
    val messageId: String?,
    val inReplyTo: String?,
    val seen: Boolean,
    val flagged: Boolean,
    val hasAttachment: Boolean,
)

/** Login was rejected – the user has to fix the password, not the server settings. */
class ImapAuthException(message: String) : Exception(message)

class ImapCommandException(message: String) : Exception(message)

/**
 * A minimal IMAP4rev1/rev2 client: exactly the commands E-Mail needs, nothing more.
 * Connections are always encrypted (TLS from the start, or STARTTLS on port 143).
 * Blocking; callers run it on Dispatchers.IO.
 */
class ImapClient private constructor(
    private var socket: Socket,
    private val socketFactory: SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier?,
    private val host: String,
) : Closeable {
    private var reader = ImapReader(BufferedInputStream(socket.getInputStream()))
    private var output: OutputStream = socket.getOutputStream()
    private var tagCounter = 0
    var capabilities: Set<String> = emptySet()
        private set

    companion object {
        private const val TIMEOUT_MS = 30_000

        /**
         * [hostnameVerifier] is an extra check on top of the TLS endpoint identification; Android's
         * default verifier is used in the app. Plain JVM tests pass null because the JDK's default
         * verifier rejects everything outside HttpsURLConnection.
         */
        fun connect(
            settings: ServerSettings,
            socketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
            hostnameVerifier: HostnameVerifier? = HttpsURLConnection.getDefaultHostnameVerifier(),
        ): ImapClient {
            val client = when (settings.security) {
                Security.TLS -> {
                    val plain = Socket()
                    plain.connect(InetSocketAddress(settings.host, settings.port), TIMEOUT_MS)
                    plain.soTimeout = TIMEOUT_MS
                    val tls = secure(plain, socketFactory, hostnameVerifier, settings.host)
                    ImapClient(tls, socketFactory, hostnameVerifier, settings.host)
                }
                Security.STARTTLS -> {
                    val plain = Socket()
                    plain.connect(InetSocketAddress(settings.host, settings.port), TIMEOUT_MS)
                    plain.soTimeout = TIMEOUT_MS
                    ImapClient(plain, socketFactory, hostnameVerifier, settings.host)
                }
            }
            try {
                client.readGreeting()
                if (settings.security == Security.STARTTLS) client.startTls()
                if (client.capabilities.isEmpty()) client.capability()
            } catch (e: Exception) {
                client.close()
                throw e
            }
            return client
        }

        /** Wraps a connected socket in TLS and checks that the certificate belongs to [host]. */
        private fun secure(plain: Socket, factory: SSLSocketFactory, verifier: HostnameVerifier?, host: String): SSLSocket {
            val tls = factory.createSocket(plain, host, plain.port, true) as SSLSocket
            tls.soTimeout = TIMEOUT_MS
            tls.sslParameters = tls.sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
            try {
                tls.startHandshake()
                if (verifier != null && !verifier.verify(host, tls.session)) {
                    throw SSLPeerUnverifiedException("Certificate does not match $host")
                }
            } catch (e: Exception) {
                runCatching { tls.close() }
                throw e
            }
            return tls
        }
    }

    private fun readGreeting() {
        val greeting = reader.readResponse()
        if (greeting.kind == "BYE") throw ImapCommandException(greeting.text)
        parseCapabilityCode(greeting.text)
    }

    private fun startTls() {
        if (capabilities.isEmpty()) capability()
        if (capabilities.none { it.equals("STARTTLS", true) }) {
            throw ImapCommandException("Server does not offer STARTTLS")
        }
        command("STARTTLS")
        val tls = secure(socket, socketFactory, hostnameVerifier, host)
        socket = tls
        reader = ImapReader(BufferedInputStream(tls.getInputStream()))
        output = tls.getOutputStream()
        capabilities = emptySet() // must be re-read after TLS (RFC 9051 6.2.1)
        capability()
    }

    fun capability(): Set<String> {
        val responses = command("CAPABILITY")
        responses.filter { it.kind == "CAPABILITY" }.forEach { r ->
            capabilities = r.values.mapNotNull { it.text()?.uppercase() }.toSet()
        }
        return capabilities
    }

    /**
     * Prefers AUTHENTICATE PLAIN: it defines UTF-8 for user name and password, while LOGIN
     * leaves non-ASCII passwords up to each server.
     */
    fun login(username: String, password: String) {
        val result = runCatching {
            if ("AUTH=PLAIN" in capabilities) authenticatePlain(username, password)
            else commandWithStrings("LOGIN", username, password)
        }
        result.exceptionOrNull()?.let { if (it is java.io.IOException) throw it else throw ImapAuthException(it.message ?: "Login failed") }
        val tagged = result.getOrThrow().last()
        parseCapabilityCode(tagged.text)
        if (capabilities.isEmpty() || capabilities.none { it.startsWith("IMAP4") }) capability()
    }

    fun list(): List<ImapFolder> = command("LIST \"\" \"*\"").filter { it.kind == "LIST" }.mapNotNull { r ->
        val attributes = r.values.getOrNull(0)?.items()?.mapNotNull { it.text() }?.toSet() ?: return@mapNotNull null
        val delimiter = r.values.getOrNull(1)?.text()
        val raw = r.values.getOrNull(2)?.text() ?: return@mapNotNull null
        val utf8 = capabilities.contains("UTF8=ACCEPT") || capabilities.contains("IMAP4REV2")
        ImapFolder(raw, if (utf8) raw else ModifiedUtf7.decode(raw), delimiter, attributes)
    }

    fun select(rawName: String): SelectedFolder {
        var exists = 0L
        var uidValidity: Long? = null
        var uidNext: Long? = null
        for (r in commandWithStrings("EXAMINE", rawName)) {
            if (r.kind == "EXISTS") exists = r.number ?: 0
            if (r.kind == "OK") {
                responseCode(r.text, "UIDVALIDITY")?.toLongOrNull()?.let { uidValidity = it }
                responseCode(r.text, "UIDNEXT")?.toLongOrNull()?.let { uidNext = it }
            }
        }
        if (uidValidity == null) {
            // Some servers occasionally omit UIDVALIDITY right after IDLE (seen with GreenMail in
            // the Linux app). STATUS reliably reports it.
            commandWithStrings("STATUS", rawName, raw = "(UIDVALIDITY MESSAGES)").firstOrNull { it.kind == "STATUS" }?.let { s ->
                val items = s.values.getOrNull(1)?.items().orEmpty()
                items.chunked(2).forEach { (k, v) ->
                    when (k.text()?.uppercase()) {
                        "UIDVALIDITY" -> uidValidity = v.text()?.toLongOrNull()
                        "MESSAGES" -> exists = v.text()?.toLongOrNull() ?: exists
                    }
                }
            }
        }
        return SelectedFolder(exists, uidValidity, uidNext)
    }

    fun uidSearchSince(since: Date): List<Long> {
        val fmt = SimpleDateFormat("d-MMM-yyyy", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return command("UID SEARCH SINCE ${fmt.format(since)}")
            .filter { it.kind == "SEARCH" }
            .flatMap { r -> r.values.mapNotNull { it.text()?.toLongOrNull() } }
    }

    fun uidFetchHeaders(uids: List<Long>): List<MessageHeader> {
        if (uids.isEmpty()) return emptyList()
        return command("UID FETCH ${uids.joinToString(",")} (UID FLAGS INTERNALDATE ENVELOPE BODYSTRUCTURE)")
            .filter { it.kind == "FETCH" }
            .mapNotNull { parseFetch(it.values.firstOrNull()?.items().orEmpty()) }
    }

    fun logout() {
        runCatching { command("LOGOUT") }
        close()
    }

    override fun close() {
        runCatching { socket.close() }
    }

    // --- protocol plumbing ---

    private fun nextTag() = "A%03d".format(++tagCounter)

    private fun command(line: String): List<ImapResponse> {
        val tag = nextTag()
        send("$tag $line\r\n")
        return collect(tag)
    }

    /** Sends a command whose arguments are user-supplied strings, quoting or as literals. */
    private fun commandWithStrings(verb: String, vararg args: String, raw: String? = null): List<ImapResponse> {
        val tag = nextTag()
        val literalPlus = capabilities.contains("LITERAL+") || capabilities.contains("LITERAL-")
        val sb = StringBuilder("$tag $verb")
        for (arg in args) {
            sb.append(' ')
            if (isQuotable(arg)) {
                sb.append('"').append(arg.replace("\\", "\\\\").replace("\"", "\\\"")).append('"')
            } else {
                val bytes = arg.toByteArray(Charsets.UTF_8)
                sb.append('{').append(bytes.size).append(if (literalPlus) "+}" else "}").append("\r\n")
                send(sb.toString()); sb.clear()
                if (!literalPlus) {
                    val cont = reader.readResponse()
                    if (cont.tag != "+") throw ImapCommandException(cont.text)
                }
                output.write(bytes)
            }
        }
        if (raw != null) sb.append(' ').append(raw)
        sb.append("\r\n")
        send(sb.toString())
        return collect(tag)
    }

    private fun authenticatePlain(username: String, password: String): List<ImapResponse> {
        val token = java.util.Base64.getEncoder().encodeToString("\u0000$username\u0000$password".toByteArray(Charsets.UTF_8))
        val tag = nextTag()
        if ("SASL-IR" in capabilities) {
            send("$tag AUTHENTICATE PLAIN $token\r\n")
        } else {
            send("$tag AUTHENTICATE PLAIN\r\n")
            val cont = reader.readResponse()
            if (cont.tag != "+") throw ImapCommandException(cont.text)
            send("$token\r\n")
        }
        return collect(tag)
    }

    private fun isQuotable(s: String) = s.all { it.code in 0x20..0x7e }

    private fun send(text: String) {
        output.write(text.toByteArray(Charsets.UTF_8))
        output.flush()
    }

    private fun collect(tag: String): List<ImapResponse> {
        val responses = mutableListOf<ImapResponse>()
        while (true) {
            val r = reader.readResponse()
            responses += r
            if (r.tag == tag) {
                if (!r.isOk) throw ImapCommandException(r.text.ifBlank { r.kind })
                return responses
            }
            if (r.tag == "*" && r.kind == "BYE") throw ImapCommandException(r.text)
        }
    }

    private fun parseCapabilityCode(text: String) {
        responseCode(text, "CAPABILITY")?.let { caps -> capabilities = caps.split(' ').map { it.uppercase() }.toSet() }
    }

    private fun responseCode(text: String, name: String): String? {
        if (!text.startsWith("[")) return null
        val code = text.substring(1, text.indexOf(']').takeIf { it > 0 } ?: return null)
        return if (code.startsWith("$name ", ignoreCase = true)) code.substring(name.length + 1).trim() else null
    }

    private fun parseFetch(items: List<ImapValue>): MessageHeader? {
        val map = items.chunked(2).filter { it.size == 2 }.associate { (k, v) -> (k.text()?.uppercase() ?: "") to v }
        val uid = map["UID"]?.text()?.toLongOrNull() ?: return null
        val flags = map["FLAGS"]?.items()?.mapNotNull { it.text()?.lowercase() }.orEmpty()
        val env = map["ENVELOPE"]?.items().orEmpty()
        val internal = map["INTERNALDATE"]?.text()?.let { runCatching { internalDate.parse(it) }.getOrNull() }
        val from = env.getOrNull(2)?.items()?.firstOrNull()?.items()?.let { a ->
            val mailbox = a.getOrNull(2)?.text().orEmpty()
            val host = a.getOrNull(3)?.text().orEmpty()
            Address(MimeWords.decode(a.getOrNull(0)?.text()), if (host.isEmpty()) mailbox else "$mailbox@$host")
        }
        return MessageHeader(
            uid = uid,
            subject = MimeWords.decode(env.getOrNull(1)?.text()),
            from = from,
            date = internal,
            messageId = env.getOrNull(9)?.text(),
            inReplyTo = env.getOrNull(8)?.text(),
            seen = "\\seen" in flags,
            flagged = "\\flagged" in flags,
            hasAttachment = map["BODYSTRUCTURE"]?.let(::hasAttachment) ?: false,
        )
    }

    private val internalDate = SimpleDateFormat("d-MMM-yyyy HH:mm:ss Z", Locale.US)

    /** True if any body part carries an "attachment" disposition. */
    private fun hasAttachment(value: ImapValue): Boolean {
        val items = value.items()
        items.forEachIndexed { i, item ->
            if (item.text().equals("attachment", ignoreCase = true) && items.getOrNull(i + 1) is ImapValue.Items) return true
            if (item is ImapValue.Items && hasAttachment(item)) return true
        }
        return false
    }
}
