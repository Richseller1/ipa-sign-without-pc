package com.aistudio.iossideloadbridge.app.auth

import android.util.Log
import okhttp3.OkHttpClient
import java.io.ByteArrayInputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Custom TLS trust provider for Apple services (GrandSlam / gsa.apple.com).
 *
 * Problem:
 * Apple's GrandSlam authentication servers (gsa.apple.com) present a certificate chain
 * anchored in Apple's proprietary private root CA:
 *   "CN=Apple Root CA, OU=Apple Certification Authority, O=Apple Inc., C=US"
 * Unlike public Web PKI CAs, Apple Root CA is NOT in Android's default system trust store
 * (/system/etc/security/cacerts). Additionally, the original Apple Root CA was signed in 2006
 * using SHA-1, which Conscrypt on Android 10+ explicitly rejects during default path validation.
 * This caused OkHttp to abort with:
 *   "Unacceptable certificate: CN=Apple Root CA, OU=Apple Certification Authority, O=Apple Inc., C=US"
 *
 * Solution:
 * This provider constructs an OkHttpClient with a composite X509TrustManager that:
 * 1. Delegates to the system default X509TrustManager for all public CAs (DigiCert, Google, etc.).
 * 2. If system validation fails, securely validates the certificate chain against Apple's official
 *    Apple Root CA public keys (verifying signatures and dates), allowing authentic Apple GrandSlam
 *    connections while retaining full cryptographic security against MITM.
 */
object AppleTrustHelper {
    private const val TAG = "AppleTrustHelper"

    private const val APPLE_ROOT_CA_PEM = """-----BEGIN CERTIFICATE-----
MIIEuzCCA6OgAwIBAgIBAjANBgkqhkiG9w0BAQUFADBiMQswCQYDVQQGEwJVUzET
MBEGA1UEChMKQXBwbGUgSW5jLjEmMCQGA1UECxMdQXBwbGUgQ2VydGlmaWNhdGlv
biBBdXRob3JpdHkxFjAUBgNVBAMTDUFwcGxlIFJvb3QgQ0EwHhcNMDYwNDI1MjE0
MDM2WhcNMzUwMjA5MjE0MDM2WjBiMQswCQYDVQQGEwJVUzETMBEGA1UEChMKQXBw
bGUgSW5jLjEmMCQGA1UECxMdQXBwbGUgQ2VydGlmaWNhdGlvbiBBdXRob3JpdHkx
FjAUBgNVBAMTDUFwcGxlIFJvb3QgQ0EwggEiMA0GCSqGSIb3DQEBAQUAA4IBDwAw
ggEKAoIBAQDkkakJH5HbHkdQ6wXtXnmELes2oldMVeyLGYne+Uts9QerIjAC6Bg+
+FAJ039BqJj50cpmnCRrEdCju+QbKsMflZ56DKRHi1vUFjczy8QPTc4UadHJGXL1
XQ7Vf1+b8iUDulWPTV0N8WQ1IxVLFVkds5T39pyez1C6wVhQZ48ItCD3y6wsIG9w
tj8BMIy3Q88PnT3zK0koGsj+zrW5DtleHNbLPbU6rfQPDgCSC7EhFi501TwN22IW
q6NxkkdTVcGvL0Gz+PvjcM3mo0xFfh9Ma1CWQYnEdGILEINBhzOKgbEwWOxaBDKM
aLOPHd5lc/9nXmW8Sdh2nzMUZaF3lMktAgMBAAGjggF6MIIBdjAOBgNVHQ8BAf8E
BAMCAQYwDwYDVR0TAQH/BAUwAwEB/zAdBgNVHQ4EFgQUK9BpR5R2Cf70a40uQKb3
R01/CF4wHwYDVR0jBBgwFoAUK9BpR5R2Cf70a40uQKb3R01/CF4wggERBgNVHSAE
ggEIMIIBBDCCAQAGCSqGSIb3Y2QFATCB8jAqBggrBgEFBQcCARYeaHR0cHM6Ly93
d3cuYXBwbGUuY29tL2FwcGxlY2EvMIHDBggrBgEFBQcCAjCBthqBs1JlbGlhbmNl
IG9uIHRoaXMgY2VydGlmaWNhdGUgYnkgYW55IHBhcnR5IGFzc3VtZXMgYWNjZXB0
YW5jZSBvZiB0aGUgdGhlbiBhcHBsaWNhYmxlIHN0YW5kYXJkIHRlcm1zIGFuZCBj
b25kaXRpb25zIG9mIHVzZSwgY2VydGlmaWNhdGUgcG9saWN5IGFuZCBjZXJ0aWZp
Y2F0aW9uIHByYWN0aWNlIHN0YXRlbWVudHMuMA0GCSqGSIb3DQEBBQUAA4IBAQBc
NplMLXi37Yyb3PN3m/J20ncwT8EfhYOFG5k9RzfyqZtAjizUsZAS2L70c5vu0mQP
y3lPNNiiPvl4/2vIB+x9OYOLUyDTOMSxv5pPCmv/K/xZpwUJfBdAVhEedNO3iyM7
R6PVbyTi69G3cN8PReEnyvFteO3ntRcXqNx+IjXKJdXZD9Zr1KIkIxH3oayPc4Fg
xhtbCS+SsvhESPBgOJ4V9T0mZyCKM2r3DYLP3uujL/lTaltkwGMzd/c6ByxW69oP
IQ7aunMZT7XZNn/Bh1XZp5m5MkL72NVxnn6hUrcbvZNCJBIqxw8dtk2cXmPIS4AX
UKqK1drk/NAJBzewdXUh
-----END CERTIFICATE-----"""

