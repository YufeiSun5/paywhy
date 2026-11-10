package io.github.yufeisun5.paywhy

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.yufeisun5.paywhy.auth.SecretStore
import io.github.yufeisun5.paywhy.capture.CaptureStore
import io.github.yufeisun5.paywhy.core.*
import io.github.yufeisun5.paywhy.data.*
import io.github.yufeisun5.paywhy.mail.GraphMail
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.Base64
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private lateinit var db: PayWhyDatabase
    private val settings = Settings("合成测试成员", "test_member", "test_device", "test@example.invalid", "",
        true, true, false, false, "08:00", "Asia/Singapore", "2026-11-10")
    @Before fun setup() {
        db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, PayWhyDatabase::class.java).build()
    }
    @After fun close() { db.close() }

    @Test fun syntheticNotificationToDurableOutboxToPlainTextPayload() = runBlocking {
        val store = CaptureStore(db)
        val n = Notice("com.tencent.mm", "key", "微信支付", "已支付¥12.30", 1000, 2000)
        assertTrue(store.accept(n, settings))
        assertFalse(store.accept(n.copy(capturedAt = 3000), settings))
        assertTrue(store.accept(n.copy(key = "another", postedAt = 4000, capturedAt = 5000), settings))
        assertFalse(store.accept(n.copy(key = "chat", title = "朋友"), settings))
        assertFalse(store.accept(n.copy(key = "disabled"), settings.copy(captureWechat = false)))
        val records = db.ledger().unexported(6000, settings.memberId)
        assertEquals(2, records.size)
        assertEquals(records[0].id, records[1].possibleDuplicateOf)
        val context = ReportContext("DAILY", "test-batch", settings.memberId, settings.memberName,
            settings.deviceId, LocalDate.parse("2026-11-10"), settings.zone, 7000, 6000, true, true)
        val part = ReportBuilder.build(context, records.map { it.record() }).single()
        val mail = OutboxMail("mail", "test-batch", "mail", "DAILY", settings.recipient, "synthetic-client",
            "synthetic-identity", part.subject, part.filename, part.text, 7000)
        db.withTransaction { db.ledger().insertMails(listOf(mail)); db.ledger().assign(6000, settings.memberId, "test-batch") }
        assertEquals(0, db.ledger().unexported(6000, settings.memberId).size)
        val frozen = db.ledger().pending(8000).single()
        val payload = JSONObject(GraphMail().payload(frozen))
        val message = payload.getJSONObject("message")
        assertEquals("Text", message.getJSONObject("body").getString("contentType"))
        val attachment = message.getJSONArray("attachments").getJSONObject(0)
        assertEquals(part.text, String(Base64.getDecoder().decode(attachment.getString("contentBytes")), Charsets.UTF_8))
        assertTrue(part.subject.contains("member=test_member"))
        assertEquals(3, part.text.trimEnd().lines().size)
        // Never call the network transport in this test.
    }

    @Test fun credentialsRoundTripThroughAndroidKeystore() {
        // Use a separate preference/key namespace inside the target app's writable sandbox.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = SecretStore(context, "test_auth", "paywhy.test.key")
        store.clear()
        try {
            store.write("synthetic-token-for-encryption-test")
            assertEquals("synthetic-token-for-encryption-test", store.read())
            val diskValue = context.getSharedPreferences("test_auth", 0).getString("state", "")!!
            assertFalse(diskValue.contains("synthetic-token"))
        } finally { store.clear() }
    }
    @Test fun qqAuthorizationIsEncryptedAndBoundToSender() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val secrets = SecretStore(context, "test_qq_auth", "paywhy.test.qq.key")
        secrets.clear()
        try {
            val qq = io.github.yufeisun5.paywhy.auth.QqCredentials(context, secrets)
            qq.save("TEST@qq.com", "abcdefghijklmnop")
            val recreated = io.github.yufeisun5.paywhy.auth.QqCredentials(context, secrets)
            val c = recreated.forMail("test@qq.com", "qq:test@qq.com")
            assertEquals("abcdefghijklmnop", c.code)
            assertTrue(runCatching { recreated.forMail("other@qq.com", "qq:other@qq.com") }.isFailure)
            assertTrue(runCatching { recreated.forMail("test@qq.com", "wrong-identity") }.isFailure)
            val stored = context.getSharedPreferences("test_qq_auth", 0).getString("state", "")!!
            assertFalse(stored.contains("abcdefghijklmnop"))
            assertFalse(stored.contains("test@qq.com"))
            qq.clear()
            assertTrue(runCatching { qq.forMail("test@qq.com", "qq:test@qq.com") }.isFailure)
        } finally { secrets.clear() }
    }

    @Test fun smtpMimeIsReadableOnAndroidAndHasPlainTextAttachment() {
        val mail = OutboxMail("test-mail", "test-batch", "test-mail", "TEST", "family@live.com", "",
            "qq:test@qq.com", "[PayWhy][TEST][妈妈]", "test.txt", "{\"member_name\":\"妈妈\"}\n", 1000,
            provider = "qq", senderAddress = "test@qq.com")
        val session = javax.mail.Session.getInstance(java.util.Properties())
        val message = io.github.yufeisun5.paywhy.mail.QqMail().message(session, mail)
        val bytes = java.io.ByteArrayOutputStream().also { message.writeTo(it) }.toByteArray()
        val parsed = javax.mail.internet.MimeMessage(session, bytes.inputStream())
        assertEquals(mail.subject, parsed.subject)
        val parts = parsed.content as javax.mail.Multipart
        assertEquals(mail.body, String(parts.getBodyPart(1).inputStream.readBytes(), Charsets.UTF_8))
        assertEquals("test.txt", parts.getBodyPart(1).fileName)
    }

}
