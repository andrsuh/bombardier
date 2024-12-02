package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.demo.common.logging.lib.annotations.InjectEventLogger
import com.itmo.microservices.demo.common.logging.lib.logging.EventLogger
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.external.OrderStatus
import com.itmo.microservices.demo.bombardier.flow.TestContext
import com.itmo.microservices.demo.bombardier.flow.TestImmutableInfo
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import com.itmo.microservices.demo.bombardier.logging.OrderSettingsDeliveryNotableEvents.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import com.itmo.microservices.demo.common.logging.EventLoggerWrapper
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class OrderSettingDeliverySlotsStage : TestStage {
    @InjectEventLogger
    lateinit var eventLog: EventLogger

    lateinit var eventLogger: EventLoggerWrapper

    override suspend fun run(
        testCtx: TestContext,
        userManagement: UserManagement,
        externalServiceApi: ExternalServiceApi
    ): TestStage.TestContinuationType {
        eventLogger = EventLoggerWrapper(eventLog, testCtx.serviceName)

        eventLogger.info(I_CHOOSE_SLOT, testCtx.orderId)


        val availableSlots = externalServiceApi.getDeliverySlots(testCtx.userId!!)

        val deliverySlot = availableSlots.random()
        val deliveryId = externalServiceApi.setDeliveryTime(testCtx.userId!!, testCtx.orderId!!, deliverySlot)

        ConditionAwaiter.awaitAtMost(40, TimeUnit.SECONDS, Duration.ofSeconds(4)).condition {
            val order = externalServiceApi.getOrder(testCtx.userId!!, testCtx.orderId!!)

            order.deliveryId == deliveryId && order.status == OrderStatus.OrderDeliverySet
        }.onFailure {
            eventLogger.error(E_CHOOSE_SLOT_FAIL, deliveryId, testCtx.orderId!!)
            throw TestStage.TestStageFailedException("Exception instead of silently fail")
        }.startWaiting()

        eventLogger.info(I_CHOOSE_SLOT_SUCCESS, deliverySlot, testCtx.orderId)
        return TestStage.TestContinuationType.CONTINUE
    }
}