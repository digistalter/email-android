// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail.smtp

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.ServerSetup
import io.github.deviablo.email.mail.Compose
import io.github.deviablo.email.mail.Draft
import io.github.deviablo.email.mail.Mailbox
import io.github.deviablo.email.mail.MessageWriter
import io.github.deviablo.email.mail.imap.Security
import io.github.deviablo.email.mail.imap.ServerSettings
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/** Sends through GreenMail's SMTPS with the project's test certificate, so TLS checks run as in the app. */
class SmtpClientIntegrationTest {
    private lateinit var greenMail: GreenMail
    private val smtps = ServerSetup(13465, "127.0.0.1", ServerSetup.PROTOCOL_SMTPS)

    private fun resource(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    private val trustingTestCa: SSLSocketFactory by lazy {
        val ca = resource("test_ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ca", ca) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    @Before fun start() {
        System.setProperty("greenmail.tls.keystore.file", resource("test-server.p12").absolutePath)
        System.setProperty("greenmail.tls.keystore.password", "changeit")
        System.setProperty("greenmail.tls.key.password", "changeit")
        greenMail = GreenMail(arrayOf(smtps))
        greenMail.start()
        greenMail.setUser("max@localhost", "max@localhost", "secret")
        greenMail.setUser("lena@localhost", "lena", "x")
    }

    @After fun stop() = greenMail.stop()

    @Test fun sendsMessageWithoutBcc() {
        val draft = Draft("t1@localhost", to = listOf(Mailbox("Lena", "lena@localhost")), bcc = listOf(Mailbox("", "geheim@localhost")),
            subject = "Grüße", html = "<div>.Punkt am Anfang</div><div>Hallo</div>")
        val raw = MessageWriter.build(Mailbox("Max", "max@localhost"), draft, System.currentTimeMillis())
        SmtpClient.connect(ServerSettings("localhost", smtps.port, Security.TLS, "max@localhost"), trustingTestCa, null).use { c ->
            c.login("max@localhost", "secret")
            c.send("max@localhost", draft.recipients(), Compose.stripBcc(raw))
            c.quit()
        }
        val received = greenMail.receivedMessages
        assertEquals(2, received.size) // Lena and the Bcc recipient
        assertEquals("Grüße", received[0].subject)
        assertTrue(received[0].getHeader("Bcc") == null)
    }

    @Test(expected = SmtpAuthException::class) fun wrongPasswordIsReported() {
        SmtpClient.connect(ServerSettings("localhost", smtps.port, Security.TLS, "max@localhost"), trustingTestCa, null).use { c ->
            c.login("max@localhost", "falsch")
        }
    }

    @Test fun dotStuffing() {
        assertArrayEquals("..a\r\nb\r\n..\r\n".toByteArray(), dotStuff(".a\nb\r\n.".toByteArray()))
    }
}
