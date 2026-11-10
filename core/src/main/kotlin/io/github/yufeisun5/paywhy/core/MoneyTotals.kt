package io.github.yufeisun5.paywhy.core

import java.math.BigDecimal
import java.math.BigInteger

/** A narrow database projection; raw financial messages are not needed by the notification. */
data class MoneyEvidence(val amountMinor: Long?, val currency: String?, val kind: String, val possibleDuplicateOf: String?)

data class MoneyTotals(val outgoing: String, val pending: String, val returned: String, val omitted: Int) {
    val compact: String get() = listOfNotNull(outgoing.takeIf { it.isNotEmpty() },
        pending.takeIf { it.isNotEmpty() }?.let { "待确认 $it" }).joinToString(" · ").ifEmpty { "暂无已记录流出" }
    val details: String get() = listOfNotNull(
        "记录流出：${outgoing.ifEmpty { "暂无" }}",
        pending.takeIf { it.isNotEmpty() }?.let { "待确认：$it" },
        returned.takeIf { it.isNotEmpty() }?.let { "退款／冲正：$it" },
        if (omitted > 0) "$omitted 条待核对，未计入" else null,
    ).joinToString("\n")
}

object MoneyTotalBuilder {
    private val units = mapOf("CNY" to "积分", "USD" to "游戏币",
        "HKD" to "HK$", "SGD" to "S$", "EUR" to "€", "GBP" to "£", "AUD" to "A$", "JPY" to "¥")
    fun build(rows: List<MoneyEvidence>): MoneyTotals {
        val sums = mutableMapOf<String, MutableMap<String, BigInteger>>()
        var omitted = 0
        for (row in rows) {
            // Account transfers and repayments do not represent newly spent money.
            if (row.kind in setOf("transfer", "income", "inflow")) continue
            val bucket = when (row.kind) {
                "expense", "outflow" -> "outgoing"
                "authorization" -> "pending"
                "refund", "reversal" -> "returned"
                else -> null
            }
            if (bucket == null || row.amountMinor == null || row.amountMinor <= 0 || row.currency == null || row.currency !in units || row.possibleDuplicateOf != null) {
                omitted++; continue
            }
            val group = sums.getOrPut(bucket) { mutableMapOf() }
            group[row.currency] = (group[row.currency] ?: BigInteger.ZERO) + BigInteger.valueOf(row.amountMinor)
        }
        fun format(bucket: String): String = sums[bucket].orEmpty().entries.sortedWith(
            compareBy({ when (it.key) { "CNY" -> 0; "USD" -> 1; else -> 2 } }, { it.key })
        ).joinToString(" · ") { (currency, amount) -> "${BigDecimal(amount, 2).toPlainString()} ${units[currency] ?: currency}" }
        return MoneyTotals(format("outgoing"), format("pending"), format("returned"), omitted)
    }
}
