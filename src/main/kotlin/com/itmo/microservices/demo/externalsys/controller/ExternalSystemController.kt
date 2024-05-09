package com.itmo.microservices.demo.externalsys.controller

import com.itmo.microservices.demo.bombardier.external.knownServices.KnownServices
import com.itmo.microservices.demo.common.SemaphoreOngoingWindow
import com.itmo.microservices.demo.common.makeRateLimiter
import com.itmo.microservices.demo.common.metrics.Metrics
import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.PostConstruct
import kotlin.random.Random

@RestController
@RequestMapping("/external")
class ExternalSystemController(
    private val services: KnownServices
) {
    companion object {
        val logger = LoggerFactory.getLogger(ExternalSystemController::class.java)
        const val rateLimitDefault = 1
        val rateLimitDefaultUnit = TimeUnit.SECONDS
        const val winDefault = 8
    }

    private val invoices = ConcurrentHashMap<String, AtomicInteger>()
    private val accounts = ConcurrentHashMap<String, Account>()

    private val blockList = ConcurrentHashMap<String, Boolean>()
    @PostConstruct
    fun init() {
        services.storage.forEach { service ->
            // default 1 -> almost no restrictions
            val basePrice = 100
            val accName1 = "default-1"
            accounts["${service.name}-$accName1"] = Account(
                service.name,
                accName1,
                null,
                slo = Slo(upperLimitInvocationMillis = 1000),
                rateLimiter = makeRateLimiter(accName1, 100, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(1000),
                price = basePrice
            )

            // default 2 -> almost no restrictions, but more expensive and more time to process (10s)
            val accName2 = "default-2"
            accounts["${service.name}-$accName2"] = Account(
                service.name,
                accName2,
                null,
                slo = Slo(upperLimitInvocationMillis = 2_000),
                rateLimiter = makeRateLimiter(accName2, 60, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(130),
                price = (basePrice * 0.7).toInt()
            )

            // default 3 -> like default 2, but rate limit is 15 per second
            val accName3 = "default-3"
            accounts["${service.name}-$accName3"] = Account(
                service.name,
                accName3,
                null,
                slo = Slo(upperLimitInvocationMillis = 3_000),
                rateLimiter = makeRateLimiter(accName3, 10, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(35),
                price = (basePrice * 0.4).toInt()
            )

            // default 4 -> like default 3, but window size is 15
            val accName4 = "default-4"
            accounts["${service.name}-$accName4"] = Account(
                service.name,
                accName4,
                null,
                slo = Slo(upperLimitInvocationMillis = 3_000),
                rateLimiter = makeRateLimiter(accName4, 10, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(15),
                price = (basePrice * 0.3).toInt()
            )

            // default 4.2 -> same as default 4, but a bit more expensive
            val accName42 = "default-42"
            accounts["${service.name}-$accName42"] = Account(
                service.name,
                accName42,
                null,
                slo = Slo(upperLimitInvocationMillis = 10_000, fullBlockingProbability = 0.005),
                rateLimiter = makeRateLimiter(accName42, 7, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(10),
                price = (basePrice * 0.35).toInt()
            )

            // default 5 -> like default 4, but fullBlockingProbability is 0.01
            val accName5 = "default-5"
            accounts["${service.name}-$accName5"] = Account(
                service.name,
                accName5,
                null,
                slo = Slo(upperLimitInvocationMillis = 10_000, fullBlockingProbability = 0.005),
                rateLimiter = makeRateLimiter(accName5, 7, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(10),
                price = (basePrice * 0.3).toInt()
            )
        }
    }

    @PostMapping("/account")
    fun createAccount(@RequestBody request: AccountDto) {
        val rateLimiter = makeRateLimiter(
            "rateLimiter:${request.accountName}",
            request.slo.tpsec ?: request.slo.tpmin ?: rateLimitDefault,
            if (request.slo.tpsec != null) TimeUnit.SECONDS else TimeUnit.MINUTES
        )

        val window = SemaphoreOngoingWindow(request.slo.winSize)

        accounts[request.accountName] = Account(
            request.serviceName,
            request.accountName,
            request.callbackPath,
            slo = Slo(request.slo.upperLimitInvocationMillis),
            rateLimiter = rateLimiter,
            window = window,
            price = request.price
        )
    }

    data class AccountDto(
        val serviceName: String,
        val accountName: String,
        val callbackPath: String?,
        val slo: SloDto = SloDto(),
        val price: Int = 10,
    )

    data class SloDto(
        val upperLimitInvocationMillis: Long = 10_000,
        val tpsec: Int? = null,
        val tpmin: Int? = null,
        val winSize: Int = 16,
    )

    data class Account(
        val serviceName: String,
        val accountName: String,
        val callbackPath: String?,
        val slo: Slo = Slo(),
        val rateLimiter: RateLimiter,
        val window: SemaphoreOngoingWindow,
        val price: Int,
    )

    data class Slo(
        val upperLimitInvocationMillis: Long = 10_000,
        val fullBlockingProbability: Double = 0.0,
    )

    @PostMapping("/process")
    suspend fun process(
        @RequestParam serviceName: String,
        @RequestParam accountName: String,
        @RequestParam transactionId: String
    ): ResponseEntity<Response> {
        val start = System.currentTimeMillis()

        val account = accounts["$serviceName-$accountName"] ?: error("No such account $serviceName-$accountName")

        if (Random.nextDouble(0.0, 1.0) < account.slo.fullBlockingProbability) {
            blockList[accountName] = true
        }

        if (blockList[accountName] == true) {
            delay(Random.nextLong(account.slo.upperLimitInvocationMillis * 10))
            blockList[accountName] = false
        }

        val totalAmount = invoices.computeIfAbsent("$serviceName-$accountName") { AtomicInteger() }.let {
            it.addAndGet(account.price)
        }

        Metrics
            .withTags(Metrics.serviceLabel to serviceName, "accountName" to accountName)
            .externalSysChargeAmountRecord(account.price)

        logger.info("Account $accountName charged ${account.price} from service ${account.serviceName}. Total amount: $totalAmount")

        if (!account.rateLimiter.acquirePermission()) {
            Metrics
                .withTags(Metrics.serviceLabel to serviceName, "accountName" to accountName, "outcome" to "RL_BREACHED")
                .externalSysDurationRecord(System.currentTimeMillis() - start)

            return ResponseEntity.status(500).body(Response(false, "Rate limit for account: $accountName breached"))
        }

        try {
            if (account.window.acquire()) {
                val duration = Random.nextLong(0, account.slo.upperLimitInvocationMillis)
                delay(duration)
                logger.info("[external] - Transaction $transactionId. Duration: $duration")

                Metrics
                    .withTags(Metrics.serviceLabel to serviceName, "accountName" to accountName, "outcome" to "SUCCESS")
                    .externalSysDurationRecord(System.currentTimeMillis() - start)

                return ResponseEntity.ok(Response(true)).also {
                    account.window.release()
                }
            } else {
                Metrics
                    .withTags(
                        Metrics.serviceLabel to serviceName,
                        "accountName" to accountName,
                        "outcome" to "WIN_BREACHED"
                    )
                    .externalSysDurationRecord(System.currentTimeMillis() - start)

                return ResponseEntity.status(500).body(
                    Response(
                        false,
                        "Parallel requests limit for account: $accountName breached. Already ${account.window.maxWinSize} executing"
                    )
                )
            }
        } catch (e: Exception) {
            account.window.release()
            throw e
        }
    }

    class Response(
        val result: Boolean,
        val message: String? = null,
    )
}