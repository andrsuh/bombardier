package com.itmo.microservices.demo.bombardier.utils

import kotlinx.coroutines.delay
import java.time.Duration
import java.util.concurrent.TimeUnit

class ConditionAwaiter(
    private val period: Long,
    private val unit: TimeUnit,
    private val pollingPeriod: Duration = Duration.ofMillis(3000),
) {
    companion object {
        fun awaitAtMost(period: Long, unit: TimeUnit, pollingPeriod: Duration = Duration.ofMillis(500)) = ConditionAwaiter(period, unit, pollingPeriod)
    }

    private var condition: (suspend () -> Boolean)? = null

    private var successClosure: suspend () -> Unit = {}
    private var failureClosure: suspend (th: Throwable?) -> Unit = { th ->
        val message = "Condition is not fulfilled"
        if (th != null)
            throw IllegalArgumentException(message, th)
        else
            throw IllegalArgumentException(message)
    }

    fun condition(condition: suspend () -> Boolean): ConditionAwaiter {
        this.condition = condition
        return this
    }

    suspend fun startWaiting() {
        requireNotNull(condition) { "condition is null" }

        val waitUpTo = System.currentTimeMillis() + unit.toMillis(period)
        while (System.currentTimeMillis() <= waitUpTo) {
            delay(pollingPeriod.toMillis())
            try {
                if (condition!!()) {
                    successClosure.invoke()
                    return
                }
            } catch (th: Throwable) {
                failureClosure.invoke(th)
                return
            }
        }
        failureClosure.invoke(null)
    }

    fun onSuccess(action: suspend () -> Unit): ConditionAwaiter {
        successClosure = action
        return this
    }

    fun onFailure(action: suspend (th: Throwable?) -> Unit): ConditionAwaiter {
        failureClosure = action
        return this
    }
}