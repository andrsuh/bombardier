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
import kotlin.random.Random

class LeakingBucketFluctuatingRateLimiter(
    private val rate: Int,
    private val window: Duration,
    private val bucketSize: Int,
    private val amplitude: Int,
    private val probability: Double,
) : RateLimiter {
    private val rateLimiterScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val meter = AtomicInteger(0)

    @Volatile
    private var thisRoundBucket = bucketSize

    override fun tick(): Boolean {
        while (true) {
            val current = meter.get()
            if (current >= thisRoundBucket) return false
            if (meter.compareAndSet(current, current + 1)) return true
        }
    }

    private val releaseJob = rateLimiterScope.launch {
        while (true) {
            delay(window.toMillis())
            for (i in 0..rate) {
                if (meter.decrementAndGet() == 0) break
            }
            thisRoundBucket = if (Random.nextDouble(0.0, 1.0) < probability) {
                bucketSize + Random.nextInt(-amplitude, amplitude)
            } else {
                bucketSize
            }
        }
    }.invokeOnCompletion { th -> if (th != null) logger.error("Rate limiter release job completed", th) }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(LeakingBucketRateLimiter::class.java)
    }
}

class SinRateLimiter(
    private val rate: Int,
    private val window: Duration,
    private val amplitude: Int,
    private val frequencySec: Int = 5,
) : RateLimiter {
    private val rateLimiterScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    private val meter = AtomicInteger(0)

    @Volatile
    private var thisRoundRate = rate

    private var secondsCounter = 1

    override fun tick(): Boolean {
        while (true) {
            val current = meter.get()
            if (current >= thisRoundRate) return false
            if (meter.compareAndSet(current, current + 1)) return true
        }
    }

    private val releaseJob = rateLimiterScope.launch {
        while (true) {
            delay(window.toMillis())
            meter.set(0)
            thisRoundRate = (amplitude * Math.sin((2 * Math.PI * secondsCounter / frequencySec) - (Math.PI / 2))).toInt() + rate
            logger.trace("Rate: $thisRoundRate")
            secondsCounter++
        }
    }.invokeOnCompletion { th -> if (th != null) logger.error("Rate limiter release job completed", th) }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(LeakingBucketRateLimiter::class.java)
    }
}