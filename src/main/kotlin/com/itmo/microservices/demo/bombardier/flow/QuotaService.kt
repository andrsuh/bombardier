package com.itmo.microservices.demo.bombardier.flow

import com.itmo.microservices.demo.common.FixedWindowRateLimiter
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

@Service
class QuotaService {
    companion object {
        const val TEST_SERVICE_PER_HOUR = 20
    }

    private val testRuns: MutableMap<String, FixedWindowRateLimiter> = ConcurrentHashMap()

    fun startTestRun(token: String): Boolean {
        return testRuns.computeIfAbsent(token) {
            FixedWindowRateLimiter(TEST_SERVICE_PER_HOUR, 1, TimeUnit.HOURS)
        }.tick()
    }
}