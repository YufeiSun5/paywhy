package io.github.yufeisun5.paywhy.core

import java.math.BigDecimal

/** Narrow, device-observed templates. Unknown fields remain unknown; no FX conversion. */
object Minsheng {
    const val APP = "cn.com.cmbc.newmbank"
    const val SMS = "sms:cn.cmbc.creditcard"
    private val senders = setOf("95568", "10684070595568", "106939195568")
    private val currencies = mapOf("人民币" to "CNY", "美元" to "USD", "港元" to "HKD",
        "港币" to "HKD", "新加坡元" to "SGD", "欧元" to "EUR", "英镑" to "GBP",
        "澳元" to "AUD", "日元" to "JPY")
    private val currency = currencies.keys.joinToString("|")
    private const val number = "([0-9]+(?:,[0-9]{3})*(?:\\.[0-9]{1,2})?)(?![0-9.,])"
    private val debit = Regex("^您尾号[0-9]{4}的借记卡(支出|收入)$number" + "元人民币[。！]?$")
    private val credit = Regex("发生(?:境外|境内)?($currency)(预授权/消费|消费冲正|预授权|消费|退款|退货)[：:\\s]*$number(?=[。；，\\s]|$)")
    private val repayment = Regex("购汇还款成功[，,]已存入$number($currency)(?=[。；，\\s]|$)")
    private val excluded = Regex("验证码|校验码|动态密码|动态口令|最低还款|最后还款日|专项额度|额度办理|拒收请回复")

    fun candidate(n: Notice): Boolean = when (n.source) {
        APP -> n.title == "交易提醒" && Regex("^您尾号[0-9]{4}的借记卡(?:支出|收入)").containsMatchIn(n.text) && !excluded.containsMatchIn(n.text)
        SMS -> n.text.startsWith("【民生银行】您信用卡") && !excluded.containsMatchIn(n.text) &&
            (Regex("发生.*(?:消费|预授权|退款|退货)").containsMatchIn(n.text) || n.text.contains("购汇还款成功"))
        else -> false
    }

    fun smsNotice(sender: String?, body: String, sentAt: Long, capturedAt: Long): Notice? {
        val address = sender?.removePrefix("+86") ?: return null
        if (address !in senders || body.length > 8000 || sentAt <= 0 || sentAt > capturedAt + 60_000) return null
        val n = Notice(SMS, "sms:$address:$sentAt", "民生信用卡交易短信", body, sentAt, capturedAt)
        return n.takeIf(::candidate)
    }

    fun parse(n: Notice): ParsedPayment {
        if (!candidate(n)) return ParsedPayment(null, "unknown", "needs_review", null)
        val d = debit.matchEntire(n.text)
        if (n.source == APP && d != null) {
            // A debit-card outflow may be a purchase, transfer, top-up, or credit-card repayment.
            return result(d.groupValues[2], "CNY", if (d.groupValues[1] == "支出") "outflow" else "inflow", true)
        }
        val c = credit.findAll(n.text).toList()
        if (n.source == SMS && c.size == 1 && !n.truncated) {
            val m = c.single()
            val kind = when (m.groupValues[2]) {
                "预授权/消费", "预授权" -> "authorization"
                "消费冲正" -> "reversal"
                "退款", "退货" -> "refund"
                else -> "expense"
            }
            return result(m.groupValues[3], currencies[m.groupValues[1]], kind, kind == "authorization")
        }
        val r = repayment.find(n.text)
        if (n.source == SMS && r != null && !n.truncated)
            return result(r.groupValues[1], currencies[r.groupValues[2]], "transfer", false)
        return ParsedPayment(null, "unknown", "needs_review", null)
    }

    private fun result(value: String, currency: String?, kind: String, review: Boolean): ParsedPayment {
        val amount = runCatching { BigDecimal(value.replace(",", "")).movePointRight(2).longValueExact() }.getOrNull()?.takeIf { it > 0 }
        return ParsedPayment(amount, kind, if (review || amount == null || currency == null) "needs_review" else "parsed_unverified", currency)
    }
}
