package io.github.yufeisun5.paywhy.background

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import android.view.View
import androidx.core.app.NotificationCompat
import io.github.yufeisun5.paywhy.MainActivity
import io.github.yufeisun5.paywhy.PayWhyApp
import io.github.yufeisun5.paywhy.R
import io.github.yufeisun5.paywhy.capture.PaymentListener
import io.github.yufeisun5.paywhy.data.Settings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged

class ResidentService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observer: Job? = null
    private var latestAmount = "今日小账本"
    private var latestDetails = ""
    private var latestCaption = "静静记录每一天"
    private var latestSummary = "正在连接采集服务"
    private val app get() = application as PayWhyApp
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SILENT) {
            // This changes only display mode; capture, daily schedule, and queued mail remain enabled.
            runCatching { app.settings.save(app.settings.value.copy(residentMode = false)) }
                .onFailure { ResidentMode.failed("模式保存失败，请在应用中切换静默模式") }
            stopResident()
            return START_NOT_STICKY
        }
        if (!app.settings.value.residentMode || !ResidentMode.canShowNotification(this)) {
            stopResident()
            return START_NOT_STICKY
        }
        ResidentMode.createChannel(this)
        try {
            val initial = notification()
            if (Build.VERSION.SDK_INT >= 34) startForeground(ResidentMode.NOTIFICATION_ID, initial,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ResidentMode.NOTIFICATION_ID, initial)
            ResidentMode.started()
        } catch (_: RuntimeException) {
            ResidentMode.failed("常驻启动受系统限制，采集和邮件继续按静默模式工作")
            stopResident()
            return START_NOT_STICKY
        }
        if (observer == null) observer = scope.launch {
            combine(app.settings.flow, PaymentListener.connection, app.db.ledger().observeOutstandingMails(), dailyMoney(app)) { s, connected, pending, totals ->
                val smsAllowed = androidx.core.content.ContextCompat.checkSelfPermission(this@ResidentService,
                    android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED
                val caption = statusText(s, connected, pending, smsAllowed)
                val details = totals.details.lineSequence().drop(1).joinToString("\n")
                Display(s.residentMode, totals.compact,
                    totals.outgoing.ifEmpty { "暂无流出记录" }, details, caption)
            }.distinctUntilChanged().catch {
                ResidentMode.failed("本地状态读取失败，已停止常驻显示；请打开应用检查")
                stopResident()
            }.collect { display ->
                if (!display.enabled || !ResidentMode.canShowNotification(this@ResidentService)) {
                    stopResident()
                } else {
                    latestSummary = display.summary
                    latestAmount = display.amount
                    latestDetails = display.details
                    latestCaption = display.caption
                    try {
                        getSystemService(NotificationManager::class.java)
                            .notify(ResidentMode.NOTIFICATION_ID, notification())
                    } catch (_: SecurityException) {
                        ResidentMode.failed("通知权限已关闭，暂按静默模式运行")
                        stopResident()
                    }
                }
            }
        }
        // Only the OS may restart this user-enabled service; no alarms or restart loops are used.
        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val silent = PendingIntent.getService(this, 1, Intent(this, ResidentService::class.java).setAction(ACTION_SILENT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(this, ResidentMode.CHANNEL_ID).setSmallIcon(R.drawable.pw_brand)
            .setContentTitle("记录服务").setContentText("在本机运行 · 点按查看").setContentIntent(open).build()
        val collapsed = RemoteViews(packageName, R.layout.notification_journal).apply {
            setTextViewText(R.id.notification_amount, latestAmount)
            setTextViewText(R.id.notification_caption, (if (latestDetails.isEmpty()) "今日采集 · " else "另有明细 · ") + latestCaption)
        }
        val expanded = RemoteViews(packageName, R.layout.notification_journal_expanded).apply {
            setTextViewText(R.id.notification_amount, latestAmount)
            setTextViewText(R.id.notification_details, latestDetails)
            setViewVisibility(R.id.notification_details, if (latestDetails.isEmpty()) View.GONE else View.VISIBLE)
            setTextViewText(R.id.notification_caption, latestCaption)
        }
        return NotificationCompat.Builder(this, ResidentMode.CHANNEL_ID)
            .setSmallIcon(R.drawable.pw_brand).setContentTitle("今日小账本")
            .setColor(0xFF786B85.toInt()).setSubText("今日小账本")
            .setContentText(latestSummary).setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(collapsed).setCustomBigContentView(expanded)
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public).addAction(R.drawable.ic_ledger, "查看记录", open)
            .addAction(0, "切换静默", silent).setDeleteIntent(silent).build()
    }

    private fun stopResident() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        ResidentMode.stopped()
    }
    override fun onDestroy() {
        scope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        ResidentMode.stopped()
        super.onDestroy()
    }
    private data class Display(val enabled: Boolean, val summary: String, val amount: String, val details: String, val caption: String)
    companion object {
        const val ACTION_SILENT = "io.github.yufeisun5.paywhy.SWITCH_TO_SILENT"
        internal fun statusText(s: Settings, connected: Boolean, pending: Int, smsGranted: Boolean = true): String {
            val capture = when {
                !s.captureEnabled -> "采集已暂停"
                s.notificationCaptureEnabled && !connected -> "采集连接待检查"
                s.captureMinshengSms && !smsGranted -> "短信权限待开启"
                else -> "悄悄记着"
            }
            val mail = if (pending > 0) "$pending 封待处理" else if (s.autoSend) "${s.sendTime} 寄出" else "自动投递未开启"
            return "$capture · $mail"
        }
    }
}
