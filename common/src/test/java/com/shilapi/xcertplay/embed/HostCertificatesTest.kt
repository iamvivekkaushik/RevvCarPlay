package com.shilapi.xcertplay.embed

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostCertificatesTest {
    @Test
    fun digestsBecomeTheirBytes() {
        val one = "00".repeat(31) + "FF"
        val two = "AB".repeat(32)
        val parsed = HostCertificates.parse("$one,$two")
        assertEquals(2, parsed.size)
        assertEquals(32, parsed[0].size)
        assertArrayEquals(byteArrayOf(0xFF.toByte()), parsed[0].copyOfRange(31, 32))
        assertTrue(parsed[1].all { it == 0xAB.toByte() })
    }

    @Test
    fun noneConfiguredTrustsNoOtherCertificate() {
        assertTrue(HostCertificates.parse("").isEmpty())
    }
}
