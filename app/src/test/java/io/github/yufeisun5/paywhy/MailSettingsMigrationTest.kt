package io.github.yufeisun5.paywhy

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.github.yufeisun5.paywhy.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class MailSettingsMigrationTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Test fun newInstallOffersQqWhileConfiguredMicrosoftIsPreserved() {
        val prefs = context.getSharedPreferences("settings", 0)
        prefs.edit().clear().commit()
        assertEquals("qq", SettingsStore(context).value.mailProvider)
        prefs.edit().clear().putString("client", "00000000-0000-0000-0000-000000000001").commit()
        assertEquals("microsoft", SettingsStore(context).value.mailProvider)
    }
    @Test fun qqDailySendNeedsSenderAndRecipientButNotMicrosoftClientId() {
        context.getSharedPreferences("settings", 0).edit().clear().commit()
        val s = SettingsStore(context).value.copy(mailProvider = "qq", qqAddress = "test@qq.com",
            recipient = "family@live.com", autoSend = true, clientId = "")
        s.validate()
        assertTrue(runCatching { s.copy(qqAddress = "").validate() }.isFailure)
        assertTrue(runCatching { s.copy(qqAddress = "a@qq.com\r\nBcc:evil@example.com").validate() }.isFailure)
        assertTrue(runCatching { s.copy(mailProvider = "microsoft").validate() }.isFailure)
        val store = SettingsStore(context)
        store.save(s)
        assertEquals(s, SettingsStore(context).value)
    }
    @Test fun v1DatabaseUpgradeKeepsRecordsAndOriginalMicrosoftQueue() = runBlocking {
        val name = "migration-qq-test.db"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).apply { parentFile!!.mkdirs() }
        val schema = JSONObject(javaClass.getResourceAsStream("/database-v1.json")!!.bufferedReader().readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                old.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indexes = entity.optJSONArray("indices") ?: org.json.JSONArray()
                for (j in 0 until indexes.length()) old.execSQL(indexes.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) old.execSQL(setup.getString(i))
            old.execSQL("INSERT INTO observations VALUES ('record','fingerprint','member','妈妈','device','com.tencent.mm',1000,1000,1230,'CNY','expense','parsed_unverified','微信支付','合成记录',0,NULL,NULL)")
            old.execSQL("INSERT INTO outbox VALUES ('old-mail','batch','old-mail','TEST','old@live.com','original-client','original-identity','old subject','test.txt','{}',1000,'retry',2,NULL,NULL,NULL,0)")
            old.version = 1
        }
        val db = Room.databaseBuilder(context, PayWhyDatabase::class.java, name).addMigrations(PayWhyDatabase.MIGRATION_1_2).build()
        try {
            assertEquals(1, db.ledger().count())
            val mail = db.ledger().pending(2000).single()
            assertEquals("microsoft", mail.provider)
            assertEquals("", mail.senderAddress)
            assertEquals("original-client", mail.clientId)
            assertEquals("original-identity", mail.authGeneration)
            assertEquals("old@live.com", mail.recipient)
            assertEquals(2, mail.attempts)
            assertEquals("妈妈", db.ledger().unexported(2000, "member").single().memberName)
        } finally { db.close(); context.deleteDatabase(name) }
    }
}