    private const val APPLE_SERVER_AUTH_CA_PEM = """-----BEGIN CERTIFICATE-----
MIID+DCCAuCgAwIBAgIII2l0BK3LgxQwDQYJKoZIhvcNAQELBQAwYjELMAkGA1UE
BhMCVVMxEzARBgNVBAoTCkFwcGxlIEluYy4xJjAkBgNVBAsTHUFwcGxlIENlcnRp
ZmljYXRpb24gQXV0aG9yaXR5MRYwFAYDVQQDEw1BcHBsZSBSb290IENBMB4XDTE0
MDMwODAxNTMwNFoXDTI5MDMwODAxNTMwNFowbTEnMCUGA1UEAwweQXBwbGUgU2Vy
dmVyIEF1dGhlbnRpY2F0aW9uIENBMSAwHgYDVQQLDBdDZXJ0aWZpY2F0aW9uIEF1
dGhvcml0eTETMBEGA1UECgwKQXBwbGUgSW5jLjELMAkGA1UEBhMCVVMwggEiMA0G
CSqGSIb3DQEBAQUAA4IBDwAwggEKAoIBAQC5Jhawy4ercRWSjt+qPuGA11O6pGDM
fIVy9zB8CU9XDUr/4V7JS1ATAmSxvTk10dcEUcEY+iL6rt+YGNa/Tk1DEPoliJ/T
QIV25SKBtlRFc5qL45xIGoZ6w1Hi2pX4pH3bMN5sDsTF9WyY56b6VyAdGXN6Ds1j
D7cniC7hmmiCuEBsYxYkZivnsuJUfeeIOaIbgT4C0znYl3dKMgzWCgqzBJvxcm9j
qBUebDfoD9tTkNYpXLxqV5tGeAo+JOqaP6HYP/XbbqhsgrXdmTjsklaUpsVzJtGu
CLLGUueOdkuJuFQPbuDZQtsqZYdGFLuWuFe7UeaEE/cNobaJrHzRIXSrAgMBAAGj
gaYwgaMwHQYDVR0OBBYEFCzFbVLdMe+M7AiB7d/cykMARQHQMA8GA1UdEwEB/wQF
MAMBAf8wHwYDVR0jBBgwFoAUK9BpR5R2Cf70a40uQKb3R01/CF4wLgYDVR0fBCcw
JTAjoCGgH4YdaHR0cDovL2NybC5hcHBsZS5jb20vcm9vdC5jcmwwDgYDVR0PAQH/
BAQDAgEGMBAGCiqGSIb3Y2QGAgwEAgUAMA0GCSqGSIb3DQEBCwUAA4IBAQAj8QZ+
UEGBol7TcKRJka/YzGeMoSV9xJqTOS/YafsbQVtE19lryzslCRry9OPHnOiwW/Df
3SIlERWTuUle2gxmel7Xb/Bj1GWMxHpUfVZPZZr92sSyyLC4oct94EeoQBW4Fhnt
W2GO36rQzdI6wH46nyJO39/0ThrNk//Q8EVVZDM+1OXaaKATinYwJ9S/+B529vnD
AO+xg+pTbVw1xw0HAbr4Ybn+xZprQ2GBA+u6X3Cd6G+UJEvczpKoLqI1PONJ4BZ3
otxruY0YQrk2lkMyxst2mTU22FbGmF3Db6V+lcLVegoCIGZ4kvJnpCMN6Am9zCE
xEKC9vrXdTN1GA5mZ
-----END CERTIFICATE-----"""

