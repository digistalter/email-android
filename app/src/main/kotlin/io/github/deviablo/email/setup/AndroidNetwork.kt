// SPDX-License-Identifier: GPL-3.0-or-later
package io.github.deviablo.email.setup

import android.net.DnsResolver
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import javax.net.ssl.HttpsURLConnection
import kotlin.coroutines.resume

/** The real network for [ServerDiscovery]: HTTPS only, the system resolver for DNS, short timeouts. */
class AndroidNetwork : DiscoveryNetwork {
    private val executor = Executors.newCachedThreadPool()

    override suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val connection = URL(url).openConnection() as? HttpsURLConnection ?: return@runCatching null
            connection.connectTimeout = 8_000
            connection.readTimeout = 8_000
            connection.instanceFollowRedirects = true
            try {
                if (connection.responseCode != 200) return@runCatching null
                // HttpsURLConnection does not follow a redirect to plain HTTP, so the answer stays protected.
                connection.inputStream.use { input ->
                    val out = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > 512 * 1024) return@runCatching null
                    }
                    out.toString(Charsets.UTF_8.name())
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    override suspend fun srv(name: String): List<SrvRecord> =
        query(name, DnsMessage.TYPE_SRV)?.let { DnsMessage.srv(it) }.orEmpty()

    override suspend fun mx(domain: String): List<String> =
        query(domain, DnsMessage.TYPE_MX)?.let { DnsMessage.mx(it) }.orEmpty()

    override suspend fun reachable(host: String, port: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching { Socket().use { it.connect(InetSocketAddress(host, port), 4_000) }; true }.getOrDefault(false)
    }

    private suspend fun query(name: String, type: Int): ByteArray? = withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine { cont ->
            val cancel = CancellationSignal()
            cont.invokeOnCancellation { cancel.cancel() }
            DnsResolver.getInstance().rawQuery(
                null, name, DnsResolver.CLASS_IN, type, DnsResolver.FLAG_EMPTY, executor, cancel,
                object : DnsResolver.Callback<ByteArray> {
                    override fun onAnswer(answer: ByteArray, rcode: Int) { if (cont.isActive) cont.resume(if (rcode == 0) answer else null) }
                    override fun onError(error: DnsResolver.DnsException) { if (cont.isActive) cont.resume(null) }
                },
            )
        }
    }
}

/** Just enough of the DNS wire format (RFC 1035) to read SRV and MX answers. */
object DnsMessage {
    const val TYPE_MX = 15
    const val TYPE_SRV = 33

    private class Reader(val data: ByteArray) {
        var pos = 0
        fun u8() = data[pos++].toInt() and 0xff
        fun u16() = (u8() shl 8) or u8()
        fun u32() = (u16().toLong() shl 16) or u16().toLong()

        /** A domain name, following compression pointers. */
        fun name(): String {
            val labels = mutableListOf<String>()
            var p = pos
            var jumped = false
            var guard = 0
            while (true) {
                if (++guard > 128) throw IllegalArgumentException("name loop")
                val len = data[p].toInt() and 0xff
                when {
                    len == 0 -> { p++; break }
                    len and 0xc0 == 0xc0 -> {
                        val target = ((len and 0x3f) shl 8) or (data[p + 1].toInt() and 0xff)
                        if (!jumped) pos = p + 2
                        jumped = true
                        p = target
                    }
                    else -> {
                        labels += String(data, p + 1, len, Charsets.US_ASCII)
                        p += 1 + len
                    }
                }
            }
            if (!jumped) pos = p
            return labels.joinToString(".")
        }
    }

    /** (type, rdata start, reader) for every answer record. */
    private fun answers(message: ByteArray, wanted: Int, read: (Reader) -> Unit) {
        val r = Reader(message)
        r.pos = 4
        val questions = r.u16()
        val answers = r.u16()
        r.pos = 12
        repeat(questions) { r.name(); r.pos += 4 }
        repeat(answers) {
            r.name()
            val type = r.u16()
            r.u16() // class
            r.u32() // ttl
            val length = r.u16()
            val end = r.pos + length
            if (type == wanted) read(r)
            r.pos = end
        }
    }

    fun srv(message: ByteArray): List<SrvRecord> = runCatching {
        val out = mutableListOf<SrvRecord>()
        answers(message, TYPE_SRV) { r -> out += SrvRecord(r.u16(), r.u16(), r.u16(), r.name()) }
        out
    }.getOrDefault(emptyList())

    fun mx(message: ByteArray): List<String> = runCatching {
        val out = mutableListOf<Pair<Int, String>>()
        answers(message, TYPE_MX) { r -> out += r.u16() to r.name() }
        out.sortedBy { it.first }.map { it.second }
    }.getOrDefault(emptyList())
}
