package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.commonlib.annotations.InjectEventLogger
import com.itmo.microservices.commonlib.logging.EventLogger
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.external.OrderStatus
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import com.itmo.microservices.demo.bombardier.logging.OrderSettingsDeliveryNotableEvents.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import com.itmo.microservices.demo.common.logging.EventLoggerWrapper
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@Component
class OrderSettingDeliverySlotsStage : TestStage {
    @InjectEventLogger
    lateinit var eventLog: EventLogger

    lateinit var eventLogger: EventLoggerWrapper

    override suspend fun run(
        userManagement: UserManagement,
        externalServiceApi: ExternalServiceApi
    ): TestStage.TestContinuationType {
        eventLogger = EventLoggerWrapper(eventLog, testCtx().serviceName)

        eventLogger.info(I_CHOOSE_SLOT, testCtx().orderId)


        val availableSlots = externalServiceApi.getDeliverySlots(
            testCtx().userId!!,
            10
        ) // TODO: might be a better idea to provide different number here

        var deliverySlot = Duration.ZERO
        repeat(Random.nextInt(1, 4)) {
            deliverySlot = availableSlots.random()
            if (deliverySlot > Duration.ofSeconds(30)) {
                deliverySlot = Duration.ofSeconds(Random.nextLong(1, 30))
            }
            val deliveryId = externalServiceApi.setDeliveryTime(testCtx().userId!!, testCtx().orderId!!, deliverySlot)

            ConditionAwaiter.awaitAtMost(16, TimeUnit.SECONDS, Duration.ofSeconds(2)).condition {
                val order = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)
                val deliveryIdInOrder = order.deliveryId
                deliveryIdInOrder == deliveryId && order.status == OrderStatus.OrderDeliverySet
            }.onFailure {
                eventLogger.error(E_CHOOSE_SLOT_FAIL, deliveryId, "?")
//                TestStage.TestContinuationType.FAIL
                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }.startWaiting()
        }

        eventLogger.info(I_CHOOSE_SLOT_SUCCESS, deliverySlot.seconds, testCtx().orderId)
        return TestStage.TestContinuationType.CONTINUE
    }
}