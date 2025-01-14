package com.itmo.microservices.demo.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.atomic.AtomicLong

class SlidingWindowRateLimiter(
    private val rate: Long,
    private val window: Duration,
): RateLimiter {
    private val sum = AtomicLong(0)
    private val queue = PriorityBlockingQueue<Measure>()


    override fun tick(): Boolean {
        if (sum.get() > rate) {
            return false
        } else {
            val res = sum.incrementAndGet()
            if (res <= rate) {
                queue.add(Measure(1, System.currentTimeMillis()))
                return true
            } else {
                sum.decrementAndGet()
                return false
            }
        }
    }

    data class Measure(
        val value: Long,
        val timestamp: Long
    ) : Comparable<Measure> {
        override fun compareTo(other: Measure): Int {
            return timestamp.compareTo(other.timestamp)
        }
    }

    private val releaseJob = rateLimiterScope.launch {
        while (true) {
            val head = queue.peek()
            val winStart = System.currentTimeMillis() - window.toMillis()
            if (head == null || head.timestamp > winStart) {
                delay(1)
                continue
//                delay(head.timestamp - winStart)
            }
            queue.take()
            sum.addAndGet(-head.value)
        }
    }.invokeOnCompletion { th -> if (th != null) logger.error("Rate limiter release job completed", th) }

    companion object {
        private val logger: Logger = LoggerFactory.getLogger(SlidingWindowRateLimiter::class.java)
        private val rateLimiterScope = CoroutineScope(Executors.newSingleThreadExecutor().asCoroutineDispatcher())
    }
}