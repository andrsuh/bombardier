package com.itmo.microservices.bombardierCore.bombardier.stages

import com.itmo.microservices.commonlib.annotations.InjectEventLogger
import com.itmo.microservices.commonlib.logging.EventLogger
import com.itmo.microservices.bombardierCore.bombardier.logging.OrderCreationNotableEvents.*
import com.itmo.microservices.bombardierCore.bombardier.external.ExternalServiceApi
import com.itmo.microservices.bombardierCore.bombardier.flow.UserManagement
import org.springframework.stereotype.Component

@Component
class OrderCreationStage : TestStage {
    @InjectEventLogger
    private lateinit var eventLogger: EventLogger

    override suspend fun run(userManagement: UserManagement, externalServiceApi: ExternalServiceApi): TestStage.TestContinuationType {
        val order = externalServiceApi.createOrder(testCtx().userId!!)
        eventLogger.info(I_ORDER_CREATED, order.id)
        testCtx().orderId = order.id
        return TestStage.TestContinuationType.CONTINUE
    }
}