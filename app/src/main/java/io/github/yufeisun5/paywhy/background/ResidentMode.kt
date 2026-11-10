package io.github.yufeisun5.paywhy.background

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.yufeisun5.paywhy.PayWhyApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ResidentState(val running: Boolean = false, val issue: String? = null)

/** Call only from a visible activity or BOOT_COMPLETED, never from periodic workers. */
object ResidentMode {
    const val CHANNEL_ID = "paywhy_resident_v1"
    const val NOTIFICATION_ID = 1001
    private val current = MutableStateFlow(ResidentState())
    val state = current.asStateFlow()

    fun createChannel(context: Context) {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "后台运行状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "显示今日分币种金额及运行状态；锁屏隐藏金额，不显示交易原文"
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            })
    }

    fun canShowNotification(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(CHANNEL_ID)
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun apply(app: PayWhyApp) {
        if (!app.settings.value.residentMode) {
            app.stopService(Intent(app, ResidentService::class.java))
            current.value = ResidentState()
            return
        }
        createChannel(app)
        if (!canShowNotification(app)) {
            app.stopService(Intent(app, ResidentService::class.java))
            failed("通知权限或通知渠道已关闭，暂按静默模式运行")
            return
        }
        runCatching { ContextCompat.startForegroundService(app, Intent(app, ResidentService::class.java)) }
            .onFailure { failed("系统暂未允许常驻，请保持应用在前台重试或检查后台限制") }
    }

    internal fun started() { current.value = ResidentState(running = true) }
    internal fun stopped() { current.update { it.copy(running = false) } }
    internal fun failed(reason: String) { current.value = ResidentState(issue = reason) }
}
