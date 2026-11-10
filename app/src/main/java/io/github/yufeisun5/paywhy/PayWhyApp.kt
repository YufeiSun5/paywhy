package io.github.yufeisun5.paywhy

import android.app.Application
import androidx.room.Room
import io.github.yufeisun5.paywhy.auth.QqCredentials
import io.github.yufeisun5.paywhy.auth.MicrosoftAuth
import io.github.yufeisun5.paywhy.data.PayWhyDatabase
import io.github.yufeisun5.paywhy.data.SettingsStore
import io.github.yufeisun5.paywhy.mail.GraphMail
import io.github.yufeisun5.paywhy.mail.ReportRepository
import io.github.yufeisun5.paywhy.work.Scheduling
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex

class PayWhyApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var settings: SettingsStore; private set
    lateinit var db: PayWhyDatabase; private set
    lateinit var auth: MicrosoftAuth; private set
    lateinit var reports: ReportRepository; private set
    val sender = GraphMail()
    val qqSender = io.github.yufeisun5.paywhy.mail.QqMail()
    lateinit var qq: QqCredentials; private set
    fun mailReady() = if (settings.value.mailProvider == "qq") qq.ready(settings.value) else auth.value.authorized
    val deliveryMutex = Mutex()
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        db = Room.databaseBuilder(this, PayWhyDatabase::class.java, "paywhy.db").addMigrations(PayWhyDatabase.MIGRATION_1_2).build()
        auth = MicrosoftAuth(this, settings)
        qq = QqCredentials(this)
        reports = ReportRepository(this)
        scope.launch {
            deliveryMutex.lock()
            try { db.ledger().recoverInterrupted() } finally { deliveryMutex.unlock() }
            Scheduling.configure(this@PayWhyApp)
        }
    }
}
