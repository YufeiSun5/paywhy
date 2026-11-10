package io.github.yufeisun5.paywhy.core

import kotlinx.serialization.json.*
import org.junit.Test
import java.time.*
import kotlin.test.*

class CaptureTest {
    private fun notice(text: String, title: String = "微信支付", source: String = "com.tencent.mm") =
        Notice(source, "notification-1", title, text, 1_000, 2_000)

    @Test fun `expense includes credit financed purchases`() {
        for (text in listOf("已支付¥12.30", "花呗支付成功￥12.30")) {
            val n = notice(text)
            assertTrue(PaymentParser.isCandidate(n))
            assertEquals(1230L, PaymentParser.parse(n).amountMinor)
            assertEquals("expense", PaymentParser.parse(n).kind)
        }
    }
    @Test fun `refund is not silently classified as ordinary income`() {
        assertEquals("refund", PaymentParser.parse(notice("退款到账￥25.00")).kind)
    }
    @Test fun `alipay alternate template and decimal arithmetic`() {
        val n = notice("你有一笔1,234.56元的支出", "支付宝", "com.eg.android.AlipayGphone")
        assertTrue(PaymentParser.isCandidate(n))
        assertEquals(123456L, PaymentParser.parse(n).amountMinor)
    }
    @Test fun `chat marketing and other packages are ignored`() {
        assertFalse(PaymentParser.isCandidate(notice("已支付¥88.00", "张三")))
        assertFalse(PaymentParser.isCandidate(notice("支付成功享优惠券￥88.00")))
        assertTrue(PaymentParser.isCandidate(notice("已支付¥12.30，已使用优惠券")))
        assertFalse(PaymentParser.isCandidate(notice("已支付¥88.00", source = "org.other.app")))
    }
    @Test fun `ambiguous and truncated amounts survive as review candidates`() {
        val n = notice("已支付¥20.00，原价¥30.00")
        assertTrue(PaymentParser.isCandidate(n))
        assertNull(PaymentParser.parse(n).amountMinor)
        assertEquals("needs_review", PaymentParser.parse(n).review)
        assertEquals("needs_review", PaymentParser.parse(notice("已支付¥20.00").copy(truncated = true)).review)
        assertNull(PaymentParser.parse(notice("支付成功，金额已隐藏")).amountMinor)
    }
    @Test fun `reposted notice is deduplicated but equal new purchases are not`() {
        val n = notice("已支付¥20.00")
        assertEquals(PaymentParser.fingerprint(n), PaymentParser.fingerprint(n.copy(capturedAt = 5_000)))
        assertNotEquals(PaymentParser.fingerprint(n), PaymentParser.fingerprint(n.copy(postedAt = 4_000)))
        assertNotEquals(PaymentParser.fingerprint(n), PaymentParser.fingerprint(n.copy(key = "new")))
    }
    @Test fun `malformed amounts and card tails do not become money`() {
        assertNull(PaymentParser.parse(notice("支付成功，尾号1234")).amountMinor)
        assertNull(PaymentParser.parse(notice("已支付¥12.345")).amountMinor)
        assertNull(PaymentParser.parse(notice("已支付¥9999999999999999999999")).amountMinor)
    }
    @Test fun `foreign and unspecified currency are never assumed CNY`() {
        assertNull(PaymentParser.parse(notice("已支付12.00美元")).currency)
        assertNull(PaymentParser.parse(notice("已支付12.00")).currency)
        assertEquals("CNY", PaymentParser.parse(notice("已支付12.00元")).currency)
    }
}

class ReportTest {
    private val context = ReportContext("DAILY", "batch-one", "member_one", "妈妈", "device-one",
        LocalDate.parse("2026-11-10"), "Asia/Singapore", 1_000, 2_000, true, true)
    private fun record(id: String = "record-one", text: String = "已支付¥12.30\n商户：\"便利店\"") = Record(
        record_id = id, member_id = "member_one", member_name = "妈妈", device_id = "device-one",
        source_package = "com.tencent.mm", notification_at = "2026-11-10T00:00:00Z", captured_at = "2026-11-10T00:00:01Z",
        amount = "12.30", kind_hint = "expense", review_status = "parsed_unverified", raw_title = "微信支付",
        raw_text = text, raw_truncated = false, possible_duplicate_of = null)

