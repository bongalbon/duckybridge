package org.duckybridge.security

/** RFC 7518: ES256 uses fixed-width unsigned R || S, unlike Java's ASN.1 DER. */
object Jose {
    fun derToJose(der: ByteArray): ByteArray {
        require(der.size in 8..72 && der[0] == 0x30.toByte() && (der[1].toInt() and 255) == der.size - 2)
        var offset = 2
        val out = ByteArray(64)
        repeat(2) { index ->
            require(offset + 2 <= der.size && der[offset++] == 0x02.toByte())
            val length = der[offset++].toInt() and 255
            require(length in 1..33 && offset + length <= der.size)
            var part = der.copyOfRange(offset, offset + length)
            require((part[0].toInt() and 0x80) == 0)
            if (part.size > 1 && part[0] == 0.toByte()) part = part.copyOfRange(1, part.size)
            require(part.size <= 32)
            part.copyInto(out, index * 32 + 32 - part.size)
            offset += length
        }
        require(offset == der.size)
        return out
    }
}
