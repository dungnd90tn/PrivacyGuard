package com.privacyguard.android

import android.content.Context

data class NotificationRule(val packageName: String, val keyword: String)

object NotificationRules {
    private const val PREFS = "notification_filter"
    private const val ENABLED = "enabled"
    private const val RULES = "rules"

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(ENABLED, enabled).apply()
    }

    fun load(context: Context): List<NotificationRule> = prefs(context).getStringSet(RULES, emptySet())
        .orEmpty().mapNotNull { value ->
            val separator = value.indexOf('|')
            if (separator <= 0 || separator == value.lastIndex) null
            else NotificationRule(value.substring(0, separator), value.substring(separator + 1))
        }.sortedWith(compareBy({ it.packageName }, { it.keyword }))

    fun add(context: Context, rule: NotificationRule) {
        require(rule.packageName.isNotBlank() && !rule.packageName.contains('|'))
        require(rule.keyword.isNotBlank() && !rule.keyword.contains('|') && rule.keyword.length <= 120)
        val updated = load(context).toMutableSet().apply { add(rule.copy(keyword = rule.keyword.trim())) }
        save(context, updated)
    }

    fun remove(context: Context, rule: NotificationRule) {
        save(context, load(context).filterNot { it == rule })
    }

    fun shouldBlock(packageName: String, title: String, body: String, rules: List<NotificationRule>): Boolean {
        return rules.any { rule ->
            (rule.packageName == "*" || rule.packageName == packageName) &&
                (title.contains(rule.keyword, ignoreCase = true) || body.contains(rule.keyword, ignoreCase = true))
        }
    }

    private fun save(context: Context, rules: Collection<NotificationRule>) {
        prefs(context).edit().putStringSet(RULES, rules.map { "${it.packageName}|${it.keyword}" }.toSet()).apply()
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
