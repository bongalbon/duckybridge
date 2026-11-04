package org.duckybridge.security

import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

class JoseTest {
    @Test fun javaEcdsaConvertsToFixedWidthJose() {
        val key = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        repeat(50) {
            val der = Signature.getInstance("SHA256withECDSA").run { initSign(key.private); update("DuckyBridge".toByteArray()); sign() }
            val jose = Jose.derToJose(der)
            assertEquals(64, jose.size)
            assertTrue(Signature.getInstance("SHA256withECDSA").run { initVerify(key.public); update("DuckyBridge".toByteArray()); verify(der) })
            // Verify using the original DER, then ensure reconstructed integers match.
            val rLength = der[3].toInt() and 255
            val r = java.math.BigInteger(der.copyOfRange(4, 4+rLength))
            val sLength = der[5+rLength].toInt() and 255
            val s = java.math.BigInteger(der.copyOfRange(6+rLength, 6+rLength+sLength))
            assertEquals(r, java.math.BigInteger(1, jose.copyOfRange(0, 32)))
            assertEquals(s, java.math.BigInteger(1, jose.copyOfRange(32, 64)))
        }
    }
    @Test fun rejectsMalformedDer() {
        for (bad in listOf(byteArrayOf(), ByteArray(72), byteArrayOf(0x30, 6, 2, 1, 0x80.toByte(), 2, 1, 1))) {
            assertThrows(IllegalArgumentException::class.java) { Jose.derToJose(bad) }
        }
    }
}
