package com.itmo.microservices.demo.common

class CompositeRateLimiter(
    private val rl1: RateLimiter,
    private val rl2: RateLimiter,
) : RateLimiter {
    override fun tick(): Boolean {
        return rl1.tick() && rl2.tick()
    }
}

class AllAllowCompositeRateLimiter(
    private val rateLimiters: List<RateLimiter>
) : RateLimiter {
    override fun tick(): Boolean = rateLimiters.all { it.tick() }
}

class AnyAllowCompositeRateLimiter(
    private val rateLimiters: List<RateLimiter>
) : RateLimiter {
    override fun tick(): Boolean = rateLimiters.any { it.tick() }
}
