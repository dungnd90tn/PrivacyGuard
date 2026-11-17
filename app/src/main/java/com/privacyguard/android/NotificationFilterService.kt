package com.privacyguard.android

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationFilterService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!NotificationRules.enabled(this) || sbn.packageName == packageName) return
        val rules = NotificationRules.load(this)
        if (rules.isEmpty()) return
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = buildString {
            append(extras.getCharSequence(Notification.EXTRA_TEXT) ?: "")
            append(' ')
            append(extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: "")
            extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { append(' '); append(it) }
        }
        if (NotificationRules.shouldBlock(sbn.packageName, title, body, rules)) {
            cancelNotification(sbn.key)
        }
    }
}
