// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail.smtp

import io.github.deviablo.email.mail.imap.ImapClient
import io.github.deviablo.email.mail.imap.Security
import io.github.deviablo.email.mail.imap.ServerSettings
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocketFactory

/** The server refused user name or password. */
class SmtpAuthException(message: String) : Exception(message)

/** The server refused the message for good (5xx), e.g. an unknown recipient. Retrying will not help. */
class SmtpRejectedException(message: String) : Exception(message)

/** Any other answer that did not fit, usually temporary (4xx). */
class SmtpException(message: String) : IOException(message)

/**
 * A small SMTP client (RFC 5321): implicit TLS or STARTTLS, never plain text, AUTH PLAIN or LOGIN.
 * The EHLO name is the address literal [127.0.0.1] so the device's name never leaves it.
 */
class SmtpClient private constructor(
    private var socket: Socket,
    private val socketFactory: SSLSocketFactory,
    private val hostnameVerifier: HostnameVerifier?,
    private val host: String,
) : AutoCloseable {
    private var input: InputStream = BufferedInputStream(socket.getInputStream())
    private var output: OutputStream = socket.getOutputStream()
    var extensions: List<String> = emptyList()
        private set

    data class Reply(val code: Int, val lines: List<String>) {
        val text get() = lines.joinToString(" ")
    }

    companion object {
        private const val TIMEOUT_MS = 60_000

        fun connect(
            settings: ServerSettings,
            socketFactory: SSLSocketFactory = SSLSocketFactory.getDefault() as SSLSocketFactory,
            hostnameVerifier: HostnameVerifier? = HttpsURLConnection.getDefaultHostnameVerifier(),
        ): SmtpClient {
            val plain = Socket()
            plain.connect(InetSocketAddress(settings.host, settings.port), TIMEOUT_MS)
            plain.soTimeout = TIMEOUT_MS
            val first = if (settings.security == Security.TLS) ImapClient.secure(plain, socketFactory, hostnameVerifier, settings.host) else plain
            val client = SmtpClient(first, socketFactory, hostnameVerifier, settings.host)
            try {
                client.expect(client.read(), 220)
                client.ehlo()
                if (settings.security == Security.STARTTLS) client.startTls()
            } catch (e: Exception) {
                client.close()
                throw e
            }
            return client
        }
    }

    private fun ehlo() {
        val reply = command("EHLO [127.0.0.1]")
        expect(reply, 250)
        extensions = reply.lines.drop(1).map { it.uppercase() }
    }

    private fun startTls() {
        if (extensions.none { it == "STARTTLS" }) throw SmtpException("Server does not offer STARTTLS")
        expect(command("STARTTLS"), 220)
        val tls = ImapClient.secure(socket, socketFactory, hostnameVerifier, host)
        socket = tls
        input = BufferedInputStream(tls.getInputStream())
        output = tls.getOutputStream()
        ehlo() // the list may differ after TLS (RFC 3207)
    }

    private fun authMechanisms(): Set<String> =
        extensions.filter { it.startsWith("AUTH ") || it.startsWith("AUTH=") }
            .flatMap { it.substring(5).split(' ') }.map { it.trim() }.toSet()

    fun login(username: String, password: String) {
        val b64 = java.util.Base64.getEncoder()
        val mechanisms = authMechanisms()
        val reply = if ("PLAIN" in mechanisms || "LOGIN" !in mechanisms) {
            command("AUTH PLAIN " + b64.encodeToString("\u0000$username\u0000$password".toByteArray(Charsets.UTF_8)), secret = true)
        } else {
            expect(command("AUTH LOGIN"), 334)
            expect(command(b64.encodeToString(username.toByteArray(Charsets.UTF_8)), secret = true), 334)
            command(b64.encodeToString(password.toByteArray(Charsets.UTF_8)), secret = true)
        }
        if (reply.code != 235) {
            if (reply.code == 535 || reply.code == 534 || reply.code == 530) throw SmtpAuthException(reply.text)
            fail(reply)
        }
    }

    /** Sends [message] (CRLF line ends) to [recipients]. Envelope only: headers are not looked at. */
    fun send(from: String, recipients: List<String>, message: ByteArray) {
        val eightBit = extensions.any { it == "8BITMIME" }
        expect(command("MAIL FROM:<$from>" + if (eightBit) " BODY=8BITMIME" else ""), 250)
        for (r in recipients) {
            val reply = command("RCPT TO:<$r>")
            if (reply.code != 250 && reply.code != 251) fail(reply)
        }
        expect(command("DATA"), 354)
        output.write(dotStuff(message))
        output.write(".\r\n".toByteArray())
        output.flush()
        expect(read(), 250)
    }

    fun quit() {
        runCatching { command("QUIT") }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun command(line: String, @Suppress("UNUSED_PARAMETER") secret: Boolean = false): Reply {
        if (line.any { it == '\r' || it == '\n' }) throw SmtpException("Line break in command")
        output.write((line + "\r\n").toByteArray(Charsets.UTF_8))
        output.flush()
        return read()
    }

    private fun read(): Reply {
        val lines = mutableListOf<String>()
        var code: Int
        while (true) {
            val line = readLine() ?: throw IOException("Connection closed")
            if (line.length < 3) throw SmtpException("Malformed reply")
            code = line.substring(0, 3).toIntOrNull() ?: throw SmtpException("Malformed reply")
            lines += line.drop(4)
            if (line.length == 3 || line[3] != '-') break
        }
        return Reply(code, lines)
    }

    private fun readLine(): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            buf.write(b)
            if (buf.size() > 64 * 1024) throw SmtpException("Reply too long")
        }
    }

    private fun expect(reply: Reply, code: Int) {
        if (reply.code != code) fail(reply)
    }

    private fun fail(reply: Reply): Nothing =
        if (reply.code in 500..599) throw SmtpRejectedException("${reply.code} ${reply.text}")
        else throw SmtpException("${reply.code} ${reply.text}")
}

/** Normalises line ends to CRLF, doubles leading dots (RFC 5321 4.5.2) and ends with CRLF. */
internal fun dotStuff(message: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(message.size + 64)
    var lineStart = true
    var i = 0
    while (i < message.size) {
        val b = message[i]
        when {
            b == '\r'.code.toByte() && i + 1 < message.size && message[i + 1] == '\n'.code.toByte() -> {
                out.write('\r'.code); out.write('\n'.code); i += 2; lineStart = true; continue
            }
            b == '\n'.code.toByte() || b == '\r'.code.toByte() -> {
                out.write('\r'.code); out.write('\n'.code); i++; lineStart = true; continue
            }
            lineStart && b == '.'.code.toByte() -> out.write('.'.code)
        }
        out.write(b.toInt())
        lineStart = false
        i++
    }
    if (!lineStart) { out.write('\r'.code); out.write('\n'.code) }
    return out.toByteArray()
}
