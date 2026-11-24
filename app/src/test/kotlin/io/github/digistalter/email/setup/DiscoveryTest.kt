// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.digistalter.email.setup

import io.github.digistalter.email.mail.imap.Security
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DiscoveryTest {
    private class FakeNetwork(
        val pages: Map<String, String> = emptyMap(),
        val srv: Map<String, List<SrvRecord>> = emptyMap(),
        val mx: Map<String, List<String>> = emptyMap(),
        val open: Set<Pair<String, Int>> = emptySet(),
    ) : DiscoveryNetwork {
        val fetched = mutableListOf<String>()
        override suspend fun fetch(url: String): String? { synchronized(fetched) { fetched += url }; return pages[url] }
        override suspend fun srv(name: String) = srv[name].orEmpty()
        override suspend fun mx(domain: String) = mx[domain].orEmpty()
        override suspend fun reachable(host: String, port: Int) = (host to port) in open
    }

    private val xml = """<?xml version="1.0" encoding="UTF-8"?>
        <clientConfig version="1.1"><emailProvider id="example.org">
          <incomingServer type="pop3"><hostname>pop.example.org</hostname><port>995</port><socketType>SSL</socketType></incomingServer>
          <incomingServer type="imap"><hostname>oauth.example.org</hostname><port>993</port><socketType>SSL</socketType><authentication>OAuth2</authentication></incomingServer>
          <incomingServer type="imap"><hostname>plain.example.org</hostname><port>143</port><socketType>plain</socketType></incomingServer>
          <incomingServer type="imap"><hostname> imap.%EMAILDOMAIN% </hostname><port>993</port><socketType>SSL</socketType>
            <username>%EMAILLOCALPART%</username><authentication>password-cleartext</authentication></incomingServer>
          <outgoingServer type="smtp"><hostname>smtp.example.org</hostname><port>587</port><socketType>STARTTLS</socketType></outgoingServer>
        </emailProvider></clientConfig>"""

    @Test fun knownProvidersNeedNoNetwork() = runBlocking {
        val net = FakeNetwork()
        val found = ServerDiscovery.discover("Max@GMX.de", net) as Discovery.Found
        assertEquals("imap.gmx.net", found.settings.imap.host)
        assertEquals(Source.BUILTIN, found.source)
        assertEquals(Preparation.ENABLE_IMAP, found.provider!!.preparation)
        assertTrue(net.fetched.isEmpty())
        assertEquals(Discovery.Unsupported, ServerDiscovery.discover("a@hotmail.co.uk", net))
    }

    @Test fun autoconfigSkipsOauthAndPlainText() = runBlocking {
        val net = FakeNetwork(pages = mapOf(ServerDiscovery.ISPDB_URL + "example.org" to xml))
        val found = ServerDiscovery.discover("max@example.org", net) as Discovery.Found
        assertEquals(ServerConfig("imap.example.org", 993, Security.TLS), found.settings.imap)
        assertEquals(ServerConfig("smtp.example.org", 587, Security.STARTTLS), found.settings.smtp)
        assertEquals("max", found.settings.username)
        assertEquals(Source.ISPDB, found.source)
    }

    @Test fun srvRecordsPreferImplicitTls() = runBlocking {
        val net = FakeNetwork(srv = mapOf(
            "_imap._tcp.club.example" to listOf(SrvRecord(0, 1, 143, "mx.club.example.")),
            "_imaps._tcp.club.example" to listOf(SrvRecord(10, 1, 993, "imap2.club.example."), SrvRecord(5, 1, 993, "imap.club.example.")),
            "_submissions._tcp.club.example" to listOf(SrvRecord(0, 0, 0, ".")),
            "_submission._tcp.club.example" to listOf(SrvRecord(0, 1, 587, "smtp.club.example.")),
        ))
        val found = ServerDiscovery.discover("anna@club.example", net) as Discovery.Found
        assertEquals(ServerConfig("imap.club.example", 993, Security.TLS), found.settings.imap)
        assertEquals(ServerConfig("smtp.club.example", 587, Security.STARTTLS), found.settings.smtp)
        assertFalse(ServerDiscovery.needsConfirmation("anna@club.example", found.settings, found.source, found.provider))
    }

    @Test fun ownDomainAtKnownProviderIsFoundViaMx() = runBlocking {
        val net = FakeNetwork(mx = mapOf("berger.example" to listOf("mx00.emig.gmx.net.")))
        val found = ServerDiscovery.discover("max@berger.example", net) as Discovery.Found
        assertEquals("imap.gmx.net", found.settings.imap.host)
        assertEquals(Source.MAIL_EXCHANGER, found.source)
        assertEquals(Discovery.Unsupported, ServerDiscovery.discover("a@firma.example", FakeNetwork(mx = mapOf("firma.example" to listOf("firma-example.mail.protection.outlook.com")))))
    }

    @Test fun guessingAndConfirmation() = runBlocking {
        val net = FakeNetwork(open = setOf("mail.verein.example" to 993, "mail.verein.example" to 465))
        val found = ServerDiscovery.discover("kasse@verein.example", net) as Discovery.Found
        assertEquals(Source.GUESS, found.source)
        assertFalse(ServerDiscovery.needsConfirmation("kasse@verein.example", found.settings, found.source, null))
        val foreign = found.settings.copy(imap = ServerConfig("imap.evil.example", 993, Security.TLS))
        assertTrue(ServerDiscovery.needsConfirmation("kasse@verein.example", foreign, Source.SRV, null))
        assertEquals(Discovery.NotFound, ServerDiscovery.discover("x@nowhere.example", FakeNetwork()))
    }

    @Test fun addressesAndNames() {
        assertTrue(Providers.isValidAddress(" Name.Last+tag@Mail.Example.COM "))
        assertTrue(Providers.isValidAddress("anna@müller.de"))
        for (bad in listOf("", "name", "name@", "@posteo.de", "name@posteo", "name@posteo.d", "na me@posteo.de", "name@posteo..de", "name@-posteo.de", "name@posteo.de1")) {
            assertFalse(bad, Providers.isValidAddress(bad))
        }
        assertEquals("Max Berger", Providers.guessName("max.berger@example.org"))
        assertEquals("Anna", Providers.guessName("anna1985@example.org".replace("1985", "_1985")))
        assertEquals("Posteo", Providers.guessLabel("a@posteo.de", Providers.forAddress("a@posteo.de")))
        assertEquals("Verein", Providers.guessLabel("a@verein.example", null))
        assertEquals(listOf("mail.co.uk", "co.uk"), ServerDiscovery.baseDomains("mx.mail.co.uk"))
    }

    @Test fun dnsAnswersAreRead() {
        // Response for _imaps._tcp.x.de with one SRV answer whose target uses compression.
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8); out.write(v and 0xff) }
        fun name(vararg labels: String) { labels.forEach { out.write(it.length); out.write(it.toByteArray()) }; out.write(0) }
        u16(1); u16(0x8180); u16(1); u16(2); u16(0); u16(0)
        name("_imaps", "_tcp", "x", "de"); u16(33); u16(1)
        // answer 1: MX (ignored for SRV), answer 2: SRV
        out.write(0xc0); out.write(12); u16(15); u16(1); u16(0); u16(60); u16(4); u16(10); out.write(0xc0); out.write(24)
        out.write(0xc0); out.write(12); u16(33); u16(1); u16(0); u16(60)
        val rdata = ByteArrayOutputStream().apply { write(0); write(5); write(0); write(1); write(0x03); write(0xe1); write(4); write("imap".toByteArray()); write(0xc0); write(24) }.toByteArray()
        u16(rdata.size); out.write(rdata)
        val records = DnsMessage.srv(out.toByteArray())
        assertEquals(listOf(SrvRecord(5, 1, 993, "imap.x.de")), records)
        assertEquals(listOf("x.de"), DnsMessage.mx(out.toByteArray()))
    }
}