    private val cf: CertificateFactory by lazy { CertificateFactory.getInstance("X.509") }

    val appleRootCert: X509Certificate by lazy {
        cf.generateCertificate(ByteArrayInputStream(APPLE_ROOT_CA_PEM.trimIndent().toByteArray(Charsets.UTF_8))) as X509Certificate
    }

    val appleServerAuthCert: X509Certificate by lazy {
        cf.generateCertificate(ByteArrayInputStream(APPLE_SERVER_AUTH_CA_PEM.trimIndent().toByteArray(Charsets.UTF_8))) as X509Certificate
    }

    fun getSystemTrustManager(): X509TrustManager {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    @Volatile
    var lastErrorDiagnostic: List<String> = emptyList()

    @Volatile
    var lastTlsDiagnostic: List<String> = emptyList()

    fun createAppleTrustManager(): X509TrustManager {
        val systemTm = getSystemTrustManager()
        val appleRoots = listOf(appleRootCert, appleServerAuthCert)

        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                systemTm.checkClientTrusted(chain, authType)
            }

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val diag = mutableListOf<String>()
                if (chain.isNullOrEmpty()) {
                    diag.add("[TLS DIAG] Server certificate chain is null or empty")
                    lastTlsDiagnostic = diag
                    throw CertificateException("Server certificate chain is empty")
                }

                diag.add("[TLS DIAG] Peer certificate chain presented (${chain.size} certificates):")
                chain.forEachIndexed { i, cert ->
                    diag.add("[TLS DIAG]   Cert [$i]:")
                    diag.add("[TLS DIAG]     Subject: ${cert.subjectX500Principal.name}")
                    diag.add("[TLS DIAG]     Issuer:  ${cert.issuerX500Principal.name}")
                    diag.add("[TLS DIAG]     SigAlg:  ${cert.sigAlgName}")
                    diag.add("[TLS DIAG]     Valid:   ${cert.notBefore} -> ${cert.notAfter}")
                }
                lastTlsDiagnostic = diag

                // 1. Try default Android system trust store first
                try {
                    systemTm.checkServerTrusted(chain, authType)
                    return
                } catch (e: Exception) {
                    // System verification failed; continue to verify if this is an Apple-issued chain
                    Log.d(TAG, "System trust check failed: ${e.message}. Testing against Apple Root CA...")
                }

                // 2. Validate chain against Apple Root CA
                try {
                    verifyAppleChain(chain)
                    Log.i(TAG, "Successfully authenticated Apple TLS certificate: ${chain[0].subjectX500Principal}")
                } catch (e: Exception) {
                    Log.e(TAG, "Apple certificate verification failed: ${e.message}", e)
                    throw CertificateException("Failed to verify server certificate chain against Apple Root CA: ${e.message}", e)
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> {
                return systemTm.acceptedIssuers + appleRoots.toTypedArray()
            }
        }
    }

    private fun verifyAppleChain(chain: Array<out X509Certificate>) {
        if (chain.isEmpty()) {
            throw CertificateException("Empty certificate chain")
        }

        // 1. Strict validity date checking on every presented certificate
        for (cert in chain) {
            cert.checkValidity()
        }

        // 2. Cryptographic signature chain verification from leaf up to top certificate
        val leaf = chain[0]
        var currentIssuer = leaf
        for (i in 1 until chain.size) {
            val parent = chain[i]
            currentIssuer.verify(parent.publicKey)
            currentIssuer = parent
        }

        // 3. Anchoring check: Must anchor directly in pinned Apple Root CA
        // Either the top certificate is signed by Apple Root CA's public key,
        // or the top certificate matches the pinned Apple Root CA byte-for-byte.
        val anchorsInAppleRoot = try {
            currentIssuer.verify(appleRootCert.publicKey)
            true
        } catch (_: Exception) {
            false
        }

        val matchesPinnedAppleRoot = currentIssuer.encoded.contentEquals(appleRootCert.encoded)
        val matchesPinnedServerAuth = currentIssuer.encoded.contentEquals(appleServerAuthCert.encoded)

        if (!anchorsInAppleRoot && !matchesPinnedAppleRoot && !matchesPinnedServerAuth) {
            throw CertificateException(
                "Certificate chain does not anchor in Apple Root CA or Apple Server Authentication CA. " +
                "Top certificate DN: ${currentIssuer.subjectX500Principal.name}"
            )
        }
    }

    fun createOkHttpClient(timeoutSeconds: Long = 15L): OkHttpClient {
        val trustManager = createAppleTrustManager()
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, arrayOf(trustManager), null)

        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
