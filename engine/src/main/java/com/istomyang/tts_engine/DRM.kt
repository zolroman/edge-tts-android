package com.istomyang.tts_engine

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.ParseException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

internal class DRM {
    companion object {
        private var clockSkewSeconds: Double = 0.0
        private val secureRandom = SecureRandom()

        fun genSecMsGec(): String {
            // ref: https://github.com/rany2/edge-tts/issues/290#issuecomment-2464956570
            var t = getUnixTSSec()
            t += 11644473600
            t -= (t % 300)
            t *= 1e9 / 100
            val s = "%d%s".format(t.toLong(), EdgeProtocol.TRUSTED_CLIENT_TOKEN).toByteArray(Charsets.US_ASCII)
            val digest = MessageDigest.getInstance("SHA-256").digest(s)
            return BigInteger(1, digest).toString(16).padStart(64, '0').uppercase()
        }

        fun genMuid(): String {
            val bytes = ByteArray(16)
            secureRandom.nextBytes(bytes)
            return bytes.joinToString("") { "%02X".format(it) }
        }

        fun adjustClockSkew(rfc2616Time: String) {
            val clientTime = getUnixTSSec()
            val serverTime = parseRfc2616Date(rfc2616Time)
            if (serverTime != null) {
                clockSkewSeconds = serverTime - clientTime
            }
        }

        private fun getUnixTSSec(): Double {
            return System.currentTimeMillis().toDouble() / 1000 + clockSkewSeconds
        }

        private fun parseRfc2616Date(rfc2616Time: String): Double? {
            val rfc2616Format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
            rfc2616Format.timeZone = TimeZone.getTimeZone("GMT")
            try {
                val date: Date = rfc2616Format.parse(rfc2616Time)
                return date.time.toDouble() / 1000
            } catch (e: ParseException) {
                e.printStackTrace()
                return null
            }
        }
    }
}
