package com.privacyguard.android

import com.privacyguard.android.core.DnsServer
import com.privacyguard.android.core.DnsServers
import com.privacyguard.android.core.DnsSettings
import org.json.JSONArray
import org.json.JSONObject

object DnsSettingsCodec {
    fun encode(settings: DnsSettings): String = JSONObject().apply {
        put("version", 1); put("selected", settings.active.id)
        put("custom", JSONArray().apply { settings.custom.forEach { server -> put(JSONObject()
            .put("id", server.id).put("name", server.name).put("primary", server.primary.address)
            .put("secondary", server.secondary?.address ?: "").put("port", server.primary.port)) } })
    }.toString()
    fun decode(raw: String?): DnsSettings {
        val json = runCatching { JSONObject(raw.orEmpty()) }.getOrNull() ?: return DnsSettings()
        val array = json.optJSONArray("custom") ?: JSONArray()
        val servers = linkedMapOf<String, DnsServer>()
        for (i in 0 until minOf(array.length(), DnsServers.MAX_CUSTOM)) runCatching {
            val item = array.getJSONObject(i)
            val server = DnsServers.custom(item.getString("id"), item.getString("name"), item.getString("primary"), item.optString("secondary"), if (item.has("port")) item.getInt("port") else 53)
            servers[server.id] = server
        }
        val selected = json.optString("selected", DnsServers.DEFAULT_ID)
        return DnsSettings(selected, servers.values.toList()).let { it.copy(selectedId = it.active.id) }
    }
}
