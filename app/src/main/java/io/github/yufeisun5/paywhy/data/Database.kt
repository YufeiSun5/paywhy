package io.github.yufeisun5.paywhy.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import io.github.yufeisun5.paywhy.core.Record
import io.github.yufeisun5.paywhy.core.amountText
import java.time.Instant

@Entity(tableName = "observations", indices = [Index(value = ["fingerprint"], unique = true), Index("batchId")])
data class Observation(
    @PrimaryKey val id: String,
    val fingerprint: String,
    val memberId: String,
    val memberName: String,
    val deviceId: String,
    val source: String,
    val postedAt: Long,
    val capturedAt: Long,
    val amountMinor: Long?,
    val currency: String?,
    val kind: String,
    val review: String,
    val title: String,
    val text: String,
    val truncated: Boolean,
    val possibleDuplicateOf: String?,
    val batchId: String? = null,
) {
    fun record() = Record(record_id = id, member_id = memberId, member_name = memberName, device_id = deviceId,
        source_package = source, notification_at = Instant.ofEpochMilli(postedAt).toString(),
        captured_at = Instant.ofEpochMilli(capturedAt).toString(), amount = amountText(amountMinor), currency = currency,
        kind_hint = kind, review_status = review, raw_title = title, raw_text = text, raw_truncated = truncated,
        possible_duplicate_of = possibleDuplicateOf)
}

@Entity(tableName = "outbox", indices = [Index(value = ["dedupeKey"], unique = true)])
data class OutboxMail(
    @PrimaryKey val id: String,
    val batchId: String,
    val dedupeKey: String,
    val kind: String,
    val recipient: String,
    val clientId: String,
    val authGeneration: String,
    val subject: String,
    val filename: String,
    val body: String,
    val createdAt: Long,
    val status: String = "pending",
    val attempts: Int = 0,
    val lastAttemptAt: Long? = null,
    val acceptedAt: Long? = null,
    val error: String? = null,
    val nextAttemptAt: Long = 0,
)

@Entity(tableName = "report_days")
data class ReportDay(@PrimaryKey val key: String, val batchId: String)

@Dao
interface LedgerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(row: Observation): Long
    @Query("SELECT * FROM observations ORDER BY capturedAt DESC LIMIT 100") fun observeRecent(): Flow<List<Observation>>
    @Query("SELECT COUNT(*) FROM observations") fun observeCount(): Flow<Int>
    @Query("SELECT COUNT(*) FROM observations WHERE batchId IS NULL") fun observeUnexported(): Flow<Int>
    @Query("SELECT * FROM observations WHERE batchId IS NULL AND capturedAt < :cutoff AND memberId = :member ORDER BY capturedAt, id")
    suspend fun unexported(cutoff: Long, member: String): List<Observation>
    @Query("UPDATE observations SET batchId = :batch WHERE batchId IS NULL AND capturedAt < :cutoff AND memberId = :member")
    suspend fun assign(cutoff: Long, member: String, batch: String)
    @Query("SELECT id FROM observations WHERE amountMinor = :amount AND currency = :currency AND kind = :kind AND memberId = :member AND capturedAt BETWEEN :start AND :end ORDER BY capturedAt DESC LIMIT 1")
    suspend fun possibleDuplicate(amount: Long, currency: String, kind: String, member: String, start: Long, end: Long): String?
    @Query("SELECT COUNT(*) FROM observations") suspend fun count(): Int
    @Insert suspend fun insertMails(rows: List<OutboxMail>)
    @Insert suspend fun markDay(row: ReportDay)
    @Query("SELECT COUNT(*) FROM report_days WHERE `key` = :key") suspend fun hasDay(key: String): Int
    @Query("SELECT * FROM outbox ORDER BY createdAt DESC, id LIMIT 60") fun observeMails(): Flow<List<OutboxMail>>
    @Query("SELECT * FROM outbox WHERE status IN ('pending', 'retry') AND nextAttemptAt <= :now ORDER BY createdAt, id LIMIT 20")
    suspend fun pending(now: Long): List<OutboxMail>
    @Query("SELECT COUNT(*) FROM outbox WHERE status IN ('pending', 'retry')") suspend fun retryableCount(): Int
    @Query("UPDATE outbox SET status = 'sending', attempts = attempts + 1, lastAttemptAt = :now, error = NULL WHERE id = :id AND status IN ('pending', 'retry')")
    suspend fun claim(id: String, now: Long): Int
    @Query("UPDATE outbox SET status = :status, error = :error, acceptedAt = :acceptedAt, nextAttemptAt = :nextAttemptAt WHERE id = :id")
    suspend fun result(id: String, status: String, error: String? = null, acceptedAt: Long? = null, nextAttemptAt: Long = 0)
    @Query("UPDATE outbox SET status = 'uncertain', error = '发送过程被中断，可能已投递；请先检查邮箱，再决定重试。' WHERE status = 'sending'")
    suspend fun recoverInterrupted()
    @Query("UPDATE outbox SET status = 'pending', error = NULL, nextAttemptAt = 0 WHERE id = :id AND status IN ('failed', 'uncertain', 'retry')")
    suspend fun retry(id: String)
}

@Database(entities = [Observation::class, OutboxMail::class, ReportDay::class], version = 1, exportSchema = true)
abstract class PayWhyDatabase : RoomDatabase() { abstract fun ledger(): LedgerDao }
