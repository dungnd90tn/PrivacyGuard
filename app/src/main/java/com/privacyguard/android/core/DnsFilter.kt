package com.privacyguard.android.core

enum class Outcome(val label: String) {
    BLOCKED("Đã chặn"), FORWARDED("Đã gửi"), FAILED("Gặp lỗi")
}

data class DnsResult(val response: ByteArray, val decision: Decision, val outcome: Outcome)

object DnsFilter {
    /** Preparing a reply is not enforcement. Record it only after writing it to the TUN. */
    fun resolve(query: ByteArray, app: String?, policy: Policy, forward: (ByteArray) -> ByteArray?): DnsResult? {
        val question = Packets.question(query) ?: return null
        // Service-discovery names can contain underscores or a single label. Never invent a category.
        val decision = try {
            Rules.decide(question.domain, app, policy)
        } catch (_: IllegalArgumentException) {
            Decision(question.domain, Category.UNKNOWN, Action.ALLOW, "Tên DNS chưa phân loại")
        }
        if (decision.action == Action.BLOCK) {
            return DnsResult(Packets.dnsError(query, question, 3), decision, Outcome.BLOCKED)
        }
        val response = try { forward(query) } catch (_: Exception) { null }
        if (response == null || response.size > 65000 || !Packets.validResponse(query, response)) {
            return DnsResult(Packets.dnsError(query, question, 2), decision.copy(reason = "Không nhận được phản hồi DNS hợp lệ"), Outcome.FAILED)
        }
        val code = Packets.u16(response, 2) and 15
        if (code != 0 && code != 3) return DnsResult(response,
            decision.copy(reason = "Resolver trả lỗi DNS (mã $code)"), Outcome.FAILED)
        return DnsResult(response, decision, Outcome.FORWARDED)
    }
}
