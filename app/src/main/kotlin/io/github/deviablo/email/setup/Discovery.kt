// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.setup

import io.github.deviablo.email.mail.imap.Security
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.w3c.dom.Element
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

data class ServerConfig(val host: String, val port: Int, val security: Security)

data class DiscoveredSettings(val imap: ServerConfig, val smtp: ServerConfig, val username: String)

data class SrvRecord(val priority: Int, val weight: Int, val port: Int, val target: String)

enum class ProviderId { GMAIL, ICLOUD, YAHOO, GMX, WEBDE, POSTEO, MAILBOX_ORG, TONLINE, MICROSOFT }

enum class Preparation { NONE, APP_PASSWORD, ENABLE_IMAP, UNSUPPORTED }

class Provider(
    val id: ProviderId,
    val name: String,
    val preparation: Preparation,
    val helpUrl: String?,
    val domains: List<String>,
    private val imap: ServerConfig?,
    private val smtp: ServerConfig?,
) {
    fun settings(email: String): DiscoveredSettings? =
        if (preparation == Preparation.UNSUPPORTED || imap == null || smtp == null) null
        else DiscoveredSettings(imap, smtp, email.trim())
}

/** The providers we know ourselves (same list as the Linux app). */
object Providers {
    private val TLS = Security.TLS
    private val STARTTLS = Security.STARTTLS
    val all = listOf(
        Provider(ProviderId.GMAIL, "Gmail", Preparation.APP_PASSWORD, "https://myaccount.google.com/apppasswords", listOf("gmail.com", "googlemail.com"),
            ServerConfig("imap.gmail.com", 993, TLS), ServerConfig("smtp.gmail.com", 465, TLS)),
        Provider(ProviderId.ICLOUD, "iCloud", Preparation.APP_PASSWORD, "https://account.apple.com", listOf("icloud.com", "me.com", "mac.com"),
            ServerConfig("imap.mail.me.com", 993, TLS), ServerConfig("smtp.mail.me.com", 587, STARTTLS)),
        Provider(ProviderId.YAHOO, "Yahoo", Preparation.APP_PASSWORD, "https://login.yahoo.com/account/security", listOf("yahoo.com", "yahoo.de", "ymail.com", "rocketmail.com"),
            ServerConfig("imap.mail.yahoo.com", 993, TLS), ServerConfig("smtp.mail.yahoo.com", 465, TLS)),
        Provider(ProviderId.GMX, "GMX", Preparation.ENABLE_IMAP, "https://www.gmx.net", listOf("gmx.de", "gmx.net", "gmx.at", "gmx.ch"),
            ServerConfig("imap.gmx.net", 993, TLS), ServerConfig("mail.gmx.net", 465, TLS)),
        Provider(ProviderId.WEBDE, "WEB.DE", Preparation.ENABLE_IMAP, "https://web.de", listOf("web.de"),
            ServerConfig("imap.web.de", 993, TLS), ServerConfig("smtp.web.de", 587, STARTTLS)),
        Provider(ProviderId.POSTEO, "Posteo", Preparation.NONE, null, listOf("posteo.de", "posteo.net", "posteo.at", "posteo.ch", "posteo.eu", "posteo.org", "posteo.be"),
            ServerConfig("posteo.de", 993, TLS), ServerConfig("posteo.de", 465, TLS)),
        Provider(ProviderId.MAILBOX_ORG, "mailbox.org", Preparation.NONE, null, listOf("mailbox.org"),
            ServerConfig("imap.mailbox.org", 993, TLS), ServerConfig("smtp.mailbox.org", 465, TLS)),
        Provider(ProviderId.TONLINE, "T-Online", Preparation.NONE, null, listOf("t-online.de", "magenta.de"),
            ServerConfig("secureimap.t-online.de", 993, TLS), ServerConfig("securesmtp.t-online.de", 465, TLS)),
        Provider(ProviderId.MICROSOFT, "Microsoft", Preparation.UNSUPPORTED, null,
            listOf("outlook.com", "outlook.de", "hotmail.com", "hotmail.de", "live.com", "live.de", "msn.com"), null, null),
    )

