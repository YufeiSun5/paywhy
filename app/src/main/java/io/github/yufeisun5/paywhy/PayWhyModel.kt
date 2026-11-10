package io.github.yufeisun5.paywhy

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.yufeisun5.paywhy.data.Settings
import io.github.yufeisun5.paywhy.work.Scheduling
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.withLock

class PayWhyModel(application: Application) : AndroidViewModel(application) {
    val app = application as PayWhyApp
    private val sharing = SharingStarted.WhileSubscribed(5_000)
    val records = app.db.ledger().observeRecent().stateIn(viewModelScope, sharing, emptyList())
    val mails = app.db.ledger().observeMails().stateIn(viewModelScope, sharing, emptyList())
    val count = app.db.ledger().observeCount().stateIn(viewModelScope, sharing, 0)
    val unexported = app.db.ledger().observeUnexported().stateIn(viewModelScope, sharing, 0)
    val busy = MutableStateFlow(false)
    val message = MutableStateFlow<String?>(null)
    var pendingNotificationSave: Settings? = null
    private fun action(block: suspend () -> String) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try { message.value = withContext(Dispatchers.IO) { block() } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { message.value = e.message ?: "操作失败，请重试" }
            finally { busy.value = false }
        }
    }
    fun save(s: Settings, notice: String = "设置已保存") = action {
        app.deliveryMutex.withLock {
            s.validate()
            val old = app.settings.value
            require(s.memberId == old.memberId || !old.captureEnabled && app.db.ledger().count() == 0) {
                "采集开始后不能更改成员 ID；换机时请在首次采集前设置相同 ID。"
            }
            if (s.clientId != old.clientId) app.auth.forget()
            app.settings.save(s)
            Scheduling.configure(app)
        }
        notice
    }
    fun completeLogin(intent: Intent) = action {
        app.auth.complete(intent)
        Scheduling.configure(app)
        "微软邮箱已连接。请发送测试邮件验证收件。"
    }
    fun signOut() = action {
        app.deliveryMutex.withLock {
            app.auth.forget()
            app.settings.save(app.settings.value.copy(autoSend = false))
            Scheduling.configure(app)
        }
        "已清除本机登录凭据并关闭每日发送"
    }
    fun queue(test: Boolean) = action {
        if (app.reports.manual(test)) {
            Scheduling.deliver(app)
            if (test) "测试邮件已排队，可在投递页查看结果" else "新记录已排队发送"
        } else "没有尚未导出的记录"
    }
    fun retry(id: String) = action {
        app.db.ledger().retry(id)
        Scheduling.deliver(app)
        "已重新排队；同一批次 ID 保持不变"
    }
}
