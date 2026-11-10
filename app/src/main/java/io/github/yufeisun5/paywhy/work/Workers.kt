package io.github.yufeisun5.paywhy.work

import android.content.Context
import android.net.ConnectivityManager
import androidx.work.*
import io.github.yufeisun5.paywhy.PayWhyApp
import io.github.yufeisun5.paywhy.auth.LoginRequired
import io.github.yufeisun5.paywhy.core.ReportSchedule
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.*
import java.util.concurrent.TimeUnit

object Scheduling {
    /** Hourly system-managed local check; no persistent service, exact alarm, or network polling. */
    fun configure(app: PayWhyApp) {
        val wm = WorkManager.getInstance(app)
        if (app.settings.value.autoSend) {
            wm.enqueueUniquePeriodicWork("daily-check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<DailyWorker>(1, TimeUnit.HOURS, 15, TimeUnit.MINUTES).build())
            scheduleNext(app)
            wm.enqueueUniqueWork("daily-catchup", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<DailyWorker>().build())
        } else {
            wm.cancelUniqueWork("daily-check")
            wm.cancelAllWorkByTag("daily-next")
            wm.cancelUniqueWork("daily-catchup")
        }
    }
    fun scheduleNext(app: PayWhyApp) {
        if (!app.settings.value.autoSend) return
        val s = app.settings.value
        val now = Instant.now()
        val next = ReportSchedule.nextWake(now, ZoneId.of(s.zone), LocalTime.parse(s.sendTime))
        WorkManager.getInstance(app).enqueueUniqueWork("daily-${next.toEpochMilli()}", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<DailyWorker>().addTag("daily-next")
                .setInitialDelay(Duration.between(now, next)).build())
    }
    fun deliver(app: PayWhyApp) {
        val network = if (app.settings.value.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        WorkManager.getInstance(app).enqueueUniqueWork("mail-delivery", ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<DeliveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES).build())
    }
}

class DailyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as PayWhyApp
        if (!app.settings.value.autoSend) return Result.success()
        return try {
            app.reports.daily()
            Scheduling.scheduleNext(app)
            if (app.db.ledger().pending(System.currentTimeMillis()).isNotEmpty()) Scheduling.deliver(app)
            Result.success()
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { Result.retry() }
    }
}

class DeliveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val app = applicationContext as PayWhyApp
        app.deliveryMutex.withLock {
            val dao = app.db.ledger()
            var retryNeeded = false
            for (mail in dao.pending(System.currentTimeMillis())) {
                val connectivity = app.getSystemService(ConnectivityManager::class.java)
                if (app.settings.value.wifiOnly && connectivity.isActiveNetworkMetered) return@withLock Result.retry()
                var qqCredential: io.github.yufeisun5.paywhy.auth.QqCredentials.Credential? = null
                val token = try {
                    if (mail.provider != app.settings.value.mailProvider) {
                        dao.result(mail.id, "failed", "发送方式已切换；请切回创建此邮件的发送方式后重试")
                        continue
                    }
                    if (mail.provider == "qq") {
                        check(mail.senderAddress == app.settings.value.qqAddress)
                        qqCredential = app.qq.forMail(mail.senderAddress, mail.authGeneration)
                        ""
                    } else {
                    if (mail.clientId != app.settings.value.clientId) throw LoginRequired()
                    app.auth.token(mail.authGeneration)
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: LoginRequired) {
                    dao.result(mail.id, "failed", "请用创建此邮件的微软账户重新登录，然后重试。")
                    continue
                } catch (_: IllegalStateException) {
                    dao.result(mail.id, "failed", "请配置创建此邮件的 QQ 邮箱及授权码后重试")
                    continue
                } catch (_: Exception) { retryNeeded = true; continue }
                if (dao.claim(mail.id, System.currentTimeMillis()) == 0) continue
                try {
                    val result = if (mail.provider == "qq") app.qqSender.send(mail, requireNotNull(qqCredential)) else app.sender.send(mail, token)
                    val status = if (result.status == "retry" && mail.attempts >= 9) "failed" else result.status
                    dao.result(mail.id, status, result.reason,
                        if (status == "accepted") System.currentTimeMillis() else null,
                        System.currentTimeMillis() + result.retryAfterMillis)
                    if (status == "retry") retryNeeded = true
                } catch (e: CancellationException) {
                    withContext(NonCancellable) { dao.result(mail.id, "uncertain", "发送被中断，请先检查邮箱后再重试。") }
                    throw e
                } catch (_: IOException) {
                    dao.result(mail.id, "uncertain", "发送连接中断，可能已投递；请检查邮箱后再决定是否重试。")
                } catch (_: Exception) {
                    dao.result(mail.id, "uncertain", "发送过程异常，结果不确定；请检查邮箱。")
                }
            }
            if (dao.retryableCount() > 0) retryNeeded = true
            if (retryNeeded) Result.retry() else Result.success()
        }
    }
}
