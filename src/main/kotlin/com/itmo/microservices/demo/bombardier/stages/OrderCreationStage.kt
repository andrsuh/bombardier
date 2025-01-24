package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.flow.TestContext
import com.itmo.microservices.demo.bombardier.flow.TestImmutableInfo
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import com.itmo.microservices.demo.bombardier.logging.OrderCreationNotableEvents.I_ORDER_CREATED
import com.itmo.microservices.demo.common.logging.EventLoggerWrapper
import com.itmo.microservices.demo.common.logging.lib.annotations.InjectEventLogger
import com.itmo.microservices.demo.common.logging.lib.logging.EventLogger
import org.springframework.stereotype.Component
import kotlin.random.Random

@Component
class OrderCreationStage : TestStage {
    @InjectEventLogger
    lateinit var eventLog: EventLogger

    lateinit var eventLogger: EventLoggerWrapper

    override suspend fun run(
        testCtx: TestContext,
        userManagement: UserManagement,
        externalServiceApi: ExternalServiceApi
    ): TestStage.TestContinuationType {
        eventLogger = EventLoggerWrapper(eventLog, testCtx.serviceName)

        val price = Random.nextInt(60, 750)

        val order = externalServiceApi.createOrder(testCtx.testImmutableInfo.userId, price)
        eventLogger.info(I_ORDER_CREATED, order.id)
        testCtx.orderId = order.id

        return TestStage.TestContinuationType.CONTINUE
    }
}