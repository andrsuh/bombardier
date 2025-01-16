package com.itmo.microservices.demo.externalsys.controller

import com.itmo.microservices.demo.bombardier.external.PaymentLogRecord
import com.itmo.microservices.demo.bombardier.external.PaymentStatus
import com.itmo.microservices.demo.bombardier.external.knownServices.KnownServices
import com.itmo.microservices.demo.common.*
import com.itmo.microservices.demo.common.metrics.Metrics
import com.itmo.microservices.demo.common.metrics.PromMetrics
import com.itmo.microservices.demo.common.TokenBucketRateLimiter
import io.github.resilience4j.ratelimiter.RateLimiter
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.annotation.PostConstruct
import kotlin.random.Random

@RestController
@RequestMapping("/external")
class ExternalSystemController(
    private val services: KnownServices,
    private val merger: SuspendableAwaiter<UUID, Boolean, PaymentLogRecord>
) {
    companion object {
        val logger = LoggerFactory.getLogger(ExternalSystemController::class.java)
        const val rateLimitDefault = 1
    }

    private val invoices = ConcurrentHashMap<String, AtomicInteger>()
    private val accounts = ConcurrentHashMap<String, Account>()

    private val blockList = ConcurrentHashMap<String, Long>()

// testing accounts, old
//    @PostConstruct
//    fun init() {
//        services.storage.forEach { service ->
//            // default 1 -> almost no restrictions
//            val basePrice = 100
//            val accName1 = "default-1"
//            accounts["${service.name}-$accName1"] = Account(
//                service.name,
//                accName1,
//                null,
//                slo = Slo(upperLimitInvocationMillis = 30),
//                rateLimiter = makeRateLimiter(accName1, 2100, TimeUnit.SECONDS),
//                window = SemaphoreOngoingWindow(20000),
//                price = basePrice
//            )
//
//            // default 2
//            val accName2 = "default-2"
//            accounts["${service.name}-$accName2"] = Account(
//                service.name,
//                accName2,
//                null,
//                slo = Slo(upperLimitInvocationMillis = 2_000),
//                rateLimiter = makeRateLimiter(accName2, 60, TimeUnit.SECONDS),
//                window = SemaphoreOngoingWindow(130),
//                price = (basePrice * 0.7).toInt()
//            )
//
//            // default 3
//            val accName3 = "default-3"
//            accounts["${service.name}-$accName3"] = Account(
//                service.name,
//                accName3,
//                null,
//                slo = Slo(upperLimitInvocationMillis = 3_000),
//                rateLimiter = makeRateLimiter(accName3, 10, TimeUnit.SECONDS),
//                window = SemaphoreOngoingWindow(35),
//                price = (basePrice * 0.4).toInt()
//            )
//
//            // default 4 -> like default 3, but window size is 15
//            val accName4 = "default-4"
//            accounts["${service.name}-$accName4"] = Account(
//                service.name,
//                accName4,
//                null,
//                slo = Slo(upperLimitInvocationMillis = 3_000),
//                rateLimiter = makeRateLimiter(accName4, 10, TimeUnit.SECONDS),
//                window = SemaphoreOngoingWindow(15),
//                price = (basePrice * 0.3).toInt()
//            )
//
//            // default 5
//            val accName5 = "default-5"
//            accounts["${service.name}-$accName5"] = Account(
//                service.name,
//                accName5,
//                null,
//                slo = Slo(upperLimitInvocationMillis = 10_000),
//                rateLimiter = makeRateLimiter(accName5, 10, TimeUnit.SECONDS),
//                window = SemaphoreOngoingWindow(8),
//                price = (basePrice * 0.3).toInt()
//            )
//        }
//    }
    @PostConstruct
    fun init() {
        services.storage.forEach { service ->
            val testAcc = "test-account"
            accounts["${service.name}-$testAcc"] = Account(
                service.name,
                testAcc,
                null,
                slo = Slo(upperLimitInvocationMillis = 2),
                rateLimiter = makeRateLimiter(testAcc, 100_000, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(1_000_000),
                price = 0
            )

            // default 1
            val basePrice = 100
            val accName1 = "default-1"
            accounts["${service.name}-$accName1"] = Account(
                service.name,
                accName1,
                null,
                slo = Slo(upperLimitInvocationMillis = 2),
                rateLimiter = makeRateLimiter(accName1, 3000, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(500000),
                price = basePrice
            )

            // default 2
            val accName2 = "default-2"
            accounts["${service.name}-$accName2"] = Account(
                service.name,
                accName2,
                null,
                slo = Slo(upperLimitInvocationMillis = 10_000),
                rateLimiter = makeRateLimiter(accName2, 10, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(100),
                price = (basePrice * 0.7).toInt()
            )

            // default 3
            val accName3 = "default-3"
            accounts["${service.name}-$accName3"] = Account(
                service.name,
                accName3,
                null,
                slo = Slo(upperLimitInvocationMillis = 16_000),
                rateLimiter = makeRateLimiter(accName3, 2, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(32),
                price = (basePrice * 0.3).toInt()
            )

            // default 4 -> like default 3, but window size is 15
            val accName4 = "default-4"
            accounts["${service.name}-$accName4"] = Account(
                service.name,
                accName4,
                null,
                slo = Slo(upperLimitInvocationMillis = 9_000),
                rateLimiter = makeRateLimiter(accName4, 5, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(15),
                price = (basePrice * 0.3).toInt()
            )

            // default 5
            val accName5 = "default-5"
            accounts["${service.name}-$accName5"] = Account(
                service.name,
                accName5,
                null,
                slo = Slo(upperLimitInvocationMillis = 10_000),
                rateLimiter = makeRateLimiter(accName5, 10, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(8),
                price = (basePrice * 0.3).toInt()
            )

            // default 6 fullBlockingProbability is 0.01
            val accName6 = "default-6"
            accounts["${service.name}-$accName6"] = Account(
                service.name,
                accName6,
                null,
                slo = Slo(upperLimitInvocationMillis = 1_000),
                rateLimiter = makeRateLimiter(accName6, 30, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(35),
                price = (basePrice * 0.3).toInt()
            )

            // default 7 fullBlockingProbability is 0.01
            val accName7 = "default-7"
            accounts["${service.name}-$accName7"] = Account(
                service.name,
                accName7,
                null,
                slo = Slo(upperLimitInvocationMillis = 10_000, accountBlockingProbability = 0.005),
                rateLimiter = makeRateLimiter(accName7, 7, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(10),
                price = (basePrice * 0.3).toInt()
            )

            // default 8
            val accName8 = "default-8"
            accounts["${service.name}-$accName8"] = Account(
                service.name,
                accName8,
                null,
                slo = Slo(upperLimitInvocationMillis = 4_000, errorResponseProbability = 0.07),
                rateLimiter = makeRateLimiter(accName8, 20, TimeUnit.SECONDS),
                window = SemaphoreOngoingWindow(15),
                price = (basePrice * 0.3).toInt()
            )
        }
    }

    @GetMapping("rl-demo")
    fun rlDemo() {
        val start = System.currentTimeMillis()

        val rl1 = makeRateLimiter("1", 100, TimeUnit.SECONDS)
        val rl2 = FixedWindowRateLimiter(100, 1, TimeUnit.SECONDS)
        val rl3 = CountingRateLimiter(100, 1, TimeUnit.SECONDS)
        val rl4 = SlidingWindowRateLimiter(100, Duration.ofSeconds(1))
        val rl5 = TokenBucketRateLimiter(100, 150, 1, TimeUnit.SECONDS)
        val rl6 = CompositeRateLimiter(
            SlidingWindowRateLimiter(1070, Duration.ofSeconds(10)),
            FixedWindowRateLimiter(170, 1, TimeUnit.SECONDS)
        )
        val rl7 = LeakingBucketRateLimiter(10, Duration.ofMillis(100), 120)
        println("Round,incoming,res4j,fixed,counting,sliding,token_bucket,composite_1")

        var round = 0
        while (true) {
            if (System.currentTimeMillis() - start > 120_000) {
                break
            }

            val upper = Random.nextLong(85, 120)
            Metrics.withTags("rl_type" to "no_limit").rateLimitDemo(upper)

            var rl1Allowed = 0
            var rl2Allowed = 0
            var rl3Allowed = 0
            var rl4Allowed = 0
            var rl5Allowed = 0
            var rl6Allowed = 0
            var rl7Allowed = 0

            for (i in 0 until upper) {
//                sleepIfNeeded()
                val rl1Resp = rl1.acquirePermission()
                if (rl1Resp) {
                    Metrics.withTags("rl_type" to "res4j").rateLimitDemo(1)
                    rl1Allowed++
                }
                val rl2Resp = rl2.tick()
                if (rl2Resp) {
                    Metrics.withTags("rl_type" to "fixed").rateLimitDemo(1)
                    rl2Allowed++
                }
                val rl3Resp = rl3.tick()
                if (rl3Resp) {
                    Metrics.withTags("rl_type" to "counting").rateLimitDemo(1)
                    rl3Allowed++
                }
                val rl4Resp = rl4.tick()
                if (rl4Resp) {
                    Metrics.withTags("rl_type" to "sliding").rateLimitDemo(1)
                    rl4Allowed++
                }
                val rl5Resp = rl5.tick()
                if (rl5Resp) {
                    Metrics.withTags("rl_type" to "token_bucket").rateLimitDemo(1)
                    rl5Allowed++
                }
                val rl6Resp = rl6.tick()
                if (rl6Resp) {
                    Metrics.withTags("rl_type" to "composite_1").rateLimitDemo(1)
                    rl6Allowed++
                }
                val rl7Resp = rl7.tick()
                if (rl7Resp) {
                    Metrics.withTags("rl_type" to "leaking_bucket").rateLimitDemo(1)
                    rl7Allowed++
                }
            }
            println("Round ${round++}: incoming: $upper,  res4j: $rl1Allowed,  fixed: $rl2Allowed,  counting: $rl3Allowed,  sliding: $rl4Allowed,  token_bucket: $rl5Allowed, composite_1: $rl6Allowed, leaking_bucket: $rl7Allowed,")
//            println("${round++},$upper,$rl1Allowed,$rl2Allowed,$rl3Allowed,$rl4Allowed,$rl5Allowed,$rl6Allowed")
            Thread.sleep(1000 - (System.currentTimeMillis() % 1000))
        }
    }

    private fun sleepIfNeeded() {
        if (Random.nextDouble(0.0, 1.0) < 0.2) {
            Thread.sleep(Random.nextLong(3, 8))
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
        val network: Network = Network(),
        val rateLimiter: RateLimiter,
        val window: SemaphoreOngoingWindow,
        val price: Int,
    )

    data class Network(
        val noiseLowerBoundMillis: Long = 0,
        val noiseUpperBoundMillis: Long = 10,
    )

    data class Slo(
        val upperLimitInvocationMillis: Long = 10_000,
        val accountBlockingProbability: Double = 0.0,
        val timeLimitsBreachingProbability: Double = 0.0,
        val errorResponseProbability: Double = -1.0,
    )

    @PostMapping("/process/bulk")
    suspend fun processBulk(
        @RequestBody request: BulkRequest
    ): ResponseEntity<BulkResponse> {
        val (code, resp) = processInternal(request)

        return ResponseEntity.status(code).body(resp)
    }

    @PostMapping("/process")
    suspend fun process(
        @RequestParam serviceName: String,
        @RequestParam accountName: String,
        @RequestParam transactionId: String,
        @RequestParam paymentId: String,
    ): ResponseEntity<Response> {
        val (code, bulkResp) = processInternal(
            BulkRequest(
                serviceName,
                accountName,
                listOf(Request(transactionId, paymentId))
            )
        )

        return ResponseEntity.status(code).body(bulkResp.responses.first())
    }

    private suspend fun processInternal(
        bulk: BulkRequest
    ): Pair<HttpStatus, BulkResponse> {
        val serviceName = bulk.serviceName
        val accountName = bulk.accountName

        Metrics
            .withTags(Metrics.serviceLabel to serviceName, "accountName" to accountName)
            .externalSysRequestSubmitted(bulk.requests.size.toDouble())

        val start = System.currentTimeMillis()

        val account = accounts["$serviceName-$accountName"] ?: error("No such account $serviceName-$accountName")

        // todo sukhoa reject if not supports bulk

        performBlockingLogic(account)

        val totalAmount = invoices
            .computeIfAbsent("$serviceName-$accountName") { AtomicInteger() }
            .addAndGet(account.price * bulk.requests.size)

        Metrics
            .withTags(Metrics.serviceLabel to serviceName, "accountName" to accountName)
            .externalSysChargeAmountRecord(account.price * bulk.requests.size)

        logger.info("Account $accountName charged ${account.price} from service ${account.serviceName}. Total amount: $totalAmount")

        if (!account.rateLimiter.acquirePermission()) {
            PromMetrics.externalSysDurationRecord(serviceName, accountName, "RL_BREACHED", System.currentTimeMillis() - start)
            networkLatency(account)
//            return ResponseEntity.status(500).body(Response(false, "Rate limit for account: $accountName breached"))
            return HttpStatus.INTERNAL_SERVER_ERROR to bulk.failBulk("Rate limit for account: $accountName breached")
        }

        try {
            if (account.window.tryAcquire()) {
                val duration = Random.nextLong(0, account.slo.upperLimitInvocationMillis)
                delay(duration)

                if (Random.nextDouble(0.0, 1.0) < account.slo.timeLimitsBreachingProbability) {
                    delay(Random.nextLong(account.slo.upperLimitInvocationMillis))
                }

                val resp = bulk.requests.map {
                    val result = Random.nextDouble(0.0, 1.0) > account.slo.errorResponseProbability

                    coroutineScope {
                        launch { // better to make channel + background coroutine that will wake up payments
                            try {
                                if (result) { // todo sukhoa we have to unblock it for the error also no?
                                    withTimeout(200) {
                                        merger.putSecondValueAndWaitForFirst(
                                            UUID.fromString(it.paymentId),
                                            PaymentLogRecord(
                                                System.currentTimeMillis(),
                                                PaymentStatus.SUCCESS, totalAmount, UUID.fromString(it.paymentId)
                                            )
                                        )
                                    }
                                }
                            } catch (ignored: TimeoutCancellationException) { }
                        }
                    }

                    logger.info("[external] - Transaction ${it.transactionId}. Duration: $duration")
                    Response(it.transactionId, it.paymentId, result)
                }.toBulkResponse()


                PromMetrics.externalSysDurationRecord(serviceName, accountName, "SUCCESS", System.currentTimeMillis() - start)

//                return ResponseEntity.ok(Response(result)).also {
                return (HttpStatus.OK to resp).also {
                    account.window.release()
                    networkLatency(account)
                }
            } else {
                PromMetrics.externalSysDurationRecord(serviceName, accountName, "WIN_BREACHED", System.currentTimeMillis() - start)
                networkLatency(account) // todo sukhoa once for the batch
//                return ResponseEntity.status(500).body(
//                    Response(
//                        false,
//                        "Parallel requests limit for account: $accountName breached. Already ${account.window.maxWinSize} executing"
//                    )
//                )
                return HttpStatus.INTERNAL_SERVER_ERROR to bulk.failBulk("Parallel requests limit for account: $accountName breached. Already ${account.window.maxWinSize} executing")
            }
        } catch (e: Exception) {
            account.window.release()
            PromMetrics.externalSysDurationRecord(serviceName, accountName, "UNEXPECTED_ERROR", System.currentTimeMillis() - start)
            networkLatency(account)
            throw e
        }
    }

    private suspend fun performBlockingLogic(account: Account) { //todo sukhoa extension of the blocklist
        val accountName = account.accountName

        if (Random.nextDouble(0.0, 1.0) < account.slo.accountBlockingProbability) {
            blockList[accountName] =
                System.currentTimeMillis() + Random.nextLong(account.slo.upperLimitInvocationMillis * 100)
        }

        val blockUntil = blockList[accountName]
        if (blockUntil != null) {
            delay(blockUntil - System.currentTimeMillis())
            blockList.remove(accountName)
        }
    }

    suspend fun networkLatency(account: Account) {
        if (account.network.noiseUpperBoundMillis == 0L) return
        return delay(Random.nextLong(
            account.network.noiseLowerBoundMillis,
            account.network.noiseUpperBoundMillis)
        )
    }

    data class BulkRequest(
        val serviceName: String,
        val accountName: String,
        val requests: List<Request>
    )

    data class BulkResponse(
        val responses: List<Response>
    )
    fun List<Response>.toBulkResponse() = BulkResponse(this)

    fun BulkRequest.failBulk(message: String) = BulkResponse(
        requests.map { Response(it.transactionId, it.paymentId, false, message) }
    )

    data class Request(
        val transactionId: String,
        val paymentId: String,
    )

    data class Response(
        val transactionId: String,
        val paymentId: String,
        val result: Boolean,
        val message: String? = null,
    )
}