package com.privacyguard.android.core

import java.net.InetAddress

/** Numeric-only endpoints avoid resolving a custom resolver's hostname through the VPN itself. */
data class DnsEndpoint(val address: String, val port: Int = 53, val tlsName: String? = null) {
    val bytes: ByteArray
    init {
        require(port in 1..65535) { "Cổng phải là số từ 1 đến 65535." }
        bytes = parseAddress(address)
        if (tlsName != null) require(tlsName == DnsServers.tlsName(tlsName)) { "Tên xác thực TLS chưa đúng." }
    }
    val display: String get() = if (port == 53) address else if (address.contains(':')) "[$address]:$port" else "$address:$port"

    companion object {
        fun parseAddress(input: String): ByteArray {
            val value = input.trim()
            require(value == input && value.isNotEmpty()) { "Nhập địa chỉ IP của máy chủ DNS, ví dụ 1.1.1.1." }
            val bytes = if (value.contains(':')) {
                require(value.matches(Regex("[0-9a-fA-F:.]+")) && value.length <= 45) {
                    "Nhập địa chỉ IPv6 đầy đủ; không thêm ngoặc, cổng hoặc tên mạng."
                }
                runCatching { InetAddress.getByName(value).address }.getOrNull()
                    ?: throw IllegalArgumentException("Địa chỉ IPv6 chưa đúng. Hãy kiểm tra lại.")
            } else {
                val parts = value.split('.')
                require(parts.size == 4 && parts.all { part -> part.matches(Regex("[0-9]{1,3}")) && part.toInt() in 0..255 }) {
                    "Nhập địa chỉ IP như 1.1.1.1 hoặc IPv6. URL https:// và tên máy chủ chưa được hỗ trợ."
                }
                ByteArray(4) { parts[it].toInt().toByte() }
            }
            val address = InetAddress.getByAddress(bytes)
            require(!address.isAnyLocalAddress && !address.isMulticastAddress && !address.isLinkLocalAddress &&
                !(bytes.size == 4 && bytes.all { it == (-1).toByte() })) {
                "Địa chỉ này không dùng được làm máy chủ DNS. Hãy chọn IP của máy chủ hoặc router."
            }
            val reserved = listOf("10.111.0.1", "10.111.0.2", "fd42:7072:6976::1", "fd42:7072:6976::2")
            require(reserved.none { InetAddress.getByName(it).address.contentEquals(bytes) }) {
                "Đây là địa chỉ nội bộ của PrivacyGuard. Hãy chọn địa chỉ máy chủ DNS khác."
            }
            return bytes
        }
        fun normalized(input: String, port: Int = 53): DnsEndpoint {
            val bytes = parseAddress(input.trim())
            return DnsEndpoint(InetAddress.getByAddress(bytes).hostAddress.orEmpty(), port)
        }
    }
}

data class DnsServer(val id: String, val name: String, val description: String,
                     val primary: DnsEndpoint, val secondary: DnsEndpoint? = null, val tlsName: String = "") {
    val custom: Boolean get() = id.startsWith("custom-")
    val endpoints: List<DnsEndpoint> get() = listOfNotNull(primary, secondary).distinct()
}

object DnsServers {
    const val DEFAULT_ID = "quad9"
    const val MAX_CUSTOM = 20
    val presets = listOf(
        DnsServer("quad9", "Quad9", "Có lọc tên miền nguy hiểm theo danh sách của Quad9.", DnsEndpoint("9.9.9.9"), DnsEndpoint("149.112.112.112"), "dns.quad9.net"),
        DnsServer("cloudflare", "Cloudflare", "Dịch vụ DNS phổ thông, không lọc nội dung.", DnsEndpoint("1.1.1.1"), DnsEndpoint("1.0.0.1"), "one.one.one.one"),
        DnsServer("google", "Google", "Dịch vụ Google Public DNS.", DnsEndpoint("8.8.8.8"), DnsEndpoint("8.8.4.4"), "dns.google"),
        DnsServer("adguard", "AdGuard", "Có lọc quảng cáo và theo dõi theo danh sách của AdGuard.", DnsEndpoint("94.140.14.14"), DnsEndpoint("94.140.15.15"), "dns.adguard-dns.com")
    )
    fun custom(id: String, name: String, primary: String, secondary: String = "", port: Int = 53, tlsName: String = ""): DnsServer {
        require(id.matches(Regex("custom-[A-Za-z0-9-]{1,50}"))) { "Mã máy chủ tùy chỉnh không hợp lệ." }
        val label = name.trim()
        require(label.isNotEmpty() && label.length <= 40 && label.none { it.isISOControl() }) { "Đặt tên từ 1 đến 40 ký tự cho máy chủ." }
        val first = DnsEndpoint.normalized(primary, port)
        val second = secondary.trim().takeIf { it.isNotEmpty() }?.let { DnsEndpoint.normalized(it, port) }
        return DnsServer(id, label, "Máy chủ do bạn thêm.", first, second?.takeIf { !it.bytes.contentEquals(first.bytes) }, tlsName.trim().takeIf { it.isNotEmpty() }?.let { DnsServers.tlsName(it) }.orEmpty())
    }
    fun tlsName(input: String): String {
        val name = runCatching { Rules.normalize(input) }.getOrNull()
        require(name != null && name.any { it in 'a'..'z' } && !name.contains('*')) {
            "Nhập tên xác thực TLS do nhà cung cấp công bố, ví dụ dns.quad9.net; không nhập IP hoặc URL."
        }
        return name
    }
}

enum class DnsMode { PLAIN, TLS }

data class DnsSettings(val selectedId: String = DnsServers.DEFAULT_ID, val custom: List<DnsServer> = emptyList(), val mode: DnsMode = DnsMode.PLAIN) {
    val servers: List<DnsServer> get() = DnsServers.presets + custom
    val active: DnsServer get() = servers.find { it.id == selectedId } ?: DnsServers.presets.first()
    fun requireUsable() {
        if (mode == DnsMode.TLS) require(active.tlsName.isNotEmpty()) {
            "${active.name} chưa có tên xác thực TLS. Hãy sửa máy chủ và thêm tên do nhà cung cấp công bố, hoặc chọn máy chủ khác."
        } else require(active.endpoints.none { it.port == 853 }) {
            "Cổng 853 dành cho DNS mã hóa. Hãy bật TLS và thêm tên xác thực, hoặc đổi cổng DNS thường về 53."
        }
    }
    val upstream: List<DnsEndpoint> get() {
        requireUsable()
        return if (mode == DnsMode.TLS) active.endpoints.map { DnsEndpoint(it.address, 853, active.tlsName) } else active.endpoints
    }
}
