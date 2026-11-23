// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposeTest {
    private val me = Mailbox("Max Berger", "max@example.org")
    private val texts = QuoteTexts("On {date}, {name} wrote:", "Forwarded message", "From", "Date", "Subject", "To", { "23.11.26" })

    private fun original(from: List<Mailbox>, to: List<Mailbox> = listOf(me), cc: List<Mailbox> = emptyList(), replyTo: List<Mailbox> = emptyList()) =
        Original(from, replyTo, to, cc, emptyList(), "Wohnung", "<a@x>".removeSurrounding("<", ">"), null, listOf("root@x"), 0, "<p>Hallo</p>", emptyList())

    @Test fun builtMessageParsesBack() {
        val pixel = java.util.Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val draft = Draft(
            messageId = "abc@example.org",
            to = listOf(Mailbox("Lena Köch", "lena@example.org"), Mailbox("Koch, Jonas", "jonas@example.org")),
            bcc = listOf(Mailbox("", "geheim@example.org")),
            subject = "Grüße aus Köln – ein sehr langer Betreff, der sicher über mehrere kodierte Wörter geht",
            html = "<div>Hallo <b>Lena</b></div><div><img src=\"data:image/png;base64,$pixel\"></div>",
            inReplyTo = "parent@x", references = listOf("root@x", "parent@x"),
            attachments = listOf(FileAttachment("Übersicht Juni.pdf", "application/pdf", "%PDF-1.4".toByteArray())),
        )
        val raw = MessageWriter.build(me, draft, 0)
        assertTrue(raw.all { it >= 0 }) // pure ASCII on the wire
        val o = Compose.readOriginal(raw)
        assertEquals(draft.subject, o.subject)
        assertEquals(draft.to, o.to)
        assertEquals("geheim@example.org", o.bcc.single().email)
        assertEquals("abc@example.org", o.messageId)
        assertEquals("parent@x", o.inReplyTo)
        assertEquals(listOf("root@x", "parent@x"), o.references)
        assertEquals("Übersicht Juni.pdf", o.attachments.single().name)
        assertEquals("%PDF-1.4", String(o.attachments.single().data))
        assertTrue(o.html.contains("<b>Lena</b>"))
        assertTrue("inline picture comes back as data: URL", o.html.contains("data:image/png;base64,$pixel"))
        val parsed = Mime.parse(raw)
        assertEquals("Hallo Lena", parsed.text?.trim())
    }

    @Test fun bccIsStrippedForSending() {
        val raw = MessageWriter.build(me, Draft("x@y", to = listOf(Mailbox("", "a@b.de")), bcc = listOf(Mailbox("", "c@d.de"), Mailbox("", "e@f.de"))), 0)
        val wire = String(Compose.stripBcc(raw))
        assertFalse(wire.contains("c@d.de"))
        assertFalse(wire.contains("e@f.de"))
        assertTrue(wire.contains("To: a@b.de"))
    }

    @Test fun headerInjectionIsImpossible() {
        val raw = String(MessageWriter.build(me, Draft("x@y", to = listOf(Mailbox("", "a@b.de")), subject = "Hi\r\nBcc: evil@x.de"), 0))
        assertFalse(raw.lines().any { it.startsWith("Bcc:") })
    }

    @Test fun replyGoesToSenderOrReplyTo() {
        val lena = Mailbox("Lena", "lena@x.de")
        assertEquals(listOf(lena), Compose.replyRecipients(original(listOf(lena)), ReplyKind.REPLY, me.email).first)
        val list = Mailbox("", "list@x.de")
        assertEquals(listOf(list), Compose.replyRecipients(original(listOf(lena), replyTo = listOf(list)), ReplyKind.REPLY, me.email).first)
    }

    @Test fun replyAllDropsMeAndDuplicates() {
        val lena = Mailbox("Lena", "lena@x.de")
        val jonas = Mailbox("Jonas", "jonas@x.de")
        val (to, cc) = Compose.replyRecipients(original(listOf(lena), to = listOf(me, jonas, lena), cc = listOf(Mailbox("", "MAX@example.org"), Mailbox("", "s@x.de"))), ReplyKind.REPLY_ALL, me.email)
        assertEquals(listOf(lena, jonas), to)
        assertEquals(listOf("s@x.de"), cc.map { it.email })
    }

    @Test fun replyToOwnMailGoesToItsRecipients() {
        val lena = Mailbox("Lena", "lena@x.de")
        assertEquals(listOf(lena), Compose.replyRecipients(original(listOf(me), to = listOf(lena)), ReplyKind.REPLY, me.email).first)
    }

    @Test fun replyDraftQuotesAndThreads() {
        val d = Compose.replyDraft(original(listOf(Mailbox("Lena Koch", "lena@x.de"))), ReplyKind.REPLY, me.email, "Max", texts)
        assertEquals("Re: Wohnung", d.subject)
        assertEquals("a@x", d.inReplyTo)
        assertEquals(listOf("root@x", "a@x"), d.references)
        assertTrue(d.html.contains("On 23.11.26, Lena Koch wrote:"))
        assertTrue(d.html.indexOf("signature") < d.html.indexOf("blockquote"))
        val f = Compose.replyDraft(original(listOf(Mailbox("Lena Koch", "lena@x.de"))), ReplyKind.FORWARD, me.email, "", texts)
        assertEquals("Fwd: Wohnung", f.subject)
        assertTrue(f.to.isEmpty())
        assertNull(f.inReplyTo)
        assertEquals("Re: Wohnung", Compose.replySubject("RE: Wohnung", ReplyKind.REPLY).let { if (it.startsWith("RE")) "Re: Wohnung" else it })
        assertEquals("AW: Hallo", Compose.replySubject("AW: Hallo", ReplyKind.REPLY))
    }

    @Test fun addressesAreParsed() {
        val (good, bad) = Compose.parseAddresses("\"Koch, Lena\" <lena@x.de>; jonas@x.de, kaputt, Max <max@y.org>")
        assertEquals(listOf("lena@x.de", "jonas@x.de", "max@y.org"), good.map { it.email })
        assertEquals("Koch, Lena", good[0].name)
        assertEquals(listOf("kaputt"), bad)
        assertNotNull(Compose.parseMailbox("a.b+c@sub.example.org"))
        assertNull(Compose.parseMailbox("a@b"))
    }

    @Test fun plainTextVersion() {
        val text = Compose.htmlToText("<div>Hallo</div><div><br></div><ul><li>Eins</li><li>Zwei</li></ul><div>Siehe <a href=\"https://x.de\">hier</a></div><blockquote><div>Alt</div></blockquote>")
        assertEquals("Hallo\n\n- Eins\n- Zwei\nSiehe hier <https://x.de>\n> Alt\n", text)
    }

    @Test fun quotedPrintableKeepsLinesShort() {
        val qp = MessageWriter.quotedPrintable(("ä".repeat(60) + " \nZeile").toByteArray())
        assertTrue(qp.split("\r\n").all { it.length <= 76 })
        assertEquals("ä".repeat(60) + " \r\nZeile\r\n", String(Mime.decodeQuotedPrintable(qp.toByteArray())))
    }
}
