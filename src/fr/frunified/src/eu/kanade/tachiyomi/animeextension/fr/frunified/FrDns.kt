package eu.kanade.tachiyomi.animeextension.fr.frunified

import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URL
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * DNS personnalisé, branché sur le client HTTP de l'extension.
 *
 * Ordre d'essai pour chaque serveur configuré :
 * 1. **DoH** (DNS over HTTPS, RFC 8484) — contourne le port 53 souvent filtré
 *    ou hijacké par l'opérateur ;
 * 2. **UDP 53** — repli classique ;
 * 3. **DNS du système** — jamais de coupure si tout le reste échoue.
 *
 * Les requêtes finales de lecture/téléchargement étant effectuées par Aniyomi
 * elle-même, ce DNS ne peut pas couvrir ces dernières (voir le guide).
 *
 * DoH s'adresse de préférence à l'IP du résolveur (`https://1.1.1.1/dns-query`)
 * afin de ne pas dépendre d'une autre résolution de nom (récursion).
 */
object FrDns : Dns {

    private const val UDP_TIMEOUT_MS = 2_500
    private const val DOH_TIMEOUT_MS = 4_000
    private const val CACHE_POSITIVE_MS = 10 * 60 * 1000L
    private const val CACHE_NEGATIVE_MS = 20 * 1000L
    private const val MAX_SERVERS = 4

    private data class CacheEntry(val expiresAt: Long, val addresses: List<InetAddress>)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    @Volatile
    private var lastServers: List<String> = emptyList()

    private data class Server(val address: InetAddress, val port: Int)

    fun clearCache() {
        cache.clear()
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val servers = FrSettings.dnsHosts
        if (servers != lastServers) {
            cache.clear()
            lastServers = servers
        }
        if (servers.isEmpty()) return Dns.SYSTEM.lookup(hostname)
        val key = hostname.lowercase()
        val now = System.currentTimeMillis()
        cache[key]?.let { entry -> if (entry.expiresAt > now) return entry.addresses }
        val addresses = resolve(hostname, servers)
        val result = addresses.ifEmpty {
            // Jamais de coupure : si le serveur DNS choisi est injoignable,
            // on retombe sur la résolution système.
            runCatching { Dns.SYSTEM.lookup(hostname) }.getOrDefault(emptyList())
        }
        cache[key] = CacheEntry(now + if (result.isEmpty()) CACHE_NEGATIVE_MS else CACHE_POSITIVE_MS, result)
        return result
    }

    private fun resolve(hostname: String, rawServers: List<String>): List<InetAddress> {
        if (!isAsciiHostname(hostname)) return emptyList()
        val addresses = linkedSetOf<InetAddress>()
        for (raw in rawServers.take(MAX_SERVERS)) {
            addresses += query(raw, hostname, TYPE_A)
            if (addresses.isEmpty()) addresses += query(raw, hostname, TYPE_AAAA)
            if (addresses.isNotEmpty()) break
        }
        return addresses.toList()
    }

    data class PathTest(
        val server: String,
        val dohEndpoint: String?,
        val dohAddresses: List<String>,
        val dohMs: Long,
        val udpAddresses: List<String>,
        val udpMs: Long,
    )

    /** Teste séparément les deux chemins, sans cache ni repli système. */
    internal fun testPath(raw: String, hostname: String): PathTest {
        val dohStarted = System.currentTimeMillis()
        val doh = dohQuery(raw, hostname, TYPE_A)
        val dohMs = System.currentTimeMillis() - dohStarted
        val udpStarted = System.currentTimeMillis()
        val udp = parseServer(raw)?.let { udpQuery(it, hostname, TYPE_A) }.orEmpty()
        return PathTest(
            server = raw,
            dohEndpoint = dohEndpoint(raw),
            dohAddresses = doh.mapNotNull(InetAddress::getHostAddress),
            dohMs = dohMs,
            udpAddresses = udp.mapNotNull(InetAddress::getHostAddress),
            udpMs = System.currentTimeMillis() - udpStarted,
        )
    }

    /** DoH d'abord, puis UDP 53. */
    private fun query(raw: String, hostname: String, type: Int): List<InetAddress> {
        val viaDoh = dohQuery(raw, hostname, type)
        if (viaDoh.isNotEmpty()) return viaDoh
        val server = parseServer(raw) ?: return emptyList()
        return udpQuery(server, hostname, type)
    }

