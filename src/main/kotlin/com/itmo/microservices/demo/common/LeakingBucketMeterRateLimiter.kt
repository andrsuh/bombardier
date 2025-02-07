package com.itmo.microservices.demo.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

class LeakingBucketMeterRateLimiter(
    private val rate: Long,
    private val window: Duration,
    private val bucketSize: Int,
) : RateLimiter {
    private val rateLimiterScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val meter = AtomicInteger(0)

    override fun tick(): Boolean {
        while (true) {
            val current = meter.get()
            if (current >= bucketSize) return false
            if (meter.compareAndSet(current, current + 1)) return true
        }
    }

    private val releaseJob = rateLimiterScope.launch {
        while (true) {
            delay(window.toMillis())
            for (i in 0..rate) {
                meter.decrementAndGet()
            }
        }
    }.invokeOnCompletion { th -> if (th != null) logger.error("Rate limiter release job completed", th) }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(LeakingBucketRateLimiter::class.java)
    }
}