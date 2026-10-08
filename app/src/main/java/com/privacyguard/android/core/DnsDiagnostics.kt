package com.privacyguard.android.core

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.security.cert.CertificateException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLException
import javax.net.ssl.SSLPeerUnverifiedException

enum class DnsStage { CONFIGURATION, PROTECT, CONNECT, TLS_HANDSHAKE, WRITE, READ, VALIDATE }
enum class DnsFailureKind {
    TIMEOUT, CONNECTION_REFUSED, NETWORK_UNREACHABLE, TLS_AUTHENTICATION, TLS_HANDSHAKE,
    IO, INCOMPLETE_RESPONSE, INVALID_FRAME_LENGTH, INVALID_RESPONSE, TRUNCATED_RESPONSE,
    NO_RESPONSE, VPN_PROTECTION, CONFIGURATION, SERVER_ERROR, UNEXPECTED
}
enum class DnsResponseIssue { INVALID_QUERY, TOO_LARGE, SHORT_HEADER, TRANSACTION_ID, FLAGS, QUESTION_COUNT, SHORT_QUESTION, QUESTION_NAME, QUESTION_TYPE_CLASS }

/** Metadata only: never include exception messages, packet bytes, headers or URLs. */
data class DnsFailure(val kind: DnsFailureKind, val stage: DnsStage, val resolver: String = "", val protocol: DnsProtocol? = null,
                      val tlsName: String? = null, val elapsedMs: Long = 0, val timeoutMs: Int? = null,
                      val exceptionType: String? = null, val validation: DnsResponseIssue? = null,
                      val responseBytes: Int? = null, val serverCode: Int? = null)
data class DnsUpstreamResult(val response: ByteArray? = null, val failures: List<DnsFailure> = emptyList())

class DnsTransportException(val kind: DnsFailureKind, val stage: DnsStage, val timeoutMs: Int, cause: Exception? = null) :
    IOException("DNS ${kind.name} at ${stage.name}", cause)

object DnsDiagnostics {
    fun kind(error: Exception): DnsFailureKind {
        if (error is DnsTransportException) return error.kind
        val chain = mutableListOf<Throwable>(); var item: Throwable? = error
        while (item != null && chain.size < 8 && item !in chain) { chain += item; item = item.cause }
        return when {
            chain.any { it is CertificateException || it is CertPathValidatorException || it is SSLPeerUnverifiedException } -> DnsFailureKind.TLS_AUTHENTICATION
            chain.any { it is SocketTimeoutException } -> DnsFailureKind.TIMEOUT
            error is ConnectException -> DnsFailureKind.CONNECTION_REFUSED
            error is NoRouteToHostException -> DnsFailureKind.NETWORK_UNREACHABLE
            error is EOFException -> DnsFailureKind.INCOMPLETE_RESPONSE
            error is SSLException -> DnsFailureKind.TLS_HANDSHAKE
            error is IOException -> DnsFailureKind.IO
            else -> DnsFailureKind.UNEXPECTED
        }
    }
    fun failure(error: Exception, endpoint: DnsEndpoint? = null, protocol: DnsProtocol? = null, elapsedMs: Long = 0): DnsFailure {
        val transport = error as? DnsTransportException
        return DnsFailure(kind(error), transport?.stage ?: DnsStage.READ, endpoint?.display.orEmpty(), protocol,
            endpoint?.tlsName, elapsedMs, transport?.timeoutMs, (transport?.cause ?: error).javaClass.simpleName.take(80))
    }
}
