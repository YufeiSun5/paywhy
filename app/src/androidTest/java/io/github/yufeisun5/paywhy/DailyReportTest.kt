package io.github.yufeisun5.paywhy

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yufeisun5.paywhy.data.Observation
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant
import java.util.UUID

/** Emulator-only regression suite. Creates local outbox batches; never invokes mail transport. */
@RunWith(AndroidJUnit4::class)
class DailyReportTest {
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as PayWhyApp
    private val original by lazy { app.settings.value }
    private val member = "test_" + UUID.randomUUID().toString().take(8)
    private fun instant(value: String) = Instant.parse(value)
    private fun millis(value: String) = instant(value).toEpochMilli()

    @Before fun setup() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) {
            "Run this suite only on an emulator"
        }
        app.qq.save("synthetic@qq.com", "abcdefghijklmnop")
        app.settings.save(original.copy(memberId = member, memberName = "合成测试", recipient = "test@example.invalid",
            mailProvider = "qq", qqAddress = "synthetic@qq.com", autoSend = true,
            startDate = "2026-11-10", zone = "Asia/Singapore", sendTime = "23:10"))
    }

    @After fun cleanup() {
        app.settings.save(original)
        app.qq.clear()
    }

    private suspend fun insert(id: String, time: String) {
        val timestamp = millis(time)
        app.db.ledger().insert(Observation("$member-$id", "$member-$id", member, "合成测试", "test_device",
            "cn.com.cmbc.newmbank", timestamp, timestamp, 100, "CNY", "outflow", "needs_review",
            "合成通知", "合成金额", false, null))
    }

    private suspend fun mails() = app.db.ledger().pending(Long.MAX_VALUE).filter { it.subject.contains("member=$member ") }
    private fun manifest(body: String) = Json.parseToJsonElement(body.lineSequence().first()).jsonObject

    @Test fun firstEveningCreatesOneBatchAndCarriesBoundaryRecordsToTomorrow() = runBlocking {
        insert("before", "2026-11-10T15:09:59Z")
        insert("boundary", "2026-11-10T15:10:00Z")
        app.reports.daily(instant("2026-11-10T15:09:59Z"))
        assertTrue(mails().isEmpty())
        app.reports.daily(instant("2026-11-10T15:10:00Z"))
        app.reports.daily(instant("2026-11-10T15:20:00Z"))
        val first = mails().single()
        assertEquals("DAILY", first.kind)
        assertEquals("2026-11-10", manifest(first.body)["report_date"]!!.jsonPrimitive.content)
        assertEquals("2026-11-10T15:10:00Z", manifest(first.body)["capture_cutoff_exclusive"]!!.jsonPrimitive.content)
        assertEquals(1, manifest(first.body)["record_count"]!!.jsonPrimitive.int)
        assertTrue(first.body.contains("$member-before"))
        assertFalse(first.body.contains("$member-boundary"))
        app.reports.daily(instant("2026-11-11T15:10:00Z"))
        assertEquals(2, mails().size)
        val next = mails().single { it.id != first.id }
        assertTrue(next.body.contains("$member-boundary"))
        assertFalse(next.body.contains("$member-before"))
    }

    @Test fun delayedExecutionAfterMidnightKeepsTheScheduledCutoff() = runBlocking {
        insert("before", "2026-11-10T15:00:00Z")
        insert("late", "2026-11-10T15:30:00Z")
        app.reports.daily(instant("2026-11-10T16:30:00Z"))
        val batch = mails().single()
        assertEquals("2026-11-10", manifest(batch.body)["report_date"]!!.jsonPrimitive.content)
        assertTrue(batch.body.contains("$member-before"))
        assertFalse(batch.body.contains("$member-late"))
        assertEquals(1, app.db.ledger().unexported(Long.MAX_VALUE, member).size)
    }

    @Test fun manualExportBeforeCatchupIsNotExportedAgain() = runBlocking {
        insert("manual", "2026-11-10T15:00:00Z")
        assertTrue(app.reports.manual(false, instant("2026-11-10T15:18:00Z")))
        app.reports.daily(instant("2026-11-10T15:20:00Z"))
        app.reports.daily(instant("2026-11-10T15:21:00Z"))
        assertEquals(2, mails().size)
        val daily = mails().single { it.kind == "DAILY" }
        assertEquals(0, manifest(daily.body)["record_count"]!!.jsonPrimitive.int)
        assertFalse(daily.body.contains("$member-manual"))
        assertEquals(0, app.db.ledger().unexported(Long.MAX_VALUE, member).size)
    }
}
