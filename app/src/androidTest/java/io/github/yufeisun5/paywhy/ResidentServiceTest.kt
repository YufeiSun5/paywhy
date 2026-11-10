package io.github.yufeisun5.paywhy

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yufeisun5.paywhy.background.ResidentMode
import io.github.yufeisun5.paywhy.background.ResidentService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ResidentServiceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as PayWhyApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)
    private fun eventually(condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 8000
        while (!condition() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(50)
        assertTrue("Condition not reached before timeout", condition())
    }

    @Test fun residentNotificationCanSwitchToSilentWithoutDisablingCapture() {
        val original = app.settings.value
        if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.POST_NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                activity.onActivity {
                    app.settings.save(original.copy(residentMode = true, captureWechat = true))
                    ResidentMode.apply(app)
                }
                eventually { ResidentMode.state.value.running && manager.activeNotifications.any { it.id == ResidentMode.NOTIFICATION_ID } }
                val notification = manager.activeNotifications.single { it.id == ResidentMode.NOTIFICATION_ID }.notification
                assertTrue(notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0)
                assertTrue(notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
                assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility)
                val action = notification.actions.single { it.title.toString() == "切换静默" }
                action.actionIntent.send()
                eventually { !app.settings.value.residentMode && !ResidentMode.state.value.running &&
                    manager.activeNotifications.none { it.id == ResidentMode.NOTIFICATION_ID } }
                assertTrue(app.settings.value.captureWechat)
                assertEquals(original.autoSend, app.settings.value.autoSend)
                assertEquals(original.wifiOnly, app.settings.value.wifiOnly)
            } finally {
                activity.onActivity { app.settings.save(original); ResidentMode.apply(app) }
            }
        }
    }

    @Test fun silentModeRejectsUnrequestedServiceStarts() {
        val original = app.settings.value
        ActivityScenario.launch(MainActivity::class.java).use { activity ->
            try {
                activity.onActivity {
                    app.settings.save(original.copy(residentMode = false))
                    ResidentMode.apply(app)
                    // Simulate a stale start intent arriving after the user disabled resident mode.
                    it.startService(Intent(it, ResidentService::class.java))
                }
                instrumentation.waitForIdleSync()
                eventually { !ResidentMode.state.value.running }
                assertTrue(manager.activeNotifications.none { it.id == ResidentMode.NOTIFICATION_ID })
                assertFalse(app.settings.value.residentMode)
            } finally {
                activity.onActivity { app.settings.save(original); ResidentMode.apply(app) }
            }
        }
    }
}