    private fun isAsciiHostname(hostname: String): Boolean =
        hostname.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' }

    /**
     * URL DoH RFC 8484 correspondant à une ligne de réglage.
     * Les résolveurs publics connus sont forcés sur leur IP afin d'éviter
     * une résolution préalable (et une récursion via [FrDns] / le DNS opérateur).
     */
    internal fun dohEndpoint(raw: String): String? {
        val value = raw.trim()
        if (value.isEmpty()) return null
        if (value.startsWith("https://", ignoreCase = true) ||
            value.startsWith("http://", ignoreCase = true)
        ) {
            return value.trimEnd('/')
        }
        val host = parseHost(value) ?: return null
        return when (host) {
            "1.1.1.1", "1.0.0.1", "cloudflare-dns.com" -> "https://1.1.1.1/dns-query"
            "8.8.8.8", "8.8.4.4", "dns.google" -> "https://8.8.8.8/dns-query"
            "9.9.9.9", "149.112.112.112", "dns.quad9.net" -> "https://9.9.9.9/dns-query"
            "94.140.14.14", "94.140.15.15", "dns.adguard-dns.com" -> "https://94.140.14.14/dns-query"
            else -> "https://$host/dns-query"
        }
    }

    private fun parseHost(raw: String): String? {
        val value = raw.trim().removePrefix("[").let { text ->
            if (text.endsWith(']') && text.count { it == ':' } > 1) {
                text.dropLast(1)
            } else {
                text
            }
        }
        if (value.isEmpty()) return null
        val lastColon = value.lastIndexOf(':')
        val looksLikeV6 = value.count { it == ':' } > 1
        return if (!looksLikeV6 && lastColon > 0 && value.substring(lastColon + 1).all(Char::isDigit)) {
            value.substring(0, lastColon).ifBlank { null }
        } else {
            value
        }
    }

