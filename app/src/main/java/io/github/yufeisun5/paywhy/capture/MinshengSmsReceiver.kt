package io.github.yufeisun5.paywhy.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import io.github.yufeisun5.paywhy.PayWhyApp
import io.github.yufeisun5.paywhy.core.Minsheng
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Receives new SMS only. Does not query inbox, log SMS, or forward unrelated messages. */
class MinshengSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val app = context.applicationContext as PayWhyApp
        val settings = app.settings.value
        if (!settings.captureMinshengSms) return
        val messages = runCatching { Telephony.Sms.Intents.getMessagesFromIntent(intent) }.getOrNull() ?: return
        if (messages.isEmpty() || messages.size > 50) return
        val first = messages.first()
        if (messages.any { it.originatingAddress != first.originatingAddress }) return
        val body = messages.joinToString("") { it.messageBody.orEmpty() }
        val notice = Minsheng.smsNotice(first.originatingAddress, body, first.timestampMillis, System.currentTimeMillis()) ?: return
        val pending = goAsync()
        app.scope.launch {
            try {
                withTimeout(8_000) { CaptureStore(app.db).accept(notice, app.settings.value) }
            } catch (_: Exception) {
                error.value = "短信记录保存失败，请检查设备剩余空间。"
            } finally { pending.finish() }
        }
    }
    companion object {
        private val error = MutableStateFlow<String?>(null)
        val lastError = error.asStateFlow()
    }
}