    @Test fun `plain text has independent parseable lines and stable identity`() {
        val part = ReportBuilder.build(context, listOf(record())).single()
        assertTrue(part.subject.startsWith("[PayWhy][DAILY][v1][妈妈] 2026-11-10"))
        assertTrue(part.subject.contains("member=member_one"))
        assertTrue(part.filename.endsWith(".txt"))
        val lines = part.text.trimEnd().lines().map { Json.parseToJsonElement(it).jsonObject }
        assertEquals(2, lines.size)
        assertEquals("unknown_notifications_are_not_a_complete_statement", lines[0]["coverage"]!!.jsonPrimitive.content)
        assertEquals(record().raw_text, lines[1]["raw_text"]!!.jsonPrimitive.content)
        assertEquals("record-one", lines[1]["record_id"]!!.jsonPrimitive.content)
        assertEquals(part, ReportBuilder.build(context, listOf(record())).single())
    }
    @Test fun `empty report reports zero observations not zero spending`() {
        val part = ReportBuilder.build(context, emptyList()).single()
        val manifest = Json.parseToJsonElement(part.text.trim()).jsonObject
        assertEquals(0, manifest["record_count"]!!.jsonPrimitive.int)
        assertFalse(manifest.containsKey("spending_total"))
    }
    @Test fun `large reports are split without losing or duplicating records`() {
        val records = (1..100).map { record("r$it", "中".repeat(8000)) }
        val parts = ReportBuilder.build(context, records)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.text.toByteArray(Charsets.UTF_8).size <= ReportBuilder.MAX_PART_BYTES })
        val ids = parts.flatMap { it.text.trimEnd().lines().drop(1) }
            .map { Json.parseToJsonElement(it).jsonObject["record_id"]!!.jsonPrimitive.content }
        assertEquals(records.map { it.record_id }, ids)
        assertEquals(parts.size, Json.parseToJsonElement(parts[0].text.lineSequence().first()).jsonObject["parts"]!!.jsonPrimitive.int)
    }
    @Test fun `test reports are distinguishable and subject cannot inject headers`() {
        val part = ReportBuilder.build(context.copy(kind = "TEST", memberName = "妈妈\r\nBcc:xxx"), emptyList()).single()
        assertTrue(part.subject.contains("[TEST]"))
        assertFalse(part.subject.contains('\n'))
        assertFalse(part.subject.contains('\r'))
    }
    @Test fun `money output uses decimal string`() { assertEquals("0.01", amountText(1)); assertNull(amountText(null)) }
}

class ScheduleTest {
    private val zone = ZoneId.of("Asia/Singapore")
    @Test fun `first day becomes due at the configured evening time`() {
        val time = LocalTime.of(23, 10)
        assertEquals(LocalDate.parse("2026-11-09"), ReportSchedule.latestDueDate(Instant.parse("2026-11-10T15:09:59Z"), zone, time))
        assertEquals(LocalDate.parse("2026-11-10"), ReportSchedule.latestDueDate(Instant.parse("2026-11-10T15:10:00Z"), zone, time))
        assertEquals(LocalDate.parse("2026-11-10"), ReportSchedule.latestDueDate(Instant.parse("2026-11-10T16:30:00Z"), zone, time))
        assertEquals(Instant.parse("2026-11-10T15:10:00Z").toEpochMilli(),
            ReportSchedule.cutoff(LocalDate.parse("2026-11-10"), zone, time))
    }
    @Test fun `next trigger is future and respects timezone`() {
        val now = Instant.parse("2026-11-10T00:00:00Z")
        assertEquals(now.plusSeconds(86400), ReportSchedule.nextWake(now, zone, LocalTime.of(8, 0)))
    }
    @Test fun `calendar boundaries respect DST short days`() {
        val zone = ZoneId.of("America/New_York")
        val date = LocalDate.parse("2026-03-08")
        val time = LocalTime.of(23, 10)
        val start = ReportSchedule.cutoff(date.minusDays(1), zone, time)
        assertEquals(23 * 60 * 60 * 1000L, ReportSchedule.cutoff(date, zone, time) - start)
    }
}
