// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail.imap

import java.util.Base64

/** Folder names in IMAP use "modified UTF-7" (RFC 3501 5.1.3), e.g. "Entw&APw-rfe" = "Entwürfe". */
object ModifiedUtf7 {
    fun decode(name: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < name.length) {
            val c = name[i]
            if (c != '&') { out.append(c); i++; continue }
            val end = name.indexOf('-', i + 1)
            if (end < 0) { out.append(name.substring(i)); break }
            val chunk = name.substring(i + 1, end)
            if (chunk.isEmpty()) {
                out.append('&')
            } else {
                val padded = chunk.replace(',', '/').let { it + "=".repeat((4 - it.length % 4) % 4) }
                val bytes = runCatching { Base64.getDecoder().decode(padded) }.getOrNull()
                if (bytes == null) out.append(name, i, end + 1) else out.append(String(bytes, Charsets.UTF_16BE))
            }
            i = end + 1
        }
        return out.toString()
    }
}
