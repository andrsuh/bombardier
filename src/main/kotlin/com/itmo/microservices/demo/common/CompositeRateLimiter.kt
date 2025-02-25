package com.itmo.microservices.demo.common

class CompositeRateLimiter(
    private val rl1: RateLimiter,
    private val rl2: RateLimiter,
    private val conjunction: Boolean = true
) : RateLimiter {
    override fun tick(): Boolean {
        return if (conjunction) rl1.tick() && rl2.tick() else rl1.tick() || rl2.tick()
    }
}
