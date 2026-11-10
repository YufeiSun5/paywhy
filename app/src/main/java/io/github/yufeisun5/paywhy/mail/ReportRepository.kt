package io.github.yufeisun5.paywhy.mail

import androidx.room.withTransaction
import kotlinx.coroutines.sync.withLock
import io.github.yufeisun5.paywhy.auth.QqCredentials
import io.github.yufeisun5.paywhy.PayWhyApp
import io.github.yufeisun5.paywhy.capture.PaymentListener
import io.github.yufeisun5.paywhy.core.*
import io.github.yufeisun5.paywhy.data.OutboxMail
import io.github.yufeisun5.paywhy.data.ReportDay
import io.github.yufeisun5.paywhy.data.Settings
import java.time.*
import java.util.UUID

class ReportRepository(private val app: PayWhyApp) {
    suspend fun daily(now: Instant = Instant.now()) = app.deliveryMutex.withLock {
        val s = app.settings.value
        if (!s.autoSend || !app.mailReady()) return@withLock
        val latest = ReportSchedule.latestDueDate(now, ZoneId.of(s.zone), LocalTime.parse(s.sendTime))
        var date = LocalDate.parse(s.startDate)
        var created = 0
        while (date <= latest && created < 31) {
            if (create("DAILY", date, ReportSchedule.cutoff(date, ZoneId.of(s.zone), LocalTime.parse(s.sendTime)), s)) created++
            date = date.plusDays(1)
        }
    }

    suspend fun manual(test: Boolean, now: Instant = Instant.now()): Boolean = app.deliveryMutex.withLock {
        val s = app.settings.value
        check(app.mailReady()) { "请先配置所选发件方式：QQ 授权码或微软登录" }
        create(if (test) "TEST" else "MANUAL", now.atZone(ZoneId.of(s.zone)).toLocalDate(), now.toEpochMilli(), s)
    }

    private suspend fun create(kind: String, date: LocalDate, cutoff: Long, s: Settings): Boolean {
        s.validate()
        require(s.recipient.isNotBlank()) { "请先填写收件邮箱" }
        val identity = if (s.mailProvider == "qq") QqCredentials.identity(s.qqAddress) else app.auth.value.identity
        return app.db.withTransaction {
            val dao = app.db.ledger()
            val dayKey = "${s.memberId}|$date"
            if (kind == "DAILY" && dao.hasDay(dayKey) != 0) return@withTransaction false
            val rows = if (kind == "TEST") emptyList() else dao.unexported(cutoff, s.memberId)
            if (kind == "MANUAL" && rows.isEmpty()) return@withTransaction false
            val id = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()
            val context = ReportContext(kind, id, s.memberId, s.memberName, s.deviceId, date, s.zone, now, cutoff,
                s.captureEnabled, PaymentListener.connection.value, s.captureMinshengSms,
                androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECEIVE_SMS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
            val parts = ReportBuilder.build(context, rows.map { it.record() })
            dao.insertMails(parts.mapIndexed { i, part ->
                OutboxMail("$id-${i + 1}", id, "$id-${i + 1}", kind, s.recipient, if (s.mailProvider == "qq") "" else s.clientId, identity,
                    part.subject, part.filename, part.text, now, provider = s.mailProvider,
                    senderAddress = if (s.mailProvider == "qq") QqCredentials.normalize(s.qqAddress) else "")
            })
            if (kind != "TEST") dao.assign(cutoff, s.memberId, id)
            if (kind == "DAILY") dao.markDay(ReportDay(dayKey, id))
            true
        }
    }
}
