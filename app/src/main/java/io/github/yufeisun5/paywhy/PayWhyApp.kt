package io.github.yufeisun5.paywhy

import android.app.Application
import androidx.room.Room
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
    val deliveryMutex = Mutex()
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        db = Room.databaseBuilder(this, PayWhyDatabase::class.java, "paywhy.db").build()
        auth = MicrosoftAuth(this, settings)
        reports = ReportRepository(this)
        scope.launch {
            deliveryMutex.lock()
            try { db.ledger().recoverInterrupted() } finally { deliveryMutex.unlock() }
            Scheduling.configure(this@PayWhyApp)
        }
    }
}
