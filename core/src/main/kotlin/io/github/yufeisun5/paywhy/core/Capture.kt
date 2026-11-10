package io.github.yufeisun5.paywhy.core

import java.math.BigDecimal
import java.security.MessageDigest

data class Notice(val source: String, val key: String, val title: String, val text: String,
                  val postedAt: Long, val capturedAt: Long, val truncated: Boolean = false)
data class ParsedPayment(val amountMinor: Long?, val kind: String, val review: String, val currency: String?)

/** Conservative hints, never an authoritative ledger. Raw candidate text survives parse failures. */
object PaymentParser {
    const val VERSION = 1
    val packages = setOf("com.tencent.mm", "com.eg.android.AlipayGphone")
    private val marketing = Regex("优惠券|立减券|最高可得|领取红包|限时优惠|待付款|支付失败|付款失败|付款请求|待支付")
    private val signal = Regex("已支付|支付成功|付款成功|成功付款|成功支付|支出|扣款|收款|退款|退回|到账|转账成功|转入|转出")
    private val trustedTitle = Regex("微信支付|微信收款|支付助手|支付宝|交易提醒|收款通知|退款通知")
    private val number = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)(?![0-9.])"
    private val patterns = listOf(
        Regex("(?:已支付|成功付款|成功支付|付款成功|支付成功|支出|扣款|收款|退款|退回|到账|转入|转出)(?:金额)?[：:\\s]*[¥￥]?\\s*$number"),
        Regex("$number\\s*元(?:的)?(?:支出|收入|退款|收款)"),
        Regex("[¥￥]\\s*$number"),
    )

    fun isCandidate(n: Notice): Boolean {
        if (n.source !in packages) return false
        val text = n.title + " " + n.text
        // A completed payment may also mention a coupon; retain it when an explicit paid amount exists.
        val explicitPaidAmount = Regex("(?:已支付|成功付款|成功支付|支付成功|付款成功|退款|收款)[：:\\s]*[¥￥]?\\s*[0-9]")
        if (marketing.containsMatchIn(text) && !explicitPaidAmount.containsMatchIn(text)) return false
        // A friend's chat message containing an amount must not become a transaction.
        if (n.source == "com.tencent.mm" && !trustedTitle.containsMatchIn(n.title)) return false
        return signal.containsMatchIn(text) || trustedTitle.containsMatchIn(n.title) &&
            Regex("支付|交易|款项").containsMatchIn(n.text)
    }

    fun parse(n: Notice): ParsedPayment {
        val text = n.title + " " + n.text
        val kind = when {
            Regex("退款|退回").containsMatchIn(text) -> "refund"
            Regex("转账|转入|转出|充值|还款|提现").containsMatchIn(text) -> "transfer"
            Regex("已支付|支付成功|付款成功|成功付款|成功支付|支出|扣款").containsMatchIn(text) -> "expense"
            Regex("收款|到账|收入").containsMatchIn(text) -> "income"
            else -> "unknown"
        }
        val amounts = patterns.flatMap { it.findAll(text).map { m -> m.groupValues[1] }.toList() }
            .mapNotNull { runCatching { BigDecimal(it.replace(",", "")).movePointRight(2).longValueExact() }.getOrNull() }
            .filter { it > 0 }.distinct()
        val currency = when {
            Regex("美元|USD|港币|HKD|日元|JPY|欧元|EUR|新加坡元|SGD|新币|澳元|AUD|英镑|GBP").containsMatchIn(text) -> null
            Regex("[¥￥]|人民币|元").containsMatchIn(text) -> "CNY"
            else -> null
        }
        val amount = amounts.singleOrNull()
        return ParsedPayment(amount, kind,
            if (amount != null && currency != null && kind != "unknown" && !n.truncated) "parsed_unverified" else "needs_review", currency)
    }

    fun fingerprint(n: Notice): String = sha256(listOf(n.source, n.key, n.postedAt.toString(), n.title, n.text)
        .joinToString("\u0000"))
}

fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
