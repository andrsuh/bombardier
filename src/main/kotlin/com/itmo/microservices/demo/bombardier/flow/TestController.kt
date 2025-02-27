package com.itmo.microservices.demo.bombardier.flow

import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.bombardier.exception.BadRequestException
import com.itmo.microservices.demo.bombardier.external.knownServices.KnownServices
import com.itmo.microservices.demo.bombardier.external.knownServices.ServiceWithApiAndAdditional
import com.itmo.microservices.demo.bombardier.stages.*
import com.itmo.microservices.demo.bombardier.stages.TestStage.TestContinuationType.CONTINUE
import com.itmo.microservices.demo.common.AllAllowCompositeRateLimiter
import com.itmo.microservices.demo.common.RateLimiter
import com.itmo.microservices.demo.common.SinRateLimiter
import com.itmo.microservices.demo.common.SlowStartRateLimiter
import com.itmo.microservices.demo.common.logging.LoggerWrapper
import com.itmo.microservices.demo.common.metrics.Metrics
import com.itmo.microservices.demo.common.metrics.PromMetrics
import io.micrometer.core.instrument.util.NamedThreadFactory
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

@Service
class TestController(
    private val knownServices: KnownServices,
    val choosingUserAccountStage: ChoosingUserAccountStage,
    val orderCreationStage: OrderCreationStage,
    val orderPaymentStage: OrderPaymentStage,
) {
    companion object {
        val log = LoggerFactory.getLogger(TestController::class.java)
    }

    val runningTests = ConcurrentHashMap<String, TestingFlow>()

    private val executor: ExecutorService = Executors.newFixedThreadPool(32, NamedThreadFactory("test-controller-executor")).also {
        Metrics.executorServiceMonitoring(it, "test-controller-executor")
    }

//    private val testInvokationScope = CoroutineScope(executor.asCoroutineDispatcher())
    private val testLaunchScope = CoroutineScope(executor.asCoroutineDispatcher())
    private val rateLimitsTokensChannel = Channel<Int>(Channel.UNLIMITED)

//    private val testStages = listOf<TestStage>(
//        choosingUserAccountStage.asErrorFree().asMetricRecordable(),
//        orderCreationStage.asErrorFree().asMetricRecordable(),
//        orderCollectingStage.asErrorFree().asMetricRecordable(),
//        OrderAbandonedStage(serviceApi).asErrorFree(),
//        orderFinalizingStage.asErrorFree().asMetricRecordable(),
//        orderSettingDeliverySlotsStage.asErrorFree().asMetricRecordable(),
//        orderChangeItemsAfterFinalizationStage.asErrorFree(),
//        orderFinalizingStage.asErrorFree(),
//        orderSettingDeliverySlotsStage.asErrorFree(),
//        orderPaymentStage.asErrorFree().asMetricRecordable(),
//        orderDeliveryStage.asErrorFree()
//    )

//    private val testStage = mutableListOf<TestStage>().let {
//        it.add(choosingUserAccountStage.asErrorFree().asMetricRecordable())
//        it.add(orderCreationStage.asErrorFree().asMetricRecordable())
//        orderPaymentStage.asErrorFree().asMetricRecordable()
//    }


    fun startTestingForService(params: TestParameters) {
        val logger = LoggerWrapper(log, params.serviceName)

        val testingFlowCoroutine = SupervisorJob()

        val v = runningTests.putIfAbsent(params.serviceName, TestingFlow(params, testingFlowCoroutine))
        if (v != null) {
            throw BadRequestException("There is no such feature launch several flows for the service in parallel :(")
        }

        val descriptor = knownServices.descriptorFromName(params.serviceName)
        val stuff = knownServices.getStuff(params.serviceName)

        runBlocking {
            stuff.userManagement.createUsersPool(params.numberOfUsers)
        }

        logger.info("Launch coroutine for $descriptor")
        launchTestCycle(descriptor, stuff)
    }

    fun getTestingFlowForService(serviceName: String): TestingFlow {
        return runningTests[serviceName] ?: throw IllegalArgumentException("There is no running test for $serviceName")
    }

    suspend fun stopTestByServiceName(serviceName: String) {
        runningTests[serviceName]?.testFlowCoroutine?.cancelAndJoin()
            ?: throw BadRequestException("There is no running tests with serviceName = $serviceName")
        runningTests.remove(serviceName)
    }

    suspend fun stopAllTests() {
        runningTests.values.forEach {
            it.testFlowCoroutine.cancelAndJoin()
        }
        runningTests.clear()
    }

    class TestingFlow(
        val testParams: TestParameters,
        val testFlowCoroutine: CompletableJob,
        val testsStarted: AtomicInteger = AtomicInteger(1),
        val testsFinished: AtomicInteger = AtomicInteger(0)
    )

    private fun launchTestCycle(
        descriptor: ServiceDescriptor,
        stuff: ServiceWithApiAndAdditional,
    ) {
        val logger = LoggerWrapper(log, descriptor.name)
        val serviceName = descriptor.name
        val testingFlow = runningTests[serviceName] ?: return

        val params = testingFlow.testParams

        val amplitude = ((params.loadProfile.sinLoad?.amplitudeRatio ?: 0.0) * params.ratePerSecond).toInt()
        val mainLimiter =  SlowStartRateLimiter(params.ratePerSecond + amplitude, Duration.ofSeconds(1), slowStartOn = true)

        val rateLimitersList = mutableListOf<RateLimiter>()

        params.loadProfile.sinLoad?.let {
            rateLimitersList.add(
                SinRateLimiter(params.ratePerSecond, Duration.ofSeconds(1), amplitude, it.period.toSeconds().toInt())
            )
        }

        val rateLimiter = AllAllowCompositeRateLimiter(listOf(mainLimiter, *rateLimitersList.toTypedArray()))

        val testStages = mutableListOf<TestStage>().also {
            it.add(choosingUserAccountStage.asErrorFree().asMetricRecordable())
            it.add(orderCreationStage.asErrorFree().asMetricRecordable())
            if (!testingFlow.testParams.stopAfterOrderCreation) {
                it.add(orderPaymentStage.asErrorFree().asMetricRecordable())
            }
        }

        val testInfo = TestImmutableInfo(serviceName = serviceName, stopAfterOrderCreation = testingFlow.testParams.stopAfterOrderCreation)
//        for (i in 1..250_000) {
        val testContext = TestContext(
            serviceName = serviceName,
            launchTestsRatePerSec = testingFlow.testParams.ratePerSecond,
            totalTestsNumber = testingFlow.testParams.numberOfTests,
            paymentProcessingTimeMillis = testingFlow.testParams.paymentProcessingTimeMillis,
            variatePaymentProcessingTime = testingFlow.testParams.variatePaymentProcessingTime,
            testSuccessByThePaymentFact = testingFlow.testParams.testSuccessByThePaymentFact,
            testImmutableInfo = testInfo,
        )

        while (true) {
            val testNum = testingFlow.testsStarted.getAndIncrement()
            if (testNum > params.numberOfTests) {
                logger.error("Wrapping up test flow. Number of tests exceeded")
                runningTests.remove(serviceName)
                return
            }

            while (true) {
                if (rateLimiter.tick()) {
                    break
                }
                Thread.sleep(1000 - System.currentTimeMillis() % 1000)
            }

            testLaunchScope.launch(testContext) {
                testContext.testStartTime = System.currentTimeMillis()
                logger.info("Starting $testNum test for service $serviceName, parent job is ${testingFlow.testFlowCoroutine}")
                launchNewTestFlow(testContext, testingFlow, descriptor, stuff, testStages)
            }
        }
//        }
    }

    private suspend fun launchNewTestFlow(
        testInfo: TestContext,
        testingFlow: TestingFlow,
        descriptor: ServiceDescriptor,
        stuff: ServiceWithApiAndAdditional,
        testStages: MutableList<TestStage>
    ) {
        val logger = LoggerWrapper(log, descriptor.name)

        val testStartTime = System.currentTimeMillis()
//        testInvokationScope.launch(
//            testingFlow.testFlowCoroutine + TestContext(
//                serviceName = serviceName,
//                launchTestsRatePerSec = testingFlow.testParams.ratePerSecond,
//                totalTestsNumber = testingFlow.testParams.numberOfTests,
//                testSuccessByThePaymentFact = testingFlow.testParams.testSuccessByThePaymentFact,
//                stopAfterOrderCreation = testingFlow.testParams.stopAfterOrderCreation
//            )
//        ) {
        try {
            testStages.forEach { stage ->
                val stageResult = stage.run(testInfo, stuff.userManagement, stuff.api)
                if (stageResult != CONTINUE) {
                    PromMetrics.testDurationRecord(
                        testInfo.serviceName,
                        stageResult.name,
                        System.currentTimeMillis() - testStartTime
                    )
                    return
                }
            }

            PromMetrics.testDurationRecord(testInfo.serviceName, "SUCCESS", System.currentTimeMillis() - testStartTime)
        } catch (th: Throwable) {
            logger.error("Unexpected fail in test", th)
            PromMetrics.testDurationRecord(testInfo.serviceName, "UNEXPECTED_FAIL", System.currentTimeMillis() - testStartTime)
            logger.info("Test ${testingFlow.testsFinished.incrementAndGet()} finished")
        }
//        }.invokeOnCompletion { th ->
//            if (th != null) {
//                logger.error("Unexpected fail in test", th)
//
//                Metrics
//                    .withTags(Metrics.serviceLabel to serviceName, "testOutcome" to "UNEXPECTED_FAIL")
//                    .testDurationRecord(System.currentTimeMillis() - testStartTime)
//            }
//
//            logger.info("Test ${testingFlow.testsFinished.incrementAndGet()} finished")
//        }
    }
}

