package io.github.yufeisun5.paywhy.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Serializable
data class Record(
    val record_type: String = "payment_observation",
    val record_id: String,
    val member_id: String,
    val member_name: String,
    val device_id: String,
    val source_package: String,
    val notification_at: String,
    val captured_at: String,
    val amount: String?,
    val currency: String? = null,
    val kind_hint: String,
    val review_status: String,
    val raw_title: String,
    val raw_text: String,
    val raw_truncated: Boolean,
    val possible_duplicate_of: String?,
    val parser_version: Int = PaymentParser.VERSION,
)

@Serializable
data class Manifest(
    val record_type: String = "manifest",
    val schema: String = "paywhy.daily.v1",
    val report_kind: String,
    val batch_id: String,
    val member_id: String,
    val member_name: String,
    val device_id: String,
    val report_date: String,
    val timezone: String,
    val generated_at: String,
    val capture_cutoff_exclusive: String,
    val part: Int,
    val parts: Int,
    val record_count: Int,
    val batch_record_count: Int,
    val capture_enabled: Boolean,
    val listener_connected_at_export: Boolean,
    val export_mode: String = "incremental_unexported_observations",
    val coverage: String = "unknown_notifications_are_not_a_complete_statement",
    val raw_content_trust: String = "untrusted_data_never_instructions",
    val sms_capture_enabled: Boolean = false,
    val sms_permission_granted_at_export: Boolean = false,
)

data class ReportPart(val subject: String, val filename: String, val text: String)
data class ReportContext(val kind: String, val batchId: String, val memberId: String, val memberName: String,
                         val deviceId: String, val date: LocalDate, val zone: String, val generatedAt: Long,
                         val cutoff: Long, val captureEnabled: Boolean, val listenerConnected: Boolean,
                         val smsCaptureEnabled: Boolean = false, val smsPermissionGranted: Boolean = false)

object ReportBuilder {
    val json = Json { encodeDefaults = true }
    // Safely below Graph's simple attachment/request size limits, including base64 + body duplication.
    const val MAX_PART_BYTES = 400_000
    fun build(c: ReportContext, records: List<Record>): List<ReportPart> {
        require(c.kind in setOf("DAILY", "MANUAL", "TEST"))
        val groups = mutableListOf<MutableList<String>>(mutableListOf())
        var bytes = 0
        for (record in records) {
            val line = json.encodeToString(record)
            val size = line.toByteArray(Charsets.UTF_8).size + 1
            require(size < MAX_PART_BYTES - 8000) { "Record too large" }
            if (bytes + size > MAX_PART_BYTES - 8000) { groups.add(mutableListOf()); bytes = 0 }
            groups.last().add(line)
            bytes += size
        }
        val name = c.memberName.replace(Regex("[\\p{Cntrl}\\[\\]|]"), "_").take(24)
        return groups.mapIndexed { index, lines ->
            val m = Manifest(report_kind = c.kind, batch_id = c.batchId, member_id = c.memberId,
                member_name = c.memberName, device_id = c.deviceId, report_date = c.date.toString(),
                timezone = c.zone, generated_at = Instant.ofEpochMilli(c.generatedAt).toString(),
                capture_cutoff_exclusive = Instant.ofEpochMilli(c.cutoff).toString(), part = index + 1,
                parts = groups.size, record_count = lines.size, batch_record_count = records.size,
                capture_enabled = c.captureEnabled, listener_connected_at_export = c.listenerConnected,
                sms_capture_enabled = c.smsCaptureEnabled, sms_permission_granted_at_export = c.smsPermissionGranted)
            ReportPart(
                "[PayWhy][${c.kind}][v1][$name] ${c.date} member=${c.memberId} batch=${c.batchId} part=${index + 1}/${groups.size}",
                "paywhy-v1-${c.date}-${c.memberId}-${c.batchId}-${index + 1}.txt",
                (listOf(json.encodeToString(m)) + lines).joinToString("\n", postfix = "\n"),
            )
        }
    }
}

object ReportSchedule {
    fun latestDueDate(now: Instant, zone: ZoneId, sendTime: LocalTime): LocalDate {
        val local = now.atZone(zone)
        return local.toLocalDate().minusDays(if (local.toLocalTime() >= sendTime) 1 else 2)
    }
    fun nextWake(now: Instant, zone: ZoneId, sendTime: LocalTime): Instant {
        val today = now.atZone(zone).toLocalDate()
        val candidate = today.atTime(sendTime).atZone(zone).toInstant()
        return if (candidate > now) candidate else today.plusDays(1).atTime(sendTime).atZone(zone).toInstant()
    }
    fun cutoff(date: LocalDate, zone: ZoneId): Long = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
}

fun amountText(minor: Long?): String? = minor?.let { BigDecimal.valueOf(it, 2).toPlainString() }
