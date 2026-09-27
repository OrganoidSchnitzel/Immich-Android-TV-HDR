package nl.giejay.mediaslider.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HdrDiagnosticsTest {

    @Test
    fun `the api key never reaches the diagnostics screen`() {
        val redacted = HdrDiagnostics.redact(
            "http://192.168.178.100:2283/api/assets/abc/original?apiKey=A3Ofqc2D40i7NIVyUB7x"
        )!!

        assertFalse(redacted, redacted.contains("A3Ofqc2D40i7NIVyUB7x"))
        assertEquals("http://192.168.178.100:2283/api/assets/abc/original?apiKey=<redacted>", redacted)
    }

    @Test
    fun `other parameters survive and the key is found wherever it sits`() {
        assertEquals(
            "http://host/api/assets/abc/original?edited=false&apikey=<redacted>&x=1",
            HdrDiagnostics.redact("http://host/api/assets/abc/original?edited=false&apikey=secret&x=1")
        )
    }

    @Test
    fun `urls without a key are untouched`() {
        val url = "http://host/api/assets/abc/original?edited=false"
        assertEquals(url, HdrDiagnostics.redact(url))
        assertNull(HdrDiagnostics.redact(null))
    }
}
