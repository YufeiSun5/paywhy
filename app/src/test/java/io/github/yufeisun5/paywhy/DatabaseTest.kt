package io.github.yufeisun5.paywhy

import android.app.Application
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import io.github.yufeisun5.paywhy.data.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class DatabaseTest {
    private lateinit var db: PayWhyDatabase
    private lateinit var dao: LedgerDao
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), PayWhyDatabase::class.java).build()
        dao = db.ledger()
    }
    @After fun close() { db.close() }
    private fun row(id: String, time: Long = 1000, fingerprint: String = id) = Observation(id, fingerprint,
        "member1", "合成测试", "device1", "com.tencent.mm", time, time, 1230, "CNY", "expense",
        "parsed_unverified", "微信支付", "已支付¥12.30", false, null)
    private fun mail(id: String = "batch-1") = OutboxMail(id, "batch", id, "TEST", "test@example.invalid",
        "client", "identity", "[PayWhy][TEST]", "test.txt", "{}\n", 1000)

    @Test fun `fingerprint dedup survives subsequent reads`() = runBlocking {
        dao.insert(row("first"))
        dao.insert(row("second", fingerprint = "first"))
        dao.insert(row("third", time = 2000))
        assertEquals(2, dao.count())
        assertEquals(listOf("first", "third"), dao.unexported(3000, "member1").map { it.id })
    }
    @Test fun `export cutoff and member boundary are respected`() = runBlocking {
        dao.insert(row("before", 999))
        dao.insert(row("boundary", 1000))
        dao.insert(row("other", 500).copy(memberId = "member2"))
        dao.assign(1000, "member1", "batch")
        assertEquals(listOf("boundary"), dao.unexported(2000, "member1").map { it.id })
        assertEquals(1, dao.unexported(2000, "member2").size)
    }
    @Test fun `snapshot transaction rolls back record assignment on failure`() = runBlocking {
        dao.insert(row("first"))
        runCatching { db.withTransaction {
            dao.assign(2000, "member1", "batch")
            dao.insertMails(listOf(mail()))
            error("simulate full disk before commit")
        } }
        assertEquals(1, dao.unexported(2000, "member1").size)
        assertEquals(0, dao.pending(3000).size)
    }
    @Test fun `sending claims exclude duplicate dispatch and recover as uncertain`() = runBlocking {
        dao.insertMails(listOf(mail()))
        assertEquals(1, dao.claim("batch-1", 2000))
        assertEquals(0, dao.claim("batch-1", 2001))
        dao.recoverInterrupted()
        assertEquals(0, dao.pending(3000).size)
        dao.retry("batch-1")
        assertEquals(1, dao.pending(3000).size)
        dao.result("batch-1", "accepted", acceptedAt = 4000)
        dao.retry("batch-1")
        assertEquals(0, dao.pending(5000).size)
    }
    @Test fun `throttle delay survives process restart`() = runBlocking {
        dao.insertMails(listOf(mail()))
        dao.result("batch-1", "retry", nextAttemptAt = 10_000)
        assertEquals(0, dao.pending(9_999).size)
        assertEquals(1, dao.pending(10_000).size)
    }
}