    /** "name@domain" → (local part, lower-case domain) if it looks like a real address. */
    fun splitAddress(email: String): Pair<String, String>? {
        val e = email.trim()
        val at = e.lastIndexOf('@')
        if (at <= 0) return null
        val local = e.substring(0, at)
        val domain = e.substring(at + 1).lowercase(Locale.ROOT)
        if (local.any { it.isWhitespace() }) return null
        val labels = domain.split('.')
        val validDomain = labels.size >= 2 && labels.all { l ->
            l.isNotEmpty() && !l.startsWith('-') && !l.endsWith('-') && l.all { it.isLetterOrDigit() || it == '-' || it.code > 127 }
        } && labels.last().let { tld -> tld.length >= 2 && tld.all { it.isLetter() } }
        return if (validDomain) local to domain else null
    }

    fun isValidAddress(email: String) = splitAddress(email) != null

    fun forDomain(domain: String): Provider? {
        val d = domain.trim().trimEnd('.').lowercase(Locale.ROOT)
        val microsoft = listOf("outlook.", "hotmail.", "live.", "msn.").any { d.startsWith(it) }
        return all.firstOrNull { d in it.domains || (microsoft && it.id == ProviderId.MICROSOFT) }
    }

    fun forAddress(email: String): Provider? = splitAddress(email)?.let { forDomain(it.second) }

    /** Microsoft 365 behind an own domain: password sign-in is switched off there as well. */
    fun isUnsupportedMailExchanger(host: String): Boolean {
        val h = host.trimEnd('.').lowercase(Locale.ROOT)
        return h.endsWith(".mail.protection.outlook.com") || h.endsWith(".olc.protection.outlook.com")
    }

    /** "max.berger@…" → "Max Berger". */
    fun guessName(email: String): String =
        email.substringBefore('@').split('.', '_', '-', '+')
            .filter { w -> w.isNotEmpty() && !w.all { it.isDigit() } }
            .joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.getDefault()) } }

    /** The provider's name, or the first part of the domain. */
    fun guessLabel(email: String, provider: Provider?): String =
        provider?.name ?: email.substringAfterLast('@').substringBefore('.').replaceFirstChar { it.titlecase(Locale.getDefault()) }
}

/** All network access of the discovery, so its order and decisions can be tested without a network. */
interface DiscoveryNetwork {
    /** The body of a successful HTTPS GET, if there is one. */
    suspend fun fetch(url: String): String?
    suspend fun srv(name: String): List<SrvRecord>
    /** Mail exchangers of a domain, best first. */
    suspend fun mx(domain: String): List<String>
    suspend fun reachable(host: String, port: Int): Boolean
}

enum class Source { BUILTIN, AUTOCONFIG, ISPDB, SRV, MAIL_EXCHANGER, GUESS }

sealed interface Discovery {
    data class Found(val settings: DiscoveredSettings, val source: Source, val provider: Provider?) : Discovery
    /** The address belongs to a provider that cannot be used with a password. */
    data object Unsupported : Discovery
    data object NotFound : Discovery
}

/**
 * Finds the server settings for an address, in this order (Linux app): known providers, the
 * provider's autoconfig file and Mozilla's database (ISPDB), DNS SRV records (RFC 6186), the
 * domain's mail servers (MX, often a known provider behind an own domain), common host names.
 */
object ServerDiscovery {
    const val ISPDB_URL = "https://autoconfig.thunderbird.net/v1.1/"

    suspend fun discover(email: String, net: DiscoveryNetwork): Discovery {
        val address = email.trim()
        val domain = Providers.splitAddress(address)?.second ?: return Discovery.NotFound
        builtin(domain, address)?.let { return it }
        autoconfig(domain, address, net)?.let { return it }
        srv(domain, address, net)?.let { return it }
        mailExchanger(domain, address, net)?.let { return it }
        val imap = guess(net, domain, listOf(993 to Security.TLS, 143 to Security.STARTTLS), listOf("imap", "mail"))
        val smtp = guess(net, domain, listOf(465 to Security.TLS, 587 to Security.STARTTLS), listOf("smtp", "mail"))
        return if (imap != null && smtp != null) Discovery.Found(DiscoveredSettings(imap, smtp, address), Source.GUESS, null) else Discovery.NotFound
    }

