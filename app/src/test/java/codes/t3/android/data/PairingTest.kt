package codes.t3.android.data

import codes.t3.android.data.pairing.Pairing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test fun directLink() {
        val t = Pairing.parse("http://192.168.1.20:3773/pair#token=7QK3M9X2HJ4P")!!
        assertEquals("http://192.168.1.20:3773/", t.httpBaseUrl)
        assertEquals("ws://192.168.1.20:3773/", t.wsBaseUrl)
        assertEquals("7QK3M9X2HJ4P", t.token)
    }

    @Test fun tokenInQueryAndHttps() {
        val t = Pairing.parse("https://box.tail.ts.net/pair?token=ABC")!!
        assertEquals("https://box.tail.ts.net/", t.httpBaseUrl)
        assertEquals("wss://box.tail.ts.net/", t.wsBaseUrl)
        assertEquals("ABC", t.token)
    }

    @Test fun hostedLinkUsesHostParam() {
        val t = Pairing.parse("https://app.t3.codes/pair?host=https%3A%2F%2Fmy.box%3A8443&label=x#token=TOK")!!
        assertEquals("https://my.box:8443/", t.httpBaseUrl)
        assertEquals("TOK", t.token)
    }

    @Test fun deepLinkWrapper() {
        val inner = java.net.URLEncoder.encode("http://10.0.0.5:3773/pair#token=ZZZ", "UTF-8")
        val t = Pairing.parse("t3code://connections/new?pairingUrl=$inner")!!
        assertEquals("http://10.0.0.5:3773/", t.httpBaseUrl)
        assertEquals("ZZZ", t.token)
    }

    @Test fun rejectsGarbage() {
        assertNull(Pairing.parse("hello"))
        assertNull(Pairing.parse("ftp://x/pair#token=1"))
    }

    @Test fun manualEntry() {
        assertEquals("http://192.168.1.100:3773/", Pairing.manual("192.168.1.100:3773", "abcd")!!.httpBaseUrl)
        assertEquals("ABCD", Pairing.manual("192.168.1.100:3773", "abcd")!!.token)
        assertEquals("http://devbox:3773/", Pairing.manual("devbox:3773", "X")!!.httpBaseUrl)
        assertEquals("https://t3.example.com/", Pairing.manual("t3.example.com", "X")!!.httpBaseUrl)
        assertEquals("http://localhost:3773/", Pairing.manual("localhost:3773", "X")!!.httpBaseUrl)
    }
}
