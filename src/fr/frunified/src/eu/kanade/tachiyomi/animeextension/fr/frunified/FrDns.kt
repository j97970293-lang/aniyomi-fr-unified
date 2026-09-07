package eu.kanade.tachiyomi.animeextension.fr.frunified

import okhttp3.Dns
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadLocalRandom

/**
 * DNS UDP personnalisé, branché sur le client HTTP de l'extension.
 *
 * Le « DNS privé » d'Android ne fonctionne pas sur certains téléphones/opérateurs ;
 * cette résolution n'utilise que le réseau Java classique (UDP port 53), sans passer
 * par les réglages DNS de l'appareil. Elle s'applique à toutes les requêtes HTTP de
 * l'extension : catalogues, manifests, scripts des sources, sondes et sous-titres.
 *
 * Les requêtes finales de lecture/téléchargement étant effectuées par l'application
 * Aniyomi elle-même, ce DNS ne peut pas couvrir ces dernières (voir le guide des réglages).
 */
object FrDns : Dns {

    private const val TIMEOUT_MS = 2_500
    private const val CACHE_POSITIVE_MS = 10 * 60 * 1000L
    private const val CACHE_NEGATIVE_MS = 20 * 1000L
    private const val MAX_SERVERS = 3

    private data class CacheEntry(val expiresAt: Long, val addresses: List<InetAddress>)
    private val cache = ConcurrentHashMap<String, CacheEntry>()

    private data class Server(val address: InetAddress, val port: Int)

    override fun lookup(hostname: String): List<InetAddress> {
        val servers = FrSettings.dnsHosts
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
        val servers = rawServers.take(MAX_SERVERS).mapNotNull(::parseServer)
        if (servers.isEmpty()) return emptyList()
        val addresses = linkedSetOf<InetAddress>()
        // Interroge les serveurs dans l'ordre jusqu'à obtenir des adresses IPv4.
        for (server in servers) {
            val found = udpQuery(server, hostname, TYPE_A)
            addresses += found
            if (addresses.isNotEmpty()) break
        }
        if (addresses.isEmpty()) {
            for (server in servers) {
                val found = udpQuery(server, hostname, TYPE_AAAA)
                addresses += found
                if (addresses.isNotEmpty()) break
            }
        }
        return addresses.toList()
    }

    private fun isAsciiHostname(hostname: String): Boolean =
        hostname.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '.' }

    private fun parseServer(raw: String): Server? = runCatching {
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

    private const val TYPE_A = 1
    private const val TYPE_AAAA = 28
    private const val TYPE_CNAME = 5

    private fun udpQuery(server: Server, hostname: String, type: Int): List<InetAddress> = runCatching {
        val socket = DatagramSocket(null)
        try {
            val id = ThreadLocalRandom.current().nextInt(0x10000)
            val query = buildQuery(id, hostname, type)
            socket.soTimeout = TIMEOUT_MS
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

    private fun buildQuery(id: Int, hostname: String, type: Int): ByteArray {
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

    private fun parseResponse(buffer: ByteArray, length: Int, expectedId: Int, wantedType: Int): List<InetAddress> {
        if (length < 12) return emptyList()
        val id = ((buffer[0].toInt() and 0xFF) shl 8) or (buffer[1].toInt() and 0xFF)
        if (id != expectedId) return emptyList()
        val flags = ((buffer[2].toInt() and 0xFF) shl 8) or (buffer[3].toInt() and 0xFF)
        if (flags and 0x8000 == 0) return emptyList() // réponse ? (QR)
        val rcode = flags and 0x000F
        if (rcode != 0) return emptyList()
        var cursor = 12
        // Question (un seul) : nom compressé puis QTYPE/QCLASS.
        cursor = skipName(buffer, cursor, length) ?: return emptyList()
        if (cursor + 4 > length) return emptyList()
        cursor += 4
        if (cursor + 2 > length) return emptyList()
        val answerCount = ((buffer[cursor].toInt() and 0xFF) shl 8) or (buffer[cursor + 1].toInt() and 0xFF)
        cursor += 2
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
