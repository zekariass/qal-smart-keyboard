package com.qalsmartkeyboard.ime.utilities

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T8.3: the strings a QR code carries, and the QR panel state. */
class QrPayloadTest {
    @Test fun urlsGetAScheme() {
        assertEquals("https://example.com", QrPayload.url("example.com"))
        assertEquals("https://example.com/a?b=c", QrPayload.url("  example.com/a?b=c \n"))
        assertEquals("http://example.com", QrPayload.url("http://example.com"))
        assertEquals("mailto:a@b.et", QrPayload.url("mailto:a@b.et"))
        assertEquals("https://localhost:8080/x", QrPayload.url("localhost:8080/x"))
        assertNull(QrPayload.url("   "))
    }

    @Test fun wifiPayload() {
        assertEquals("WIFI:T:WPA;S:HomeNet;P:hunter22;;", QrPayload.wifi("HomeNet", "hunter22"))
        assertEquals("WIFI:T:nopass;S:Coffee;;", QrPayload.wifi("Coffee", "ignored", WifiSecurity.NONE))
        assertEquals("WIFI:T:WEP;S:Old;P:key-1;;", QrPayload.wifi("Old", "key-1", WifiSecurity.WEP))
        assertEquals("WIFI:T:WPA;S:Hidden;P:pw pw;H:true;;", QrPayload.wifi("Hidden", "pw pw", hidden = true))
    }

    @Test fun wifiEscapesSpecialCharacters() {
        assertEquals("WIFI:T:WPA;S:a\\;b\\,c\\:d\\\\e\\\"f;P:p\\;w;;", QrPayload.wifi("a;b,c:d\\e\"f", "p;w"))
    }

    @Test fun hexOnlyValuesAreQuoted() {
        assertEquals("WIFI:T:WPA;S:\"1234\";P:\"deadBEEF\";;", QrPayload.wifi("1234", "deadBEEF"))
    }

    @Test fun mecardAndVcard() {
        assertEquals("MECARD:N:Bekele,Abebe;TEL:+251911223344;EMAIL:a@b.et;;", QrPayload.mecard("Abebe", "Bekele", "+251911223344", "a@b.et"))
        assertEquals("MECARD:N:,Abebe;;", QrPayload.mecard("Abebe"))
        assertEquals("MECARD:N:O\\;Neil,A\\,B;;", QrPayload.mecard("A,B", "O;Neil"))
        val v = QrPayload.vcard("Abebe", "Bekele", "+251911223344", "a@b.et")
        assertEquals(
            "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Bekele;Abebe;;;\r\nFN:Abebe Bekele\r\nTEL:+251911223344\r\nEMAIL:a@b.et\r\nEND:VCARD",
            v,
        )
        assertTrue(QrPayload.vcard("A;B,C\nD").contains("N:;A\\;B\\,C\\nD;;;"))
    }

    @Test fun controllerStates() {
        val c = QrController()
        assertEquals(QrState.Empty, c.state())
        assertNull(c.payload())
        c.setSource("example.com")
        assertEquals("example.com", c.payload())
        c.selectKind(QrKind.URL)
        assertEquals("https://example.com", c.payload())
        val ready = c.state() as QrState.Ready
        assertEquals("https://example.com", ready.payload)
        assertTrue(ready.matrix.size >= 21)
        c.selectKind(QrKind.WIFI)
        c.setSource("Home Net\nsecret")
        assertEquals("WIFI:T:WPA;S:Home Net;P:secret;;", c.payload())
        c.selectSecurity(WifiSecurity.NONE)
        assertEquals("WIFI:T:nopass;S:Home Net;;", c.payload())
        c.setSource("\nonly password")
        assertNull(c.payload())
        c.selectKind(QrKind.TEXT)
        c.setSource("x".repeat(QrController.MAX_SOURCE_CHARS + 50))
        assertEquals(QrState.TooLong, c.state())
        c.clear()
        assertEquals(QrState.Empty, c.state())
    }
}