object TestCtxKey : CoroutineContext.Key<TestContext>

data class TestContext(
    val testId: UUID = UUID.randomUUID(),
    val serviceName: String,
    var userId: UUID? = null,
    var orderId: UUID? = null,
    var paymentDetails: PaymentDetails = PaymentDetails(),
    var stagesComplete: MutableList<String> = mutableListOf(),
    var wasChangedAfterFinalization: Boolean = false,
    var launchTestsRatePerSec: Int,
    var paymentProcessingTimeMillis: Long,
    val variatePaymentProcessingTime: Boolean,
    var totalTestsNumber: Int,
    val testSuccessByThePaymentFact: Boolean = false,
    val stopAfterOrderCreation: Boolean = false,
    var testStartTime: Long = System.currentTimeMillis(),
    val testImmutableInfo: TestImmutableInfo,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<TestContext>
        get() = TestCtxKey

//    fun finalizationNeeded() = OrderFinalizingStage::class.java.simpleName !in stagesComplete ||
//        (OrderChangeItemsAfterFinalizationStage::class.java.simpleName in stagesComplete
//            && wasChangedAfterFinalization)
}

// create separate object for immutable data to avoid suspensions during obtaining the coroutine context
data class TestImmutableInfo(
    val testId: UUID = UUID.randomUUID(),
    val serviceName: String,
    val stopAfterOrderCreation: Boolean = false,
    val testStartTime: Long = System.currentTimeMillis(),
) {
    lateinit var userId: UUID
}


data class PaymentDetails(
//    var startedAt: Long? = null,
//    var failedAt: Long? = null,
//    var finishedAt: Long? = null,
    var attempt: Int = 0,
//    var amount: Int? = null,
)

data class TestParameters(
    val serviceName: String,
    val numberOfUsers: Int = 1,
    val numberOfTests: Int = 100,
    val ratePerSecond: Int = 10,
    val testSuccessByThePaymentFact: Boolean = true, // todo sukhoa effectively it is always true as we don't analyse it on payment order stage
    val stopAfterOrderCreation: Boolean = false,
    val paymentProcessingTimeMillis: Long = 1000,
    val variatePaymentProcessingTime: Boolean = false,
    val loadProfile: LoadProfile,
)

data class LoadProfile(
    val ratePerSecond: Int = 10,
    val sinLoad: SinLoad? = null,
)

data class SinLoad(
    val amplitudeRatio: Double,
    val period: Duration,
)