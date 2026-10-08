package com.privacyguard.android

import com.privacyguard.android.core.DnsFailure
import com.privacyguard.android.core.DnsFailureKind
import com.privacyguard.android.core.DnsProtocol
import com.privacyguard.android.core.DnsResponseIssue
import com.privacyguard.android.core.DnsResult
import com.privacyguard.android.core.DnsStage
import org.json.JSONArray
import org.json.JSONObject

data class DnsFailureDetails(val summary: String, val queryType: Int?, val attempts: List<DnsFailure>)

/** Reuses the reason column; old events stay readable. No raw exception messages or packet data. */
object DnsFailureCodec {
    private const val PREFIX = "DNS_DIAGNOSTIC_V1:"
    fun encode(result: DnsResult): String {
        if (result.failures.isEmpty()) return result.decision.reason
        return PREFIX + JSONObject().apply {
            put("summary", result.decision.reason); result.queryType?.let { put("queryType", it) }
            put("attempts", JSONArray().apply { result.failures.take(4).forEach { failure ->
                put(JSONObject().apply {
                    put("kind", failure.kind.name); put("stage", failure.stage.name)
                    put("resolver", failure.resolver.take(100)); put("elapsedMs", failure.elapsedMs)
                    failure.protocol?.let { put("protocol", it.name) }; failure.tlsName?.let { put("tlsName", it.take(253)) }
                    failure.timeoutMs?.let { put("timeoutMs", it) }; failure.exceptionType?.let { put("exceptionType", it.take(80)) }
                    failure.validation?.let { put("validation", it.name) }; failure.responseBytes?.let { put("responseBytes", it) }
                    failure.serverCode?.let { put("serverCode", it) }
                })
            } })
        }.toString()
    }
    fun decode(reason: String): DnsFailureDetails? {
        if (!reason.startsWith(PREFIX) || reason.length > 8192) return null
        return runCatching {
            val json = JSONObject(reason.removePrefix(PREFIX)); val attempts = json.getJSONArray("attempts")
            val parsed = (0 until minOf(attempts.length(), 4)).map { index ->
                val item = attempts.getJSONObject(index)
                DnsFailure(DnsFailureKind.valueOf(item.getString("kind")), DnsStage.valueOf(item.getString("stage")), item.optString("resolver").take(100),
                    item.optString("protocol").takeIf { it.isNotBlank() }?.let(DnsProtocol::valueOf),
                    item.optString("tlsName").takeIf { it.isNotBlank() }?.take(253), item.optLong("elapsedMs").coerceAtLeast(0),
                    if (item.has("timeoutMs")) item.getInt("timeoutMs") else null,
                    item.optString("exceptionType").takeIf { it.isNotBlank() }?.take(80),
                    item.optString("validation").takeIf { it.isNotBlank() }?.let(DnsResponseIssue::valueOf),
                    if (item.has("responseBytes")) item.getInt("responseBytes") else null,
                    if (item.has("serverCode")) item.getInt("serverCode") else null)
            }
            DnsFailureDetails(json.getString("summary").take(300), if (json.has("queryType")) json.getInt("queryType") else null, parsed)
        }.getOrNull()
    }
    fun technical(reason: String): String {
        val details = decode(reason) ?: return reason
        return buildString {
            append(details.summary)
            details.queryType?.let { append("\nLoại truy vấn: ${when (it) { 1 -> "A (IPv4)"; 28 -> "AAAA (IPv6)"; 65 -> "HTTPS"; else -> "TYPE$it" }}") }
            details.attempts.forEachIndexed { index, failure ->
                append("\n\nLần thử ${index + 1}: ${failure.resolver.ifEmpty { "Chưa kết nối máy chủ" }}")
                append("\nGiao thức: ${failure.protocol?.name ?: "—"} · Bước: ${failure.stage.name}")
                append("\nLỗi: ${failure.kind.name} · Thời gian: ${failure.elapsedMs} ms")
                failure.timeoutMs?.let { append(" · Timeout mỗi bước: $it ms") }
                failure.tlsName?.let { append("\nTên xác thực TLS: $it") }
                failure.exceptionType?.let { append("\nLoại exception: $it") }
                failure.validation?.let { append("\nKiểm tra DNS: ${it.name}") }
                failure.responseBytes?.let { append("\nĐã nhận: $it byte") }
                failure.serverCode?.let { append("\nMã máy chủ DNS trả về: $it") }
            }
        }
    }
}
