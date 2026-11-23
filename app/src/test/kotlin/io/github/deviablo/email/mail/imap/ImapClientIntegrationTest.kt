// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.mail.imap

import com.icegreen.greenmail.util.GreenMail
import com.icegreen.greenmail.util.GreenMailUtil
import com.icegreen.greenmail.util.ServerSetup
import com.icegreen.greenmail.util.ServerSetupTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.Date
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory

/**
 * Runs the real client against GreenMail. The server uses a certificate from our own test CA,
 * so certificate and host name checks run exactly as in the app; only the trust anchor differs.
 */
class ImapClientIntegrationTest {
    private lateinit var greenMail: GreenMail
    // Quote and backslash must be escaped in LOGIN. (GreenMail rejects non-ASCII LOGIN passwords
    // and offers no AUTH=PLAIN, so the UTF-8 path cannot be checked against it.)
    private val password = "ge\"heim\\ 123"

    private val smtp = ServerSetup(ServerSetupTest.SMTP.port, "127.0.0.1", ServerSetup.PROTOCOL_SMTP)
    private val imaps = ServerSetup(ServerSetupTest.IMAPS.port, "127.0.0.1", ServerSetup.PROTOCOL_IMAPS)

    private fun resource(name: String) = File(javaClass.classLoader!!.getResource(name)!!.toURI())

    private val trustingTestCa: SSLSocketFactory by lazy {
        val ca = resource("test_ca.pem").inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
        val store = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null); setCertificateEntry("ca", ca) }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(store) }
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }.socketFactory
    }

    private fun settings(host: String = "localhost") =
        ServerSettings(host, imaps.port, Security.TLS, "alice")

    @Before fun start() {
        System.setProperty("greenmail.tls.keystore.file", resource("test-server.p12").absolutePath)
        System.setProperty("greenmail.tls.keystore.password", "changeit")
        System.setProperty("greenmail.tls.key.password", "changeit")
        greenMail = GreenMail(arrayOf(smtp, imaps))
        greenMail.start()
        greenMail.setUser("alice@localhost", "alice", password)
    }

    @After fun stop() = greenMail.stop()

    @Test fun loginListAndFetchHeaders() {
        GreenMailUtil.sendTextEmail("alice@localhost", "Lena Koch <lena@localhost>", "Wohnungsübergabe am Samstag", "Hallo!", smtp)
        GreenMailUtil.sendAttachmentEmail(
            "alice@localhost", "bob@localhost", "Fotos", "Anbei", "bild".toByteArray(),
            "image/jpeg", "foto.jpg", "Foto", smtp,
        )
        greenMail.waitForIncomingEmail(5_000, 2)

        ImapClient.connect(settings(), trustingTestCa, null).use { client ->
            client.login("alice", password)
            assertTrue(client.list().any { it.rawName == "INBOX" })

            val inbox = client.select("INBOX")
            assertEquals(2L, inbox.exists)
            assertTrue(inbox.uidValidity != null)

            val uids = client.uidSearchSince(Date(System.currentTimeMillis() - 30L * 24 * 3600 * 1000))
            assertEquals(2, uids.size)

            val headers = client.uidFetchHeaders(uids).associateBy { it.subject }
            val first = headers.getValue("Wohnungsübergabe am Samstag")
            assertEquals("Lena Koch", first.from?.name)
            assertEquals("lena@localhost", first.from?.address)
            assertFalse(first.seen)
            assertFalse(first.hasAttachment)
            assertTrue(headers.getValue("Fotos").hasAttachment)
            client.logout()
        }
    }

    @Test(expected = ImapAuthException::class)
    fun wrongPasswordIsReportedAsLoginProblem() {
        ImapClient.connect(settings(), trustingTestCa, null).use { it.login("alice", "falsch") }
    }

    @Test(expected = SSLHandshakeException::class)
    fun untrustedCertificateIsRejected() {
        // Default trust store does not know the test CA – exactly what must happen with a fake server.
        ImapClient.connect(settings(), hostnameVerifier = null).close()
    }

    @Test(expected = SSLHandshakeException::class)
    fun certificateForAnotherHostIsRejected() {
        // The certificate is valid for localhost/127.0.0.1/10.0.2.2, not for 127.0.0.2.
        val other = GreenMail(ServerSetup(imaps.port + 1, "127.0.0.2", ServerSetup.PROTOCOL_IMAPS))
        other.start()
        try {
            ImapClient.connect(ServerSettings("127.0.0.2", imaps.port + 1, Security.TLS, "alice"), trustingTestCa, null).close()
        } finally {
            other.stop()
        }
    }
}
