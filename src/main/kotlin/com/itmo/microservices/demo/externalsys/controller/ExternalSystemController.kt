package com.itmo.microservices.demo.externalsys.controller

import com.itmo.microservices.demo.bombardier.external.PaymentLogRecord
import com.itmo.microservices.demo.bombardier.external.PaymentStatus
import com.itmo.microservices.demo.bombardier.external.knownServices.KnownServices
import com.itmo.microservices.demo.common.*
import com.itmo.microservices.demo.common.metrics.Metrics
import com.itmo.microservices.demo.common.metrics.PromMetrics
import com.itmo.microservices.demo.common.TokenBucketRateLimiter
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatus.*
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
        val defaultTimeout = Duration.ofHours(1).toMillis()
    }

    private val invoices = ConcurrentHashMap<String, AtomicInteger>()
    private val accounts = ConcurrentHashMap<String, Account>()

    private val blockList = ConcurrentHashMap<String, Long>()

    @PostConstruct
    fun init() {
        services.storage.forEach { service ->
            val testAcc = "test-account"
            accounts["${service.name}-$testAcc"] = Account(
                service.name,
                testAcc,
                null,
                speedLimits = SpeedLimits(100_000, 100_000_000),
                slo = Slo(upperLimitInvocationMillis = 10),
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
                speedLimits = SpeedLimits(3000, 50_000),
                price = basePrice
            )

            // default 2
            val accName2 = "default-2"
            accounts["${service.name}-$accName2"] = Account(
                service.name,
                accName2,
                null,
                slo = Slo(upperLimitInvocationMillis = 10_000),
                speedLimits = SpeedLimits(10, 100),
                price = (basePrice * 0.15).toInt()
            )

            // acc 3
            val accName3 = "acc-3"
            accounts["${service.name}-$accName3"] = Account(
                service.name,
                accName3,
                null,
                slo = Slo(upperLimitInvocationMillis = 2_000),
                speedLimits = SpeedLimits(10, 30),
                price = (basePrice * 0.3).toInt()
            )

            // default 4 -> like default 3, but window size is 15
            val accName4 = "default-4"
            accounts["${service.name}-$accName4"] = Account(
                service.name,
                accName4,
                null,
                slo = Slo(upperLimitInvocationMillis = 9_000),
                speedLimits = SpeedLimits(5, 15),
                price = (basePrice * 0.3).toInt()
            )

            // acc 5
            val accName5 = "acc-5"
            accounts["${service.name}-$accName5"] = Account(
                service.name,
                accName5,
                null,
                slo = Slo(upperLimitInvocationMillis = 9800),
                speedLimits = SpeedLimits(3, 5),
                price = (basePrice * 0.3).toInt()
            )

            // default 6 fullBlockingProbability is 0.01
            val accName6 = "default-6"
            accounts["${service.name}-$accName6"] = Account(
                service.name,
                accName6,
                null,
                slo = Slo(upperLimitInvocationMillis = 1_000),
                speedLimits = SpeedLimits(30, 35),
                price = (basePrice * 0.3).toInt()
            )

            val accName7 = "acc-7"
            accounts["${service.name}-$accName7"] = Account(
                service.name,
                accName7,
                null,
                slo = Slo(upperLimitInvocationMillis = 1000, timeLimitsBreachingProbability = 0.1, timeLimitsBreachingMinTime = Duration.ofMillis(9400), timeLimitsBreachingMaxTime = Duration.ofMillis(9500)),
                network = Network(40, 90),
                speedLimits = SpeedLimits(8, 50),
                price = (basePrice * 0.3).toInt(),
                exposedAverageProcessingTime = Duration.ofMillis(1200)
            )

            // default 8
            val accName8 = "acc-8"
            accounts["${service.name}-$accName8"] = Account(
                service.name,
                accName8,
                null,
                slo = Slo(upperLimitInvocationMillis = 1400, errorResponseProbability = 0.1),
                network = Network(40, 90),
                speedLimits = SpeedLimits(10, 50),
                price = (basePrice * 0.3).toInt()
            )

            val accName9 = "acc-9"
            accounts["${service.name}-$accName9"] = Account(
                service.name,
                accName9,
                null,
                slo = Slo(upperLimitInvocationMillis = 1000),
                network = Network(15, 40),
                speedLimits = SpeedLimits(120, 50),
                price = (basePrice * 0.3).toInt()
            )

            val accName10 = "acc-10"
            accounts["${service.name}-$accName10"] = Account(
                service.name,
                accName10,
                null,
                slo = Slo(upperLimitInvocationMillis = 1000),
                network = Network(15, 40),
                speedLimits = SpeedLimits(480, 200),
                price = (basePrice * 0.3).toInt()
            )

            val accName11 = "acc-11"
            accounts["${service.name}-$accName11"] = Account(
                service.name,
                accName11,
                null,
                slo = Slo(upperLimitInvocationMillis = 2000),
                network = Network(15, 40),
                speedLimits = SpeedLimits(480, 400),
                price = (basePrice * 0.3).toInt()
            )

            val accName12 = "acc-12"
            accounts["${service.name}-$accName12"] = Account(
                service.name,
                accName12,
                null,
                slo = Slo(upperLimitInvocationMillis = 20_000),
                speedLimits = SpeedLimits(1100, 20_000),
                price = (basePrice * 0.3).toInt()
            )

            val accName13 = "acc-13"
            accounts["${service.name}-$accName13"] = Account(
                service.name,
                accName13,
                null,
                slo = Slo(upperLimitInvocationMillis = 20),
                speedLimits = SpeedLimits(5000, 2000),
                price = (basePrice * 0.3).toInt()
            )

            val accName14 = "acc-14"
            accounts["${service.name}-$accName14"] = Account(
                service.name,
                accName14,
                null,
                slo = Slo(
                    upperLimitInvocationMillis = 1000,
                    errorResponseProbability = 0.01
                ),
                network = Network(0, 15),
                speedLimits = SpeedLimits(500, 1500),
                price = (basePrice * 0.45).toInt()
            )

            val accName15 = "acc-15"
            accounts["${service.name}-$accName15"] = Account(
                service.name,
                accName15,
                null,
                network = Network(15, 40),
                slo = Slo(
                    upperLimitInvocationMillis = 2_000,
                    errorResponseProbability = 0.07,
                    timeLimitsBreachingProbability = 0.001,
                    timeLimitsBreachingMinTime = Duration.ofSeconds(5),
                    timeLimitsBreachingMaxTime = Duration.ofSeconds(15)
                ),
                speedLimits = SpeedLimits(200, 1500),
                price = (basePrice * 0.3).toInt()
            )

            val accName16 = "acc-16"
            accounts["${service.name}-$accName16"] = Account(
                service.name,
                accName16,
                null,
                slo = Slo(upperLimitInvocationMillis = 1000, timeLimitsBreachingProbability = 0.15, timeLimitsBreachingMinTime = Duration.ofDays(1), timeLimitsBreachingMaxTime = Duration.ofDays(2)),
                network = Network(40, 90),
                speedLimits = SpeedLimits(30, 5),
                price = (basePrice * 0.3).toInt(),
                exposedAverageProcessingTime = Duration.ofMillis(800)
            )
        }
    }

    @GetMapping("/accounts")
    fun getAccounts(@RequestParam serviceName: String): List<AccountProperties> {
        return accounts.values.map {
            AccountProperties(
                serviceName,
                it.accountName,
                it.speedLimits.win,
                it.speedLimits.rps,
                it.price,
                it.exposedAverageProcessingTime,
            )
        }
    }

    data class AccountProperties(
        val serviceName: String,
        val accountName: String,
        val parallelRequests: Int,
        val rateLimitPerSec: Int,
        val price: Int,
        val averageProcessingTime: Duration = Duration.ofSeconds(11),
    )

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
        println("incoming,res4j,fixed,counting,sliding,token_bucket,composite_1,leaking_bucket")

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
//            println("Round ${round++}: incoming: $upper,  res4j: $rl1Allowed,  fixed: $rl2Allowed,  counting: $rl3Allowed,  sliding: $rl4Allowed,  token_bucket: $rl5Allowed, composite_1: $rl6Allowed, leaking_bucket: $rl7Allowed,")
            println("$upper,$rl1Allowed,$rl2Allowed,$rl3Allowed,$rl4Allowed,$rl5Allowed,$rl6Allowed,$rl7Allowed")
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
        accounts[request.accountName] = Account(
            request.serviceName,
            request.accountName,
            request.callbackPath,
            speedLimits = SpeedLimits(request.slo.tps, request.slo.win),
            slo = Slo(request.slo.upperLimitInvocationMillis),
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
        val tps: Int = 5,
        val win: Int = 15,
    )

    data class Account(
        val serviceName: String,
        val accountName: String,
        val callbackPath: String?,
        val slo: Slo = Slo(),
        val exposedAverageProcessingTime: Duration = Duration.ofMillis(slo.upperLimitInvocationMillis / 2),
        val speedLimits: SpeedLimits,
        val network: Network = Network(),
        val price: Int,
        val rateLimiter: RateLimiter = FixedWindowRateLimiter(speedLimits.rps, 1, TimeUnit.SECONDS),
        val window: SemaphoreOngoingWindow = SemaphoreOngoingWindow(speedLimits.win),
    )

    data class SpeedLimits(
        val rps: Int = 5,
        val win: Int = 15,
    )

    data class Network(
        val noiseLowerBoundMillis: Long = 0,
        val noiseUpperBoundMillis: Long = 10,
    )

    data class Slo(
        val upperLimitInvocationMillis: Long = 10_000,
        val accountBlockingProbability: Double = 0.0,
        val accountBlockingMaxTime: Duration = Duration.ofMillis(1000),
        val timeLimitsBreachingProbability: Double = 0.0,
        val timeLimitsBreachingMinTime: Duration = Duration.ofMillis(0),
        val timeLimitsBreachingMaxTime: Duration = Duration.ofMillis(1000),
        val errorResponseProbability: Double = -1.0,
    )

    @PutMapping("/process/bulk")
    suspend fun processBulk(
        @RequestBody request: BulkRequest,
        @RequestParam timeout: Duration?,
    ): ResponseEntity<BulkResponse> {
        val (code, resp) = try {
            withTimeout(timeout?.toMillis() ?: defaultTimeout) {
                processInternal(request)
            }
        } catch (e: TimeoutCancellationException) {
            return ResponseEntity.status(REQUEST_TIMEOUT).body(request.failBulk("Timeout"))
        }

        return ResponseEntity.status(code).body(resp)
    }

    @PostMapping("/process")
    suspend fun process(
        @RequestParam serviceName: String,
        @RequestParam accountName: String,
        @RequestParam transactionId: String,
        @RequestParam paymentId: String,
        @RequestParam amount: Int,
        @RequestParam timeout: Duration?,
    ): ResponseEntity<Response> {
        val (code, bulkResp) = try {
            withTimeout(timeout?.toMillis() ?: defaultTimeout) {
                processInternal(
                    BulkRequest(
                        serviceName,
                        accountName,
                        listOf(Request(transactionId, paymentId, amount))
                    )
                )
            }
        } catch (e: TimeoutCancellationException) {
            return ResponseEntity.status(REQUEST_TIMEOUT).body(Response(transactionId, paymentId, false, "Timeout"))
        }

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

        Metrics
            .withTags(Metrics.serviceLabel to serviceName, "accountName" to accountName)
            .externalSysChargeAmountRecord(account.price * bulk.requests.size)


        // if we perform blocking logic before RL acquisition, we will non-intentionally break the speed limits
        if (!account.rateLimiter.tick()) {
            performBlockingLogic(account)
            networkLatency(account)

            PromMetrics.externalSysDurationRecord(
                serviceName,
                accountName,
                "rate_limit_breached",
                System.currentTimeMillis() - start
            )
            return TOO_MANY_REQUESTS to bulk.failBulk("Rate limit for account: $accountName breached")
        }

        try {
            if (account.window.tryAcquire()) {
                performBlockingLogic(account)

                val duration = if (Random.nextDouble(0.0, 1.0) < account.slo.timeLimitsBreachingProbability) {
                    Random.nextLong(account.slo.timeLimitsBreachingMinTime.toMillis(), account.slo.timeLimitsBreachingMaxTime.toMillis())
                } else Random.nextLong(0, account.slo.upperLimitInvocationMillis)

                delay(duration)

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
                                                PaymentStatus.SUCCESS, it.amount, UUID.fromString(it.paymentId)
                                            )
                                        )
                                    }
                                }
                            } catch (ignored: TimeoutCancellationException) {
                            }
                        }
                    }

                    logger.info("[external] - Transaction ${it.transactionId}. Duration: $duration")
                    Response(it.transactionId, it.paymentId, result)
                }.toBulkResponse()


                return (OK to resp).also {
                    networkLatency(account)

                    PromMetrics.externalSysDurationRecord(
                        serviceName,
                        accountName,
                        "SUCCESS",
                        System.currentTimeMillis() - start
                    )
                }
            } else {
                performBlockingLogic(account)
                networkLatency(account) // todo sukhoa once for the batch
                PromMetrics.externalSysDurationRecord(
                    serviceName,
                    accountName,
                    "parallel_requests_limit_breached",
                    System.currentTimeMillis() - start
                )
                return BANDWIDTH_LIMIT_EXCEEDED to bulk.failBulk("Parallel requests limit for account: $accountName breached. Already ${account.window.maxWinSize} executing")
            }
        } catch (e: Throwable) {
            logger.trace("Unexpected error:", e) // global jetty timeout for example or cancellation exception
            networkLatency(account)
            PromMetrics.externalSysDurationRecord(
                serviceName,
                accountName,
                if (e is TimeoutCancellationException) "CLIENT_DEADLINE_EXCEEDED" else "UNEXPECTED_ERROR",
                System.currentTimeMillis() - start
            )
            throw e
        } finally {
            account.window.release()
        }
    }

    private suspend fun performBlockingLogic(account: Account) { //todo sukhoa extension of the blocklist
        val accountName = account.accountName

        if (Random.nextDouble(0.0, 1.0) < account.slo.accountBlockingProbability) {
            blockList[accountName] =
                System.currentTimeMillis() + Random.nextLong(account.slo.accountBlockingMaxTime.toMillis())
        }

        val blockUntil = blockList[accountName]
        if (blockUntil != null) {
            delay(blockUntil - System.currentTimeMillis())
            blockList.remove(accountName)
        }
    }

    suspend fun networkLatency(account: Account) {
        if (account.network.noiseUpperBoundMillis == 0L) return
        return delay(
            Random.nextLong(
                account.network.noiseLowerBoundMillis,
                account.network.noiseUpperBoundMillis
            )
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
        val amount: Int,
    )

    data class Response(
        val transactionId: String,
        val paymentId: String,
        val result: Boolean,
        val message: String? = null,
    )
}