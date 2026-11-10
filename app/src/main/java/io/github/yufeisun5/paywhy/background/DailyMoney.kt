package io.github.yufeisun5.paywhy.background

import io.github.yufeisun5.paywhy.PayWhyApp
import io.github.yufeisun5.paywhy.core.MoneyTotalBuilder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.Instant
import java.time.ZoneId

/** Event-driven updates plus one midnight boundary while the resident service exists; no wake lock. */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun dailyMoney(app: PayWhyApp) = app.settings.flow
    .map { it.memberId to it.zone }.distinctUntilChanged()
    .flatMapLatest { (member, zoneName) ->
        val zone = ZoneId.of(zoneName)
        flow {
            while (currentCoroutineContext().isActive) {
                val now = Instant.now()
                val date = now.atZone(zone).toLocalDate()
                val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
                val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                emit(start to end)
                delay((end - System.currentTimeMillis()).coerceAtLeast(1_000))
            }
        }.flatMapLatest { (start, end) -> app.db.ledger().observeMoney(member, start, end) }
    }.map(MoneyTotalBuilder::build)
