// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.data

import io.github.deviablo.email.mail.imap.ImapFolder
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderRolesTest {
    private fun f(raw: String, vararg attrs: String, name: String = raw) = ImapFolder(raw, name, "/", attrs.toSet())

    @Test fun specialUseAttributesWin() {
        val roles = FolderRoles.assign(listOf(f("INBOX"), f("Sent"), f("Gesendet", "\\Sent"), f("Spam", "\\Junk")))
        assertEquals(FolderRole.INBOX, roles["INBOX"])
        assertEquals(FolderRole.SENT, roles["Gesendet"])
        assertEquals(FolderRole.OTHER, roles["Sent"]) // only one folder per role
        assertEquals(FolderRole.SPAM, roles["Spam"])
    }

    @Test fun germanNamesWithoutAttributes() {
        val roles = FolderRoles.assign(listOf(f("INBOX"), f("Entw&APw-rfe", name = "Entwürfe"), f("Papierkorb"), f("Archiv"), f("Verein")))
        assertEquals(FolderRole.DRAFTS, roles["Entw&APw-rfe"])
        assertEquals(FolderRole.TRASH, roles["Papierkorb"])
        assertEquals(FolderRole.ARCHIVE, roles["Archiv"])
        assertEquals(FolderRole.OTHER, roles["Verein"])
    }

    @Test fun nestedNamesUseLastPart() {
        val roles = FolderRoles.assign(listOf(f("INBOX"), f("INBOX/Trash"), f("INBOX/Drafts")))
        assertEquals(FolderRole.TRASH, roles["INBOX/Trash"])
        assertEquals(FolderRole.DRAFTS, roles["INBOX/Drafts"])
    }
}
