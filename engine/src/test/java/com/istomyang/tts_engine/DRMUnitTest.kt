package com.istomyang.tts_engine

import org.junit.Assert.assertTrue
import org.junit.Test

class DRMUnitTest {
    @Test
    fun secMsGecIsUppercaseSha256Hex() {
        val token = DRM.genSecMsGec()

        assertTrue(token.matches(Regex("[0-9A-F]{64}")))
    }

    @Test
    fun muidIsUppercase16ByteHex() {
        val muid = DRM.genMuid()

        assertTrue(muid.matches(Regex("[0-9A-F]{32}")))
    }
}
