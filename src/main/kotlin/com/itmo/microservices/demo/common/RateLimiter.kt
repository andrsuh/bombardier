package com.itmo.microservices.demo.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RateLimiter(
    private val rate: Int,
    private val timeUnit: TimeUnit = TimeUnit.MINUTES
) {
    companion object {
        private val logger: Logger = LoggerFactory.getLogger(RateLimiter::class.java)
        private val rateLimiterScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    }

    private val semaphore = Semaphore(rate)

    private val releaseJob = rateLimiterScope.launch {
        while (true) {
            val permitsToRelease = rate - semaphore.availablePermits
            repeat(permitsToRelease) {
                runCatching {
                    semaphore.release()
                }.onFailure { th -> logger.error("Failed while releasing permits", th) }
            }
            logger.debug("Released $permitsToRelease permits")
            delay(timeUnit.toMillis(1))
        }
    }.invokeOnCompletion { th -> if (th != null) logger.error("Rate limiter release job completed", th) }

    fun tick() = semaphore.tryAcquire()
}