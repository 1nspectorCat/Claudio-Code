package com.vladiko.voicebridge

import okhttp3.OkHttpClient
import okhttp3.Request
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// OkHttp client that trusts exactly one server certificate, identified by the
// SHA-256 fingerprint of its DER encoding (self-signed relay cert). Hostname
// verification is replaced by the pin: matching the fingerprint IS the identity.
object Net {
    // v0.36: one client per pin, reused. A fresh client per call meant a full TLS
    // handshake and a fresh connection pool on every tap — noticeable on mobile.
    private var cachedPin: String? = null
    private var cachedClient: OkHttpClient? = null
    private var cachedGet: OkHttpClient? = null

    @Synchronized
    fun client(pinHex: String): OkHttpClient {
        val pinKey = pinHex.trim().lowercase()
        cachedClient?.let { if (cachedPin == pinKey) return it }
        val c = buildClient(pinKey)
        cachedPin = pinKey
        cachedClient = c
        cachedGet = null
        return c
    }

    private fun buildClient(pin: String): OkHttpClient {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {}
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                if (chain.isEmpty()) throw CertificateException("empty chain")
                val fp = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
                    .joinToString("") { "%02x".format(it) }
                if (fp != pin) throw CertificateException("certificate pin mismatch: $fp")
            }
            override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
        }
        val ssl = SSLContext.getInstance("TLS")
        ssl.init(null, arrayOf<TrustManager>(tm), SecureRandom())
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .hostnameVerifier { _, _ -> true }
            .pingInterval(25, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    // Blocking GET returning the response body, or null on failure. Call off the
    // main thread. Uses a short-timeout clone (shares pool/dispatcher with the
    // cached client) so a dead relay can't hang the UI.
    fun get(pinHex: String, url: String): String? {
        return try {
            val c = getClient(pinHex)
            c.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (!r.isSuccessful) null else r.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun getClient(pinHex: String): OkHttpClient {
        val base = client(pinHex)
        cachedGet?.let { return it }
        val c = base.newBuilder()
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .build()
        cachedGet = c
        return c
    }
}
