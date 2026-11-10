package io.github.yufeisun5.paywhy.capture

import androidx.room.withTransaction
import io.github.yufeisun5.paywhy.core.Notice
import io.github.yufeisun5.paywhy.core.PaymentParser
import io.github.yufeisun5.paywhy.data.*
import java.util.UUID

class CaptureStore(private val db: PayWhyDatabase) {
    suspend fun accept(notice: Notice, settings: Settings): Boolean {
        val enabled = when (notice.source) {
            "com.tencent.mm" -> settings.captureWechat
            "com.eg.android.AlipayGphone" -> settings.captureAlipay
            else -> false
        }
        if (!enabled || !PaymentParser.isCandidate(notice)) return false
        val parsed = PaymentParser.parse(notice)
        return db.withTransaction {
            val dao = db.ledger()
            val amount = parsed.amountMinor
            val currency = parsed.currency
            val duplicate = if (amount != null && currency != null)
                dao.possibleDuplicate(amount, currency, parsed.kind, settings.memberId, notice.capturedAt - 120_000, notice.capturedAt)
            else null
            dao.insert(Observation(UUID.randomUUID().toString(), PaymentParser.fingerprint(notice),
                settings.memberId, settings.memberName, settings.deviceId, notice.source,
                notice.postedAt, notice.capturedAt, amount, currency, parsed.kind, parsed.review,
                notice.title, notice.text, notice.truncated, duplicate)) != -1L
        }
    }
}
