// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadingTest {
    private fun m(id: Long, folder: Long, key: String, from: String, date: Long, seen: Boolean = true, msgId: String? = "<$id@x>") =
        StoredMessage(id, 1, folder, id, "Wohnung", from, "$from@example.org".lowercase(), "", date, seen, false, false, msgId, key)

    @Test fun subjectsWithPrefixesMatch() {
        assertEquals("wohnung am samstag", Threading.normalizeSubject("Re: AW: WG: Wohnung am Samstag"))
        assertEquals("rechnung", Threading.normalizeSubject("Re[2]: Rechnung"))
        assertTrue(Threading.isReply("AW: Hallo"))
        assertFalse(Threading.isReply("Monatsrechnung"))
    }

    @Test fun conversationTakesOwnRepliesFromSent() {
        val inbox = listOf(m(1, 10, "t", "Lena", 100), m(3, 10, "t", "Lena", 300, seen = false))
        val sent = listOf(m(2, 20, "t", "Max", 200))
        val convs = Threading.conversations(inbox, inbox + sent, grouping = true, allowedFolders = setOf(10, 20))
        assertEquals(1, convs.size)
        assertEquals(listOf(1L, 2L, 3L), convs[0].messages.map { it.id })
        assertEquals(2, convs[0].inFolder.size)
        assertTrue(convs[0].unread)
        assertEquals("Lena, Ich", Threading.senders(convs[0], setOf("max@example.org"), "Ich"))
    }

    @Test fun sameMailInTwoFoldersAppearsOnce() {
        val a = m(1, 10, "t", "Lena", 100, msgId = "<same@x>")
        val b = m(2, 30, "t", "Lena", 100, msgId = "<same@x>")
        val convs = Threading.conversations(listOf(a), listOf(a, b), grouping = true, allowedFolders = setOf(10, 30))
        assertEquals(1, convs[0].messages.size)
    }

    @Test fun groupingOffShowsEveryMail() {
        val inbox = listOf(m(1, 10, "t", "Lena", 100), m(3, 10, "t", "Lena", 300))
        assertEquals(2, Threading.conversations(inbox, inbox, grouping = false, allowedFolders = setOf(10)).size)
    }
}
