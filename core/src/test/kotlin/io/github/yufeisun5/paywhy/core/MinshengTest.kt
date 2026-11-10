package io.github.yufeisun5.paywhy.core

import org.junit.Test
import kotlin.test.*

class MinshengTest {
    private fun sms(body: String) = Minsheng.smsNotice("10684070595568", "【民生银行】您信用卡1234$body", 1_000, 2_000)!!
    @Test fun `foreign authorization retains original amount currency and uncertainty`() {
        for ((name, iso) in mapOf("美元" to "USD", "港元" to "HKD", "新加坡元" to "SGD")) {
            val n = sms("于02日13:45发生境外${name}预授权/消费27.80。入账币种与金额以账单为准。")
            assertTrue(PaymentParser.isCandidate(n))
            assertEquals(ParsedPayment(2780, "authorization", "needs_review", iso), PaymentParser.parse(n))
        }
    }
    @Test fun `explicit consumption and reversal are distinct`() {
        assertEquals(ParsedPayment(123456, "expense", "parsed_unverified", "USD"), PaymentParser.parse(sms("于02日13:45发生境外美元消费1,234.56。")))
        assertEquals(ParsedPayment(2780, "reversal", "parsed_unverified", "USD"), PaymentParser.parse(sms("于02日13:45发生境外美元消费冲正27.80。入账币种与金额以账单为准。")))
        assertEquals("refund", PaymentParser.parse(sms("于02日13:45发生境外美元退款27.80。")).kind)
    }
    @Test fun `foreign currency repayment is transfer not expense`() {
        assertEquals(ParsedPayment(4567, "transfer", "parsed_unverified", "USD"), PaymentParser.parse(sms("于02月03日13:45购汇还款成功，已存入45.67美元。下载全民生活APP快速查明细。")))
    }
    @Test fun `debit amount excludes card tail and preserves unknown purpose`() {
        val n = Notice(Minsheng.APP, "key", "交易提醒", "您尾号1234的借记卡支出2.35元人民币", 1_000, 2_000)
        assertTrue(PaymentParser.isCandidate(n))
        assertEquals(ParsedPayment(235, "outflow", "needs_review", "CNY"), PaymentParser.parse(n))
        assertEquals("inflow", PaymentParser.parse(n.copy(text = n.text.replace("支出", "收入"))).kind)
        assertFalse(PaymentParser.isCandidate(n.copy(title = "优惠活动")))
        assertFalse(PaymentParser.isCandidate(n.copy(source = "untrusted.package")))
    }
    @Test fun `SMS sender signature and OTP filters are enforced before storage`() {
        val body = sms("于02日13:45发生境外美元消费27.80。").text
        assertNull(Minsheng.smsNotice("13800000000", body, 1_000, 2_000))
        assertNotNull(Minsheng.smsNotice("+86106939195568", body, 1_000, 2_000))
        assertNull(Minsheng.smsNotice("95568", body.replace("民生银行", "其他银行"), 1_000, 2_000))
        assertNull(Minsheng.smsNotice("95568", body + "验证码123456", 1_000, 2_000))
        assertNull(Minsheng.smsNotice("95568", "【民生银行】您信用卡本月账单人民币应还100元、最低还款10元，美元应还5美元。", 1_000, 2_000))
        assertNull(Minsheng.smsNotice("95568", "【民生银行】尊享专项额度办理分期，消费轻松。", 1_000, 2_000))
    }
    @Test fun `unsupported or malformed currency and amount never become a confident expense`() {
        for (body in listOf("境外美元消费2.345。", "境外美元消费9999999999999999999999。", "境外加元消费2.35。")) {
            val p = PaymentParser.parse(sms("于02日13:45发生$body"))
            assertNull(p.amountMinor)
            assertEquals("needs_review", p.review)
        }
        val n = sms("于02日13:45发生境外美元消费2.35。")
        assertEquals("needs_review", PaymentParser.parse(n.copy(truncated = true)).review)
        assertNull(Minsheng.smsNotice("95568", n.text, -1, 2_000))
    }
    @Test fun `SMS redelivery deduplicates but same amount later survives`() {
        val n = sms("于02日13:45发生境外美元消费2.35。")
        assertEquals(PaymentParser.fingerprint(n), PaymentParser.fingerprint(n.copy(capturedAt = 3_000)))
        val later = Minsheng.smsNotice("10684070595568", n.text, 1_001, 2_000)!!
        assertNotEquals(PaymentParser.fingerprint(n), PaymentParser.fingerprint(later))
    }
    @Test fun `multiple transaction amounts are retained as uncertain raw evidence`() {
        val n = sms("于02日13:45发生境外美元消费2.35。另发生境外美元消费3.45。")
        assertNull(PaymentParser.parse(n).amountMinor)
    }
}
