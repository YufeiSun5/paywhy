package io.github.yufeisun5.paywhy.core

import org.junit.Test
import kotlin.test.*

class MoneyTotalsTest {
    private fun row(amount: Long? = 100, currency: String? = "CNY", kind: String = "expense", duplicate: String? = null) =
        MoneyEvidence(amount, currency, kind, duplicate)

    @Test fun `currencies never mix and preauthorizations stay pending`() {
        val t = MoneyTotalBuilder.build(listOf(row(), row(250, "USD"), row(500, "USD", "authorization"), row(300, "HKD")))
        assertEquals("1.00 积分 · 2.50 游戏币 · 3.00 HK$", t.outgoing)
        assertEquals("5.00 游戏币", t.pending)
        assertTrue(t.compact.contains("待确认"))
    }
    @Test fun `repayments refunds and ambiguous duplicates are not newly spent money`() {
        val t = MoneyTotalBuilder.build(listOf(row(100, kind = "outflow"), row(300, kind = "transfer"),
            row(200, kind = "refund"), row(400, kind = "reversal"), row(100, duplicate = "other"), row(null), row(100, null)))
        assertEquals("1.00 积分", t.outgoing)
        assertEquals("6.00 积分", t.returned)
        assertEquals(3, t.omitted)
    }
    @Test fun `large totals cannot wrap and empty day does not claim zero spending`() {
        val t = MoneyTotalBuilder.build(listOf(row(Long.MAX_VALUE), row(Long.MAX_VALUE)))
        assertEquals("184467440737095516.14 积分", t.outgoing)
        assertEquals("暂无已记录流出", MoneyTotalBuilder.build(emptyList()).compact)
    }
}