    private fun builtin(domain: String, email: String): Discovery? {
        val provider = Providers.forDomain(domain) ?: return null
        val settings = provider.settings(email) ?: return Discovery.Unsupported
        return Discovery.Found(settings, Source.BUILTIN, provider)
    }

    private suspend fun autoconfig(domain: String, email: String, net: DiscoveryNetwork): Discovery? = coroutineScope {
        val urls = listOf(
            "https://autoconfig.$domain/mail/config-v1.1.xml?emailaddress=${percentEncode(email)}",
            "https://$domain/.well-known/autoconfig/mail/config-v1.1.xml",
            ISPDB_URL + domain,
        )
        val (own, wellKnown, ispdb) = urls.map { async { net.fetch(it) } }.awaitAll()
        Autoconfig.parse(own, email)?.let { return@coroutineScope Discovery.Found(it, Source.AUTOCONFIG, null) }
        Autoconfig.parse(wellKnown, email)?.let { return@coroutineScope Discovery.Found(it, Source.AUTOCONFIG, null) }
        Autoconfig.parse(ispdb, email)?.let { Discovery.Found(it, Source.ISPDB, null) }
    }

    private suspend fun srv(domain: String, email: String, net: DiscoveryNetwork): Discovery? = coroutineScope {
        val (imaps, imap, submissions, submission) = listOf("_imaps._tcp.", "_imap._tcp.", "_submissions._tcp.", "_submission._tcp.")
            .map { async { net.srv(it + domain) } }.awaitAll()
        // Implicit TLS is preferred over STARTTLS (RFC 8314).
        val incoming = bestSrv(imaps, Security.TLS) ?: bestSrv(imap, Security.STARTTLS) ?: return@coroutineScope null
        val outgoing = bestSrv(submissions, Security.TLS) ?: bestSrv(submission, Security.STARTTLS)
            ?: guess(net, domain, listOf(465 to Security.TLS, 587 to Security.STARTTLS), listOf("smtp", "mail"))
            ?: return@coroutineScope null
        Discovery.Found(DiscoveredSettings(incoming, outgoing, email), Source.SRV, null)
    }

    private fun bestSrv(records: List<SrvRecord>, security: Security): ServerConfig? =
        // A target of "." means the service is deliberately not offered.
        records.map { it to it.target.trimEnd('.') }.filter { (r, t) -> t.isNotEmpty() && r.port != 0 }
            .minWithOrNull(compareBy<Pair<SrvRecord, String>> { it.first.priority }.thenByDescending { it.first.weight })
            ?.let { (r, t) -> ServerConfig(t, r.port, security) }

    private suspend fun mailExchanger(domain: String, email: String, net: DiscoveryNetwork): Discovery? {
        val exchangers = net.mx(domain)
        if (exchangers.any { Providers.isUnsupportedMailExchanger(it) }) return Discovery.Unsupported
        val host = exchangers.firstOrNull()?.trimEnd('.')?.lowercase(Locale.ROOT) ?: return null
        for (base in baseDomains(host)) {
            if (base == domain) continue
            val provider = Providers.forDomain(base)
            if (provider != null && provider.preparation != Preparation.UNSUPPORTED) {
                provider.settings(email)?.let { return Discovery.Found(it, Source.MAIL_EXCHANGER, provider) }
            }
            Autoconfig.parse(net.fetch(ISPDB_URL + base), email)?.let { return Discovery.Found(it, Source.MAIL_EXCHANGER, null) }
        }
        return null
    }

    /** The registrable domain of a host, roughly; without a public suffix list both guesses are tried. */
    internal fun baseDomains(host: String): List<String> {
        val labels = host.split('.').filter { it.isNotEmpty() }
        val bases = mutableListOf<String>()
        if (labels.size >= 3 && labels[labels.size - 2].length <= 3) bases += labels.takeLast(3).joinToString(".")
        if (labels.size >= 2) bases += labels.takeLast(2).joinToString(".")
        return bases
    }

