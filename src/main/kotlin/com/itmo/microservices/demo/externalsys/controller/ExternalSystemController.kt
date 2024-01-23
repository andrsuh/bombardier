package com.itmo.microservices.demo.externalsys.controller

import com.itmo.microservices.demo.bombardier.external.knownServices.KnownServices
import com.itmo.microservices.demo.common.OngoingWindow
import com.itmo.microservices.demo.common.RateLimiter
import com.itmo.microservices.demo.common.metrics.Metrics
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
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

    @PostConstruct
    fun init() {
        services.storage.forEach { service ->
            arrayOf(1, 2).forEach {
                val basePrice = 1
                val accName = "default-${it}"
                accounts["${service.name}-$accName"] = Account(
                    service.name,
                    "default",
                    null,
                    slo = Slo(),
                    rateLimiter = RateLimiter(5, TimeUnit.SECONDS),
                    window = OngoingWindow(16),
                    price = basePrice * it
                )
            }
        }
    }

    @PostMapping("/account")
    fun createAccount(@RequestBody request: AccountDto) {
        val rLimiter = RateLimiter(
            request.slo.tpsec ?: request.slo.tpmin ?: rateLimitDefault,
            if (request.slo.tpsec != null) TimeUnit.SECONDS else if (request.slo.tpmin != null) TimeUnit.MINUTES else rateLimitDefaultUnit
        )

        val window = OngoingWindow(request.slo.winSize)

        accounts[request.accountName] = Account(
            request.serviceName,
            request.accountName,
            request.callbackPath,
            slo = Slo(request.slo.upperLimitInvocationMillis),
            rateLimiter = rLimiter,
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
        val window: OngoingWindow,
        val price: Int,
    )


    data class Slo(
        val upperLimitInvocationMillis: Long = 5_000,
    )

    @PostMapping("/process")
    suspend fun process(
        @RequestParam serviceName: String,
        @RequestParam accountName: String,
        @RequestParam transactionId: String
    ): ResponseEntity<Response> {
        val account = accounts["$serviceName-$accountName"] ?: error("No such account $serviceName-$accountName")
        val totalAmount = invoices.computeIfAbsent("$serviceName-$accountName") { AtomicInteger() }.let {
            it.addAndGet(account.price)
        }

        logger.info("Account $accountName charged ${account.price} from service ${account.serviceName}. Total amount: $totalAmount")
        Metrics
            .withTags(Metrics.serviceLabel to serviceName, "accountName" to accountName)
            .externalSysChargeAmountRecord(totalAmount)

        if (!account.rateLimiter.tick()) {
            return ResponseEntity.status(500).body(Response(false, "Rate limit for account: $accountName breached"))
        }
        when (val res = account.window.putIntoWindow()) {
            is OngoingWindow.WindowResponse.Success -> {
                val duration = Random.nextLong(0, account.slo.upperLimitInvocationMillis)
                delay(duration)
                logger.info("[external] - Transaction $transactionId. Duration: $duration")

                return ResponseEntity.ok(Response(true)).also {
                    account.window.releaseWindow()
                }
            }
            is OngoingWindow.WindowResponse.Fail -> {
                return ResponseEntity.status(500).body(
                    Response(
                        false,
                        "Parallel requests limit for account: $accountName breached. Already ${res.currentWinSize} executing"
                    )
                )
            }
        }
    }

    class Response(
        val result: Boolean,
        val message: String? = null,
    )
}