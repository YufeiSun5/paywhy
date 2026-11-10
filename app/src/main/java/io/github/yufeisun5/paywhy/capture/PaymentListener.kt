package io.github.yufeisun5.paywhy.capture

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import io.github.yufeisun5.paywhy.PayWhyApp
import io.github.yufeisun5.paywhy.core.Notice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PaymentListener : NotificationListenerService() {
    override fun onListenerConnected() { connected.value = true }
    override fun onListenerDisconnected() {
        connected.value = false
        val now = android.os.SystemClock.elapsedRealtime()
        if ((application as PayWhyApp).settings.value.captureEnabled && now - lastRebindAt > 60_000) {
            lastRebindAt = now
            runCatching { requestRebind(ComponentName(this, PaymentListener::class.java)) }
        }
    }
    override fun onDestroy() { connected.value = false; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = application as PayWhyApp
        val settings = app.settings.value
        val enabled = settings.captures(sbn.packageName)
        if (!enabled) return // Never extract other apps' content.
        val n = sbn.notification
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val now = System.currentTimeMillis()
        val posted = n.`when`.takeIf { it > 0 && it <= now + 60_000 } ?: sbn.postTime
        val messages = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)?.messages.orEmpty()
        val notices = if (messages.isNotEmpty()) messages.map { message ->
            val text = message.text?.toString().orEmpty()
            Notice(sbn.packageName, sbn.key, title.take(256), text.take(8000),
                message.timestamp.takeIf { it > 0 && it <= now + 60_000 } ?: posted, now,
                title.length > 256 || text.length > 8000)
        } else {
            // Do not count a group summary as another payment; individual notifications are captured.
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
            val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.map { it.toString() }.orEmpty()
            val texts = if (lines.isNotEmpty()) lines else listOf(
                extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.takeIf { it.isNotBlank() }
                    ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty())
            texts.map { text -> Notice(sbn.packageName, sbn.key, title.take(256), text.take(8000), posted, now,
                title.length > 256 || text.length > 8000) }
        }
        app.scope.launch {
            val capture = CaptureStore(app.db)
            for (notice in notices) {
                runCatching {
                    capture.accept(notice, settings)
                }.onFailure { lastError.value = "采集保存失败，请检查设备剩余空间。" }
            }
        }
    }
    companion object {
        private var lastRebindAt = -60_001L
        private val connected = MutableStateFlow(false)
        val connection = connected.asStateFlow()
        val lastError = MutableStateFlow<String?>(null)
        /** Some OEMs keep the permission but do not bind again after an APK update. */
        fun ensureConnected(context: Context) {
            val app = context.applicationContext as PayWhyApp
            if (!app.settings.value.notificationCaptureEnabled || connected.value) return
            if (context.packageName !in androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context)) return
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastRebindAt <= 60_000) return
            lastRebindAt = now
            runCatching { requestRebind(ComponentName(context, PaymentListener::class.java)) }
        }
    }
}
