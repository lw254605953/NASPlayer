package com.megatron.player.cast

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class CastBackendTest {
    @Test fun unknownReceiverDurationPreservesSourceTimeline() {
        assertEquals(120_000L, castDurationMs(0, 120_000))
        assertEquals(120_000L, castDurationMs(Long.MIN_VALUE, 120_000))
        assertEquals(125_000L, castDurationMs(125_000, 120_000))
        assertEquals(0L, castDurationMs(0, -1))
    }
    @Test fun singleByteRangeMatchesHttpSemantics() {
        assertEquals(RelayRange(0, 99, false), relayRange(null, 100))
        assertEquals(RelayRange(10, 19, true), relayRange("bytes=10-19", 100))
        assertEquals(RelayRange(90, 99, true), relayRange("bytes=90-", 100))
        assertEquals(RelayRange(90, 99, true), relayRange("bytes=-10", 100))
        assertEquals(RelayRange(0, 99, true), relayRange("bytes=-999", 100))
        assertEquals(RelayRange(90, 99, true), relayRange("bytes=90-999", 100))
        listOf("bytes=100-", "bytes=9-1", "bytes=-0", "bytes=0-2,3-4", "bytes=9223372036854775808-", "items=1-2").forEach {
            assertThrows(IllegalArgumentException::class.java) { relayRange(it, 100) }
        }
    }

    @Test fun relayStreamsOnlyTheRequestedBytesAndStopsImmediately() {
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        val opens = AtomicInteger()
        val loopback = InetAddress.getByName("127.0.0.1")
        val server = RelayHttpServer(loopback, loopback, "/cast/test-secret", "video/mp4", bytes.size.toLong()) { start, length ->
            opens.incrementAndGet()
            ByteArrayInputStream(bytes, start.toInt(), length.toInt())
        }
        server.start()
        fun request(path: String, method: String = "GET", headers: String = ""): ByteArray =
            Socket(loopback, server.port).use { socket ->
                socket.soTimeout = 2000
                socket.getOutputStream().write("$method $path HTTP/1.1\r\nHost: localhost\r\n${headers}Connection: close\r\n\r\n".toByteArray())
                socket.getInputStream().readBytes()
            }
        fun header(reply: ByteArray) = reply.toString(Charsets.ISO_8859_1).substringBefore("\r\n\r\n")
        try {
            val partial = request("/cast/test-secret", headers = "Range: bytes=123-45678\r\n")
            assertTrue(header(partial).startsWith("HTTP/1.1 206"))
            assertTrue(header(partial).contains("Content-Range: bytes 123-45678/200000"))
            val body = partial.drop(header(partial).length + 4).toByteArray()
            assertArrayEquals(bytes.copyOfRange(123, 45679), body)
            val full = request("/cast/test-secret")
            assertArrayEquals(bytes, full.drop(header(full).length + 4).toByteArray())
            val head = request("/cast/test-secret", "HEAD")
            assertTrue(header(head).contains("Content-Length: 200000"))
            assertEquals(header(head).length + 4, head.size)
            assertEquals(2, opens.get())
            assertTrue(header(request("/other")).startsWith("HTTP/1.1 404"))
            assertTrue(header(request("/cast/test-secret", headers = "Range: bytes=999999-\r\n")).contains("Content-Range: bytes */200000"))
            val cors = header(request("/cast/test-secret", "OPTIONS", "Origin: https://www.gstatic.com\r\n"))
            assertTrue(cors.startsWith("HTTP/1.1 204"))
            assertTrue(cors.contains("Access-Control-Allow-Origin: https://www.gstatic.com"))
        } finally { server.close() }
        assertThrows(java.io.IOException::class.java) { Socket(loopback, server.port).close() }
    }

    @Test fun dlnaXmlFindsVersionedServiceAndAllowsOnlyAdvertisedTimeSeek() {
        val info = DlnaXml.description("""<root xmlns="urn:schemas-upnp-org:device-1-0"><URLBase>http://127.0.0.1:1234/base/</URLBase><device><deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType><friendlyName>TV &amp; 音响</friendlyName><UDN>uuid:tv1</UDN><serviceList><service><serviceType>urn:schemas-upnp-org:service:AVTransport:2</serviceType><controlURL>/ctl</controlURL><SCPDURL>av.xml</SCPDURL></service></serviceList></device></root>""", "http://127.0.0.1:1234/description.xml")
        assertEquals("http://127.0.0.1:1234/ctl", info.controlUrl)
        assertEquals("http://127.0.0.1:1234/base/av.xml", info.scpdUrl)
        assertEquals("TV & 音响", info.name)
        val caps = DlnaXml.capabilities("""<scpd xmlns="urn:schemas-upnp-org:service-1-0"><actionList><action><name>Play</name></action><action><name>Pause</name></action><action><name>Seek</name><argumentList><argument><name>Unit</name><relatedStateVariable>A_ARG_TYPE_SeekMode</relatedStateVariable></argument></argumentList></action></actionList><serviceStateTable><stateVariable><name>A_ARG_TYPE_SeekMode</name><allowedValueList><allowedValue>TRACK_NR</allowedValue><allowedValue>REL_TIME</allowedValue></allowedValueList></stateVariable></serviceStateTable></scpd>""")
        assertTrue("Pause" in caps.actions)
        assertEquals(setOf("REL_TIME"), caps.seekModes)
        val wire = DlnaXml.envelope("urn:schemas-upnp-org:service:AVTransport:2", "SetAVTransportURI", mapOf("CurrentURI" to "http://phone/cast/a?x=1&y=2", "CurrentURIMetaData" to "<DIDL-Lite/>"))
        assertTrue(wire.contains("x=1&amp;y=2"))
        assertTrue(wire.contains("&lt;DIDL-Lite/&gt;"))
        assertEquals(3_661_250, DlnaXml.timeMs("01:01:01.250"))
        assertEquals(0, DlnaXml.timeMs("NOT_IMPLEMENTED"))
    }

    @Test fun dlnaRejectsExternalEntitiesAndPreservesFaultCode() {
        assertThrows(Exception::class.java) { DlnaXml.document("""<!DOCTYPE a [<!ENTITY x SYSTEM "file:///etc/passwd">]><root>&x;</root>""") }
        val fault = DlnaXml.fault(DlnaXml.document("""<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><s:Fault><detail><UPnPError xmlns="urn:schemas-upnp-org:control-1-0"><errorCode>714</errorCode><errorDescription>Illegal MIME-type</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>"""))!!
        assertTrue(fault.message!!.contains("714"))
        assertTrue(fault.message!!.contains("Illegal MIME-type"))
    }
}
