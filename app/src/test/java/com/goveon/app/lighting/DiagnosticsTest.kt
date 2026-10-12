package com.goveon.app.lighting

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsTest {
    @Test fun `local diagnostic text keeps GATT reasons while redacting network device and credentials`() {
        val address = List(6) { "AB" }.joinToString(":")
        val key = "test".repeat(8)
        val email = "test" + "@" + "example.org"
        val text = safeDiagnosticText("GATT status 133 for $address on ${listOf(192, 168, 1, 20).joinToString(".")} api_key=$key contact=$email")
        assertTrue(text.contains("GATT status 133"))
        assertFalse(text.contains(address)); assertFalse(text.contains(key)); assertFalse(text.contains(email))
        assertTrue(text.contains("[device]")); assertTrue(text.contains("[network]"))
    }
    @Test fun `diagnostic length stays bounded`() {
        assertEquals(1000, safeDiagnosticText("x".repeat(2000)).length)
    }
}