    private fun parseServer(raw: String): Server? {
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true)
        ) {
            return null
        }
        return runCatching {
            val value = raw.trim()
            if (value.isEmpty()) return@runCatching null
            val address: InetAddress
            val port: Int
            val lastColon = value.lastIndexOf(':')
            val looksLikeV6 = value.count { it == ':' } > 1
            if (!looksLikeV6 && lastColon > 0 && value.substring(lastColon + 1).all(Char::isDigit)) {
                address = InetAddress.getByName(value.substring(0, lastColon))
                port = value.substring(lastColon + 1).toInt().takeIf { it in 1..65535 } ?: 53
            } else {
                address = InetAddress.getByName(value)
                port = 53
            }
            Server(address, port)
        }.getOrNull()
    }

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val TYPE_CNAME = 5

    private fun dohQuery(raw: String, hostname: String, type: Int): List<InetAddress> = runCatching {
        val endpoint = dohEndpoint(raw) ?: return emptyList()
        val id = ThreadLocalRandom.current().nextInt(0x10000)
        val query = buildQuery(id, hostname, type)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(query)
        val url = if ('?' in endpoint) "$endpoint&dns=$encoded" else "$endpoint?dns=$encoded"
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = DOH_TIMEOUT_MS
            readTimeout = DOH_TIMEOUT_MS
            instanceFollowRedirects = false
            useCaches = false
            setRequestProperty("Accept", "application/dns-message")
            setRequestProperty("User-Agent", "FR-Unified-DoH")
        }
        try {
            if (conn.responseCode !in 200..299) return emptyList()
            val bytes = conn.inputStream.use { stream ->
                val buffer = java.io.ByteArrayOutputStream(512)
                val chunk = ByteArray(512)
                var total = 0
                while (total < 4096) {
                    val read = stream.read(chunk, 0, minOf(chunk.size, 4096 - total))
                    if (read < 0) break
                    buffer.write(chunk, 0, read)
                    total += read
                }
                buffer.toByteArray()
            }
            parseResponse(bytes, bytes.size, id, type)
        } finally {
            runCatching { conn.disconnect() }
        }
    }.getOrDefault(emptyList())

    private fun udpQuery(server: Server, hostname: String, type: Int): List<InetAddress> = runCatching {
        val socket = DatagramSocket(null)
        try {
            val id = ThreadLocalRandom.current().nextInt(0x10000)
            val query = buildQuery(id, hostname, type)
            socket.soTimeout = UDP_TIMEOUT_MS
            socket.connect(InetSocketAddress(server.address, server.port))
            socket.send(DatagramPacket(query, query.size))
            val buffer = ByteArray(4096)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            parseResponse(buffer, packet.length, id, type)
        } finally {
            runCatching { socket.close() }
        }
    }.getOrDefault(emptyList())

    internal fun buildQuery(id: Int, hostname: String, type: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream(64 + hostname.length)
        fun writeShort(value: Int) {
            out.write((value ushr 8) and 0xFF)
            out.write(value and 0xFF)
        }
        writeShort(id)
        writeShort(0x0100) // RD
        writeShort(1) // QDCOUNT
        writeShort(0) // ANCOUNT
        writeShort(0) // NSCOUNT
        writeShort(0) // ARCOUNT
        hostname.split('.').forEach { label ->
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isNotEmpty() && bytes.size <= 63) {
                out.write(bytes.size)
                out.write(bytes)
            }
        }
        out.write(0)
        writeShort(type)
        writeShort(1) // QCLASS IN
        return out.toByteArray()
    }

    /**
     * Analyse une réponse DNS filaire. ANCOUNT est lu dans l'en-tête (octets 6-7),
     * pas après la question — l'ancienne lecture décalait toutes les réponses.
     */
    internal fun parseResponse(
        buffer: ByteArray,
        length: Int,
        expectedId: Int,
        wantedType: Int,
    ): List<InetAddress> {
        if (length < 12) return emptyList()
        val id = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        if (id != expectedId) return emptyList()
        val flags = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        if (flags and 0x8000 == 0) return emptyList() // réponse ? (QR)
        val rcode = flags and 0x000F
        if (rcode != 0) return emptyList()
        val questionCount = ((buffer[4].toInt() and 0xFF) shl 8) or (buffer[5].toInt() and 0xFF)
        val answerCount = ((buffer[6].toInt() and 0xFF) shl 8) or (buffer[7].toInt() and 0xFF)
        var cursor = 12
        repeat(questionCount.coerceIn(0, 16)) {
            cursor = skipName(buffer, cursor, length) ?: return emptyList()
            if (cursor + 4 > length) return emptyList()
            cursor += 4
        }
        val addresses = linkedSetOf<InetAddress>()
        var answers = 0
        while (cursor < length && answers < answerCount && answers < 32) {
            val ownerName = skipName(buffer, cursor, length) ?: return addresses.toList()
            cursor = ownerName
            if (cursor + 10 > length) return addresses.toList()
            val type = ((buffer[cursor].toInt() and 0xFF) shl 8) or (buffer[cursor + 1].toInt() and 0xFF)
            val dataLength = ((buffer[cursor + 8].toInt() and 0xFF) shl 8) or (buffer[cursor + 9].toInt() and 0xFF)
            cursor += 10
            if (cursor + dataLength > length) return addresses.toList()
            when {
                type == wantedType && wantedType == TYPE_A && dataLength == 4 ->
                    addresses += InetAddress.getByAddress(buffer.copyOfRange(cursor, cursor + 4))

                type == wantedType && wantedType == TYPE_AAAA && dataLength == 16 ->
                    addresses += InetAddress.getByAddress(buffer.copyOfRange(cursor, cursor + 16))

                type == TYPE_CNAME -> Unit // suivi des CNAME non nécessaire
            }
            cursor += dataLength
            answers++
        }
        return addresses.toList()
    }

    /** Retourne la position après le nom (labels ou pointeur compressé), ou null si invalide. */
    private fun skipName(buffer: ByteArray, start: Int, length: Int): Int? {
        var cursor = start
        var jumped = false
        var result = start
        var hops = 0
        while (hops++ < 64) {
            if (cursor >= length) return null
            val len = buffer[cursor].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (!jumped) result = cursor + 1
                    return result
                }

                len and 0xC0 == 0xC0 -> {
                    if (cursor + 1 >= length) return null
                    val pointer = ((len and 0x3F) shl 8) or (buffer[cursor + 1].toInt() and 0xFF)
                    if (!jumped) result = cursor + 2
                    jumped = true
                    cursor = pointer
                }

                len and 0xC0 == 0 -> {
                    if (cursor + 1 + len > length) return null
                    cursor += 1 + len
                }

                else -> return null // type d'étiquette non supporté
            }
        }
        return null
    }
}
