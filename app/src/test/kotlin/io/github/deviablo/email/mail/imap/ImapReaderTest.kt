// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail.imap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImapReaderTest {
    private fun reader(text: String) = ImapReader(text.replace("\n", "\r\n").byteInputStream())

    @Test fun taggedOkWithResponseCode() {
        val r = reader("A001 OK [READ-ONLY] EXAMINE completed\n").readResponse()
        assertEquals("A001", r.tag)
        assertEquals("OK", r.kind)
        assertEquals("[READ-ONLY] EXAMINE completed", r.text)
    }

    @Test fun listWithQuotedDelimiterAndEscapes() {
        val r = reader("* LIST (\\HasNoChildren \\Sent) \"/\" \"Sent \\\"Items\\\"\"\n").readResponse()
        assertEquals("LIST", r.kind)
        assertEquals(listOf("\\HasNoChildren", "\\Sent"), r.values[0].items().map { it.text() })
        assertEquals("/", r.values[1].text())
        assertEquals("Sent \"Items\"", r.values[2].text())
    }

    @Test fun numberedFetchWithLiteralAndNil() {
        val subject = "Grüße"
        val bytes = subject.toByteArray(Charsets.UTF_8).size
        val r = reader("* 12 FETCH (UID 7 ENVELOPE (NIL {$bytes}\n$subject NIL))\n").readResponse()
        assertEquals(12L, r.number)
        assertEquals("FETCH", r.kind)
        val items = r.values[0].items()
        assertEquals("7", items[1].text())
        val env = items[3].items()
        assertTrue(env[0] is ImapValue.Nil)
        assertEquals(subject, env[1].text())
    }

    @Test fun bracketedSectionStaysOneAtom() {
        val r = reader("* 1 FETCH (BODY[HEADER.FIELDS (DATE)] \"x\")\n").readResponse()
        assertEquals("BODY[HEADER.FIELDS (DATE)]", r.values[0].items()[0].text())
    }

    @Test fun continuation() {
        val r = reader("+ Ready for literal\n").readResponse()
        assertEquals("+", r.tag)
        assertEquals("Ready for literal", r.text)
    }

    @Test fun consecutiveResponses() {
        val rd = reader("* SEARCH 3 5 9\nA002 OK done\n")
        assertEquals(listOf("3", "5", "9"), rd.readResponse().values.map { it.text() })
        assertEquals("A002", rd.readResponse().tag)
    }
}
