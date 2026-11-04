package org.duckybridge.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class Identity {
    private val alias = "duckybridge-client-v1"
    private val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    init {
        if (!store.containsAlias(alias)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
    }
    private val publicDer get() = store.getCertificate(alias).publicKey.encoded
    val id get() = MessageDigest.getInstance("SHA-256").digest(publicDer).joinToString("") { "%02x".format(it) }
    val publicPem get() = "-----BEGIN PUBLIC KEY-----\n" + Base64.encodeToString(publicDer, Base64.NO_WRAP).chunked(64).joinToString("\n") + "\n-----END PUBLIC KEY-----\n"
    fun token(audience: String, op: JSONObject, requestId: String = UUID.randomUUID().toString()): String {
        val time = System.currentTimeMillis() / 1000
        val header = JSONObject().put("alg", "ES256").put("typ", "JWT").put("kid", id)
        val claims = JSONObject().put("v", 1).put("iss", "duckybridge-v1").put("sub", id).put("aud", audience)
            .put("iat", time).put("exp", time + 30).put("jti", requestId).put("op", op)
        val input = "${url64(header.toString().toByteArray())}.${url64(claims.toString().toByteArray())}"
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(store.getKey(alias, null) as java.security.PrivateKey); update(input.toByteArray(Charsets.US_ASCII)); sign()
        }
        return "$input.${url64(Jose.derToJose(der))}"
    }
    private fun url64(bytes: ByteArray) = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}
