// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.digistalter.email.mail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MimeTest {
    private fun raw(s: String) = s.trimIndent().replace("\n", "\r\n").toByteArray(Charsets.ISO_8859_1)

    @Test fun plainLatin1QuotedPrintable() {
        val m = Mime.parse(raw("""
            Subject: =?ISO-8859-1?Q?Gr=FC=DFe?=
            Content-Type: text/plain; charset=iso-8859-1
            Content-Transfer-Encoding: quoted-printable

            Sch=F6ne Gr=FC=DFe aus K=F6ln, mit einer sehr langen Zeile, die umbrochen=
             wird.
        """))
        assertEquals("Schöne Grüße aus Köln, mit einer sehr langen Zeile, die umbrochen wird.", m.text?.trim())
        assertEquals("Grüße", MimeWords.decode(m.headers["Subject"]))
    }

    @Test fun alternativeWithInlineImageAndAttachment() {
        val m = Mime.parse(raw("""
            Content-Type: multipart/mixed; boundary="outer"

            --outer
            Content-Type: multipart/related; boundary="rel"

            --rel
            Content-Type: multipart/alternative; boundary="alt"

            --alt
            Content-Type: text/plain; charset=utf-8

            Hallo
            --alt
            Content-Type: text/html; charset=utf-8

            <p>Hallo <img src="cid:logo@x"></p>
            --alt--
            --rel
            Content-Type: image/png
            Content-ID: <logo@x>
            Content-Transfer-Encoding: base64

            iVBORw0KGgo=
            --rel--
            --outer
            Content-Type: application/pdf; name="Rechnung.pdf"
            Content-Disposition: attachment; filename*=UTF-8''%C3%9Cbersicht%20Juni.pdf
            Content-Transfer-Encoding: base64

            JVBERi0xLjQ=
            --outer--
        """))
        assertEquals("Hallo", m.text?.trim())
        assertTrue(m.html!!.contains("cid:logo@x"))
        assertNotNull(m.inlineImage("logo@x"))
        assertEquals(1, m.attachments.size)
        assertEquals("Übersicht Juni.pdf", m.attachments[0].filename)
        assertEquals("%PDF-1.4", String(m.part(m.attachments[0].path)!!.bytes()))
    }

    @Test fun rfc2231Continuation() {
        val v = HeaderValue.parse("attachment; filename*0*=UTF-8''Sehr%20langer%20; filename*1*=Name%C3%A4.txt")
        assertEquals("Sehr langer Nameä.txt", v.params["filename"])
    }

    @Test fun foldedHeaders() {
        val h = Mime.parseHeaders(raw("""
            References: <a@x>
             <b@x>
            Subject: Test

        """))
        assertEquals("<a@x> <b@x>", h["References"])
    }
}

class HtmlSafetyTest {
    @Test fun scriptsFormsAndHandlersAreRemoved() {
        val r = HtmlSafety.prepare("""<p onclick="x()">Hi</p><script>alert(1)</script><form><input></form><iframe src="https://e.vil"></iframe>""", false, false)
        assertFalse(r.html.contains("script>alert"))
        assertFalse(r.html.contains("onclick"))
        assertFalse(r.html.contains("<form"))
        assertFalse(r.html.contains("<iframe"))
    }

    @Test fun remoteImagesBlockedUntilAllowed() {
        val html = """<img src="https://tracker.example/p.gif"><div style="background:url(https://x/y.png)">x</div>"""
        val blocked = HtmlSafety.prepare(html, false, false)
        assertTrue(blocked.blockedImages)
        assertFalse(Regex("""\ssrc="https://tracker""").containsMatchIn(blocked.html))
        assertFalse(blocked.html.contains("url(https://x"))
        val allowed = HtmlSafety.prepare(html, true, false)
        assertTrue(allowed.html.contains("src=\"https://tracker.example/p.gif\""))
    }

    @Test fun dataLinksAreDropped() {
        val r = HtmlSafety.prepare("""<a href="data:text/html,evil">x</a><a href="https://ok.example">y</a>""", false, false)
        assertFalse(r.html.contains("data:text"))
        assertTrue(r.html.contains("https://ok.example"))
    }

    @Test fun deceptiveLinksAreFlagged() {
        assertEquals("evil.example", HtmlSafety.linkWarning("https://bank.example@evil.example/login"))
        assertTrue(HtmlSafety.linkWarning("https://xn--bnk-sna.example/")!!.contains("xn--"))
        assertEquals(null, HtmlSafety.linkWarning("https://bank.example/login"))
    }
}
