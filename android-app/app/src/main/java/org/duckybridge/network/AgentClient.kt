package org.duckybridge.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.duckybridge.data.Device
import org.duckybridge.security.Identity
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.*

class AgentClient(private val identity: Identity) {
    private var socket: WebSocket? = null
    private var audience: String? = null
    private var client: OkHttpClient? = null
    @Volatile private var generation = 0
    private fun pinned(device: Device): OkHttpClient {
        require(Regex("[a-f0-9]{64}").matches(device.fingerprint)) { "Empreinte SHA-256 complète requise" }
        require(device.port in 1..65535)
        // A private self-signed identity is explicitly trusted by fingerprint, never by hostname alone.
        val trust = object : X509TrustManager {
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) { throw CertificateException("Pas un serveur") }
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                val cert = chain.firstOrNull() ?: throw CertificateException("Certificat absent")
                cert.checkValidity()
                cert.verify(cert.publicKey)
                val expected = device.fingerprint.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(cert.encoded), expected)) throw CertificateException("Identité de l'agent différente")
            }
        }
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        return OkHttpClient.Builder().sslSocketFactory(context.socketFactory, trust)
            .hostnameVerifier { _, session ->
                // LAN addresses change. The exact pinned leaf certificate defines the identity.
                runCatching { MessageDigest.getInstance("SHA-256").digest(session.peerCertificates[0].encoded).joinToString("") { "%02x".format(it) } == device.fingerprint }.getOrDefault(false)
            }.connectionSpecs(listOf(ConnectionSpec.MODERN_TLS)).connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false).followSslRedirects(false).build()
    }
    fun pair(device: Device): String {
        val http = pinned(device)
        try {
            val body = JSONObject().put("name", android.os.Build.MODEL.take(80)).put("public_key_pem", identity.publicPem)
            val request = Request.Builder().url(url(device, "/v1/pair", false)).post(body.toString().toRequestBody("application/json".toMediaType())).build()
            return http.newCall(request).execute().use { response ->
                val result = response.body?.string().orEmpty()
                check(response.isSuccessful) { "Appairage refusé : $result" }; result
            }
        } finally { http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    fun connect(device: Device, onEvent: (JSONObject) -> Unit, onError: (String) -> Unit) {
        close()
        val currentGeneration = generation
        val http = pinned(device); client = http
        socket = http.newWebSocket(Request.Builder().url(url(device, "/v1/ws", true)).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (generation != currentGeneration) return
                runCatching {
                    val event = JSONObject(text)
                    if (event.optString("type") == "hello") {
                        check(event.getInt("v") == 1 && event.getString("agent_id") == device.fingerprint) { "Identité incompatible" }
                        audience = event.getString("audience")
                        webSocket.send(identity.token(audience!!, JSONObject().put("type", "status")))
                    } else onEvent(event)
                }.onFailure { webSocket.cancel(); onError(it.message ?: "Réponse invalide") }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (generation == currentGeneration) { audience = null; onError(t.message ?: "Connexion interrompue") } }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { if (generation == currentGeneration) { audience = null; webSocket.close(code, reason); onError("Session fermée : reconnectez-vous") } }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (generation == currentGeneration) audience = null }
        })
    }
    fun send(op: JSONObject, id: String): Boolean {
        val target = audience ?: error("Connexion et authentification requises")
        return socket?.send(identity.token(target, op, id)) == true
    }
    fun close() { generation++; audience = null; socket?.cancel(); socket = null; client?.connectionPool?.evictAll(); client?.dispatcher?.executorService?.shutdown(); client = null }
    private fun url(d: Device, path: String, ws: Boolean): String = HttpUrl.Builder().scheme("https").host(d.host.trim()).port(d.port).encodedPath(path).build().toString().let { if (ws) it.replaceFirst("https://", "wss://") else it }
}
