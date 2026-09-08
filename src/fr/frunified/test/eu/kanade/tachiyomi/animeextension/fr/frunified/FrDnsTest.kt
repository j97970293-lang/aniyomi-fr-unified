package eu.kanade.tachiyomi.animeextension.fr.frunified

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.reflect.Proxy

class FrDnsTest {
    @Test
    fun dohEndpointMapsPublicResolversToTheirIp() {
        assertEquals("https://1.1.1.1/dns-query", FrDns.dohEndpoint("1.1.1.1"))
        assertEquals("https://1.1.1.1/dns-query", FrDns.dohEndpoint("1.1.1.1:53"))
        assertEquals("https://1.1.1.1/dns-query", FrDns.dohEndpoint("cloudflare-dns.com"))
        assertEquals("https://8.8.8.8/dns-query", FrDns.dohEndpoint("8.8.8.8"))
        assertEquals("https://9.9.9.9/dns-query", FrDns.dohEndpoint("9.9.9.9"))
        assertEquals(
            "https://cloudflare-dns.com/dns-query",
            FrDns.dohEndpoint("https://cloudflare-dns.com/dns-query"),
        )
        assertEquals(
            "https://dns.google/dns-query",
            FrDns.dohEndpoint("https://dns.google/dns-query/"),
        )
    }

    @Test
    fun dnsHostsKeepDohUrlsAndPlainIps() {
        FrSettings.init(
            preferences(
                mapOf(
                    FrSettings.KEY_DNS_HOSTS to "https://1.1.1.1/dns-query\n8.8.8.8\n9.9.9.9:53",
                ),
            ),
        )
        assertEquals(
            listOf("https://1.1.1.1/dns-query", "8.8.8.8", "9.9.9.9:53"),
            FrSettings.dnsHosts,
        )
    }

    @Test
    fun parseResponseReadsAnswerCountFromHeaderNotAfterTheQuestion() {
        val id = 0x1234
        val query = FrDns.buildQuery(id, "example.com", 1)
        val packet = ByteArrayOutputStream()
        packet.write(query, 0, 2) // ID
        packet.write(0x81)
        packet.write(0x80) // QR + RD + RA
        packet.write(query, 4, 2) // QDCOUNT
        packet.write(0)
        packet.write(1) // ANCOUNT = 1 (l'ancienne lecture le prenait après la question)
        packet.write(query, 8, query.size - 8) // NSCOUNT, ARCOUNT, question
        packet.write(0xC0)
        packet.write(0x0C) // pointeur vers le nom à l'octet 12
        packet.write(0)
        packet.write(1) // TYPE A
        packet.write(0)
        packet.write(1) // CLASS IN
        packet.write(0)
        packet.write(0)
        packet.write(0)
        packet.write(60) // TTL
        packet.write(0)
        packet.write(4) // RDLENGTH
        packet.write(93)
        packet.write(184)
        packet.write(216)
        packet.write(34)

        val bytes = packet.toByteArray()
        val addresses = FrDns.parseResponse(bytes, bytes.size, id, 1)
        assertEquals(1, addresses.size)
        assertEquals("93.184.216.34", addresses.single().hostAddress)
    }

    @Test
    fun buildQueryContainsTheAskedName() {
        val query = FrDns.buildQuery(1, "api.themoviedb.org", 1)
        val asText = String(query, Charsets.ISO_8859_1)
        assertTrue(asText.contains("themoviedb"))
        assertTrue(asText.contains("org"))
    }

    private fun preferences(values: Map<String, Any?>): SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { proxy, method, args ->
        when (method.name) {
            "getAll" -> values
            "contains" -> values.containsKey(args?.firstOrNull())
            "toString" -> "FR Unified dns preferences"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === args?.firstOrNull()
            else -> null
        }
    } as SharedPreferences
}