    private suspend fun guess(net: DiscoveryNetwork, domain: String, ports: List<Pair<Int, Security>>, prefixes: List<String>): ServerConfig? = coroutineScope {
        val candidates = ports.flatMap { (port, security) -> prefixes.map { ServerConfig("$it.$domain", port, security) } }
        val answers = candidates.map { c -> async { net.reachable(c.host, c.port) } }.awaitAll()
        candidates.zip(answers).firstOrNull { it.second }?.first
    }

    private fun percentEncode(value: String) = value.toByteArray().joinToString("") { b ->
        val c = b.toInt() and 0xff
        if (c.toChar().isLetterOrDigit() && c < 128 || c.toChar() in "-._~") c.toChar().toString() else "%%%02X".format(c)
    }

    /**
     * True if the servers came from unsigned DNS answers (SRV, MX, guessing) and belong neither to
     * the mail domain nor to a known provider. A manipulated network could point there to collect
     * the password, so the user sees the server names first.
     */
    fun needsConfirmation(email: String, settings: DiscoveredSettings, source: Source, provider: Provider?): Boolean {
        if (provider != null || source !in setOf(Source.SRV, Source.MAIL_EXCHANGER, Source.GUESS)) return false
        val domain = Providers.splitAddress(email)?.second ?: return true
        fun belongs(host: String) = host.trimEnd('.').lowercase(Locale.ROOT).let { it == domain || it.endsWith(".$domain") }
        return !(belongs(settings.imap.host) && belongs(settings.smtp.host))
    }
}

/** Thunderbird's autoconfig format (config-v1.1.xml). Only password sign-in, only TLS or STARTTLS. */
object Autoconfig {
    fun parse(xml: String?, email: String): DiscoveredSettings? {
        if (xml.isNullOrBlank()) return null
        val doc = runCatching {
            val factory = DocumentBuilderFactory.newInstance()
            // No DTDs and no external entities: the file comes from the net.
            runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { factory.setFeature("http://xml.org/sax/features/external-general-entities", false) }
            factory.isExpandEntityReferences = false
            factory.newDocumentBuilder().parse(xml.byteInputStream())
        }.getOrNull() ?: return null
        val provider = doc.documentElement.childElements().firstOrNull { it.tagName == "emailProvider" } ?: return null
        fun pick(tag: String, type: String) = provider.childElements().filter { it.tagName == tag && it.getAttribute("type") == type }.firstNotNullOfOrNull(::candidate)
        val imap = pick("incomingServer", "imap") ?: return null
        val smtp = pick("outgoingServer", "smtp") ?: return null
        val username = imap.second?.let { expand(it, email) }?.takeIf { it.isNotEmpty() } ?: email.trim()
        return DiscoveredSettings(imap.first.copy(host = expand(imap.first.host, email)), smtp.first.copy(host = expand(smtp.first.host, email)), username)
    }

    private fun candidate(node: Element): Pair<ServerConfig, String?>? {
        fun text(tag: String) = node.childElements().firstOrNull { it.tagName == tag }?.textContent?.trim()
        val host = text("hostname")?.takeIf { it.isNotEmpty() } ?: return null
        val port = text("port")?.toIntOrNull() ?: return null
        val security = when (text("socketType")) {
            "SSL", "TLS" -> Security.TLS
            "STARTTLS" -> Security.STARTTLS
            else -> return null
        }
        val methods = node.childElements().filter { it.tagName == "authentication" }.map { it.textContent.trim() }
        if (methods.isNotEmpty() && methods.none { it == "password-cleartext" || it == "password-encrypted" }) return null
        return ServerConfig(host, port, security) to text("username")
    }

    private fun expand(template: String, email: String): String {
        val e = email.trim()
        return template.replace("%EMAILADDRESS%", e).replace("%EMAILLOCALPART%", e.substringBeforeLast('@'))
            .replace("%EMAILDOMAIN%", e.substringAfterLast('@').lowercase(Locale.ROOT))
    }

    private fun Element.childElements(): List<Element> =
        (0 until childNodes.length).mapNotNull { childNodes.item(it) as? Element }
}
