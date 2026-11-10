package io.github.yufeisun5.paywhy.background

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
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
    private var latestText = "正在读取本地运行状态"
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
            val initial = notification(latestText)
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
            combine(app.settings.flow, PaymentListener.connection, app.db.ledger().observeCount(),
                app.db.ledger().observeOutstandingMails()) { s, connected, count, pending ->
                Display(s.residentMode, statusText(s, connected, count, pending))
            }.distinctUntilChanged().catch {
                ResidentMode.failed("本地状态读取失败，已停止常驻显示；请打开应用检查")
                stopResident()
            }.collect { display ->
                if (!display.enabled || !ResidentMode.canShowNotification(this@ResidentService)) {
                    stopResident()
                } else {
                    latestText = display.text
                    try {
                        getSystemService(NotificationManager::class.java)
                            .notify(ResidentMode.NOTIFICATION_ID, notification(display.text))
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

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val silent = PendingIntent.getService(this, 1, Intent(this, ResidentService::class.java).setAction(ACTION_SILENT),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val public = NotificationCompat.Builder(this, ResidentMode.CHANNEL_ID).setSmallIcon(R.drawable.ic_stat_paywhy)
            .setContentTitle("PayWhy 后台运行").setContentText("点按打开应用").setContentIntent(open).build()
        return NotificationCompat.Builder(this, ResidentMode.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_paywhy).setContentTitle("PayWhy 正在运行")
            .setContentText(text).setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setSilent(true).setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE).setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public).addAction(0, "切换静默", silent).setDeleteIntent(silent).build()
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
    private data class Display(val enabled: Boolean, val text: String)
    companion object {
        const val ACTION_SILENT = "io.github.yufeisun5.paywhy.SWITCH_TO_SILENT"
        internal fun statusText(s: Settings, connected: Boolean, count: Int, pending: Int): String {
            val capture = if (!s.captureEnabled) "采集已关闭" else if (!s.notificationCaptureEnabled) "短信采集已开启，权限状态见应用" else if (connected) "监听已连接" else "监听未连接，请检查通知使用权"
            val mail = if (pending > 0) "待投递或处理 $pending 封" else if (s.autoSend) "每日 ${s.sendTime} 投递" else "每日邮件未开启"
            return "$capture · 已采集 $count 条\n$mail"
        }
    }
}
