// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail

import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.Base64

/** Decodes RFC 2047 encoded words ("=?UTF-8?B?…?=") in header values such as subjects and names. */
object MimeWords {
    private val word = Regex("""=\?([^?\s]+)\?([bBqQ])\?([^?\s]*)\?=""")
    private val gapBetweenWords = Regex("""(\?=)\s+(=\?)""")

    fun decode(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        // Whitespace between two adjacent encoded words is not part of the text.
        val joined = gapBetweenWords.replace(value) { "${it.groupValues[1]}${it.groupValues[2]}" }
        return word.replace(joined) { m ->
            val charset = runCatching { Charset.forName(m.groupValues[1].substringBefore('*')) }.getOrNull()
                ?: return@replace m.value
            val bytes = runCatching {
                if (m.groupValues[2].equals("B", true)) Base64.getMimeDecoder().decode(m.groupValues[3])
                else decodeQ(m.groupValues[3])
            }.getOrNull() ?: return@replace m.value
            String(bytes, charset)
        }
    }

    private fun decodeQ(text: String): ByteArray {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '_' -> out.write(' '.code)
                c == '=' && i + 2 < text.length && text.substring(i + 1, i + 3).toIntOrNull(16) != null -> {
                    out.write(text.substring(i + 1, i + 3).toInt(16)); i += 2
                }
                else -> out.write(c.code)
            }
            i++
        }
        return out.toByteArray()
    }
}
