// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.digistalter.email.mail.imap

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream

sealed interface ImapValue {
    data class Atom(val value: String) : ImapValue
    data class Str(val value: String) : ImapValue
    /** Literal kept byte for byte (whole messages are not necessarily UTF-8). */
    class Literal(val bytes: ByteArray) : ImapValue
    data class Items(val items: List<ImapValue>) : ImapValue
    data object Nil : ImapValue
}

fun ImapValue.text(): String? = when (this) {
    is ImapValue.Atom -> value
    is ImapValue.Str -> value
    is ImapValue.Literal -> String(bytes, Charsets.UTF_8)
    else -> null
}

fun ImapValue.bytes(): ByteArray? = when (this) {
    is ImapValue.Literal -> bytes
    is ImapValue.Str -> value.toByteArray(Charsets.UTF_8)
    else -> null
}

fun ImapValue.items(): List<ImapValue> = (this as? ImapValue.Items)?.items.orEmpty()

/**
 * One server response. [kind] is OK/NO/BAD/BYE/PREAUTH, a data keyword (LIST, SEARCH …) or,
 * for numbered responses like "* 3 FETCH", the keyword after the number (then [number] is set).
 */
data class ImapResponse(
    val tag: String,
    val kind: String,
    val number: Long? = null,
    val values: List<ImapValue> = emptyList(),
    val text: String = "",
) {
    val isOk get() = kind == "OK"
}

class ImapProtocolException(message: String) : Exception(message)

/** Reads IMAP responses (RFC 9051 syntax) straight from the socket, including literals. */
class ImapReader(private val input: InputStream) {
    private var peeked = -1

    private fun read(): Int {
        if (peeked >= 0) return peeked.also { peeked = -1 }
        val b = input.read()
        if (b < 0) throw EOFException("Connection closed by server")
        return b
    }

    private fun peek(): Int {
        if (peeked < 0) peeked = read()
        return peeked
    }

    fun readResponse(): ImapResponse {
        val tag = readAtom()
        expectSpaceOrEnd()
        if (tag == "+") return ImapResponse(tag, "+", text = readRestOfLine())
        val first = readAtom()
        val number = first.toLongOrNull()
        val kind = if (number != null) {
            expectSpaceOrEnd()
            readAtom().uppercase()
        } else {
            first.uppercase()
        }
        return when (kind) {
            "OK", "NO", "BAD", "BYE", "PREAUTH" -> {
                if (peek() == ' '.code) read()
                ImapResponse(tag, kind, number, text = readRestOfLine())
            }
            else -> ImapResponse(tag, kind, number, values = readValuesUntilEol())
        }
    }

    private fun expectSpaceOrEnd() {
        if (peek() == ' '.code) read()
    }

    private fun readValuesUntilEol(): List<ImapValue> {
        val values = mutableListOf<ImapValue>()
        while (true) {
            when (peek()) {
                ' '.code -> read()
                '\r'.code -> { read(); if (peek() == '\n'.code) read(); return values }
                '\n'.code -> { read(); return values }
                else -> values += readValue()
            }
        }
    }

    private fun readValue(): ImapValue = when (peek()) {
        '('.code -> readList()
        '"'.code -> ImapValue.Str(readQuoted())
        '{'.code -> ImapValue.Literal(readLiteral())
        else -> readAtom().let { if (it.equals("NIL", ignoreCase = true)) ImapValue.Nil else ImapValue.Atom(it) }
    }

    private fun readList(): ImapValue.Items {
        read()
        val items = mutableListOf<ImapValue>()
        while (true) {
            when (peek()) {
                ' '.code -> read()
                ')'.code -> { read(); return ImapValue.Items(items) }
                '\r'.code, '\n'.code -> throw ImapProtocolException("Unterminated list")
                else -> items += readValue()
            }
        }
    }

    private fun readQuoted(): String {
        read()
        val out = ByteArrayOutputStream()
        while (true) {
            val b = read()
            when (b) {
                '"'.code -> return String(out.toByteArray(), Charsets.UTF_8)
                '\\'.code -> out.write(read())
                '\r'.code, '\n'.code -> throw ImapProtocolException("Unterminated quoted string")
                else -> out.write(b)
            }
        }
    }

    private fun readLiteral(): ByteArray {
        read()
        val digits = StringBuilder()
        while (peek() != '}'.code) digits.append(read().toChar())
        read()
        if (read() != '\r'.code || read() != '\n'.code) throw ImapProtocolException("Bad literal header")
        val size = digits.toString().removeSuffix("+").toIntOrNull() ?: throw ImapProtocolException("Bad literal size")
        val bytes = ByteArray(size)
        var offset = 0
        if (peeked >= 0 && size > 0) { bytes[0] = peeked.toByte(); peeked = -1; offset = 1 }
        while (offset < size) {
            val n = input.read(bytes, offset, size - offset)
            if (n < 0) throw EOFException("Connection closed inside literal")
            offset += n
        }
        return bytes
    }

    /** Atoms may contain a bracketed section with spaces, e.g. BODY[HEADER.FIELDS (DATE)]. */
    private fun readAtom(): String {
        val sb = StringBuilder()
        var depth = 0
        while (true) {
            val c = peek()
            if (depth == 0 && (c == ' '.code || c == '('.code || c == ')'.code || c == '\r'.code || c == '\n'.code)) break
            if (depth == 0 && c == '"'.code && sb.isEmpty()) break
            read()
            if (c == '['.code) depth++
            if (c == ']'.code && depth > 0) depth--
            sb.append(c.toChar())
        }
        return sb.toString()
    }

    private fun readRestOfLine(): String {
        val out = ByteArrayOutputStream()
        while (true) {
            val b = read()
            if (b == '\r'.code) { if (peek() == '\n'.code) read(); break }
            if (b == '\n'.code) break
            out.write(b)
        }
        return String(out.toByteArray(), Charsets.UTF_8)
    }
}
