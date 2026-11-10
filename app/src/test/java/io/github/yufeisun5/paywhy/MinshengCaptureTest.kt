package io.github.yufeisun5.paywhy

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.yufeisun5.paywhy.capture.CaptureStore
import io.github.yufeisun5.paywhy.core.*
import io.github.yufeisun5.paywhy.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MinshengCaptureTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: PayWhyDatabase
    @Before fun setup() { db = Room.inMemoryDatabaseBuilder(context, PayWhyDatabase::class.java).build() }
    @After fun close() { db.close() }
    @Test fun `new sources default off and persist independently`() {
        val store = SettingsStore(context)
        assertFalse(store.value.captureMinsheng)
        assertFalse(store.value.captureMinshengSms)
        val s = store.value.copy(captureMinsheng = true, captureMinshengSms = true)
        store.save(s)
        assertEquals(s, SettingsStore(context).value)
        assertTrue(s.captureEnabled)
        assertTrue(s.copy(captureMinsheng = false).captureEnabled)
        assertFalse(s.copy(captureMinsheng = false).notificationCaptureEnabled)
    }
    @Test fun `bank sources pass opt in storage dedup and plain text export without FX conversion`() = runBlocking {
        val off = SettingsStore(context).value
        val s = off.copy(captureMinsheng = true, captureMinshengSms = true)
        val capture = CaptureStore(db)
        val debit = Notice(Minsheng.APP, "notification", "交易提醒", "您尾号1234的借记卡支出2.35元人民币", 1_000, 2_000)
        val sms = Minsheng.smsNotice("95568", "【民生银行】您信用卡1234于02日13:45发生境外美元预授权/消费27.80。入账币种与金额以账单为准。", 1_000, 2_000)!!
        assertFalse(capture.accept(debit, off))
        assertFalse(capture.accept(sms, off))
        assertTrue(capture.accept(debit, s))
        assertTrue(capture.accept(sms, s))
        assertFalse(capture.accept(sms.copy(capturedAt = 2_001), s))
        val rows = db.ledger().unexported(3_000, s.memberId)
        assertEquals(2, rows.size)
        val report = ReportBuilder.build(ReportContext("MANUAL", "batch", s.memberId, s.memberName, s.deviceId,
            LocalDate.parse("2026-11-10"), "Asia/Singapore", 3_000, 3_000, true, true, true, true), rows.map { it.record() }).single()
        val records = report.text.trim().lines().drop(1).map { Json.parseToJsonElement(it).jsonObject }
        val dollar = records.single { it["currency"]!!.jsonPrimitive.content == "USD" }
        assertEquals("27.80", dollar["amount"]!!.jsonPrimitive.content)
        assertEquals("authorization", dollar["kind_hint"]!!.jsonPrimitive.content)
        assertEquals("needs_review", dollar["review_status"]!!.jsonPrimitive.content)
        assertEquals(s.memberId, dollar["member_id"]!!.jsonPrimitive.content)
        assertEquals(s.deviceId, dollar["device_id"]!!.jsonPrimitive.content)
    }
}
