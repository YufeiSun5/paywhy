package io.github.yufeisun5.paywhy

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.github.yufeisun5.paywhy.background.ResidentMode
import io.github.yufeisun5.paywhy.background.ResidentService
import io.github.yufeisun5.paywhy.data.SettingsStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class ResidentModeTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun `upgrade keeps existing settings and defaults to silent`() {
        context.getSharedPreferences("settings", 0).edit().putString("member", "existing_member")
            .putBoolean("wechat", true).commit()
        val settings = SettingsStore(context).value
        assertFalse(settings.residentMode)
        assertEquals("existing_member", settings.memberId)
        assertTrue(settings.captureWechat)
    }
    @Test fun `mode choice survives recreation without changing collection or delivery options`() {
        val store = SettingsStore(context)
        val original = store.value.copy(captureWechat = true, captureAlipay = true, wifiOnly = true)
        store.save(original.copy(residentMode = true))
        val recreated = SettingsStore(context)
        assertEquals(original.copy(residentMode = true), recreated.value)
        recreated.save(recreated.value.copy(residentMode = false))
        assertEquals(original, SettingsStore(context).value)
    }
    @Test fun `channel is low importance silent and has no badge`() {
        ResidentMode.createChannel(context)
        val channel = context.getSystemService(NotificationManager::class.java).getNotificationChannel(ResidentMode.CHANNEL_ID)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound)
        assertFalse(channel.shouldVibrate())
        assertFalse(channel.canShowBadge())
    }
    @Test fun `disabled channel cannot be treated as visible resident mode`() {
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(ResidentMode.CHANNEL_ID, "Blocked", NotificationManager.IMPORTANCE_NONE))
        assertFalse(ResidentMode.canShowNotification(context))
    }
    @Test fun `notification text only exposes operational status`() {
        val s = SettingsStore(context).value.copy(memberName = "PRIVATE_NAME", recipient = "private@example.invalid",
            captureWechat = true)
        val text = ResidentService.statusText(s, false, 12, 2)
        assertTrue(text.contains("监听未连接"))
        assertTrue(text.contains("已采集 12 条"))
        assertTrue(text.contains("2 封"))
        assertFalse(text.contains("PRIVATE_NAME"))
        assertFalse(text.contains("private@example.invalid"))
    }
}
