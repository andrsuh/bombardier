package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.demo.common.logging.lib.annotations.InjectEventLogger
import com.itmo.microservices.demo.common.logging.lib.logging.EventLogger
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.flow.TestContext
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import com.itmo.microservices.demo.bombardier.logging.OrderCollectingNotableEvents.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import com.itmo.microservices.demo.common.logging.EventLoggerWrapper
import org.springframework.stereotype.Component
import java.time.Duration.ofSeconds
import java.util.*
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.random.Random

@Component
class OrderCollectingStage : TestStage {
    @InjectEventLogger
    private lateinit var eventLog: EventLogger

    lateinit var eventLogger: EventLoggerWrapper

    override suspend fun run(
        testCtx: TestContext,
        userManagement: UserManagement,
        externalServiceApi: ExternalServiceApi
    ): TestStage.TestContinuationType {
        eventLogger = EventLoggerWrapper(eventLog, testCtx.serviceName)

        eventLogger.info(I_ADDING_ITEMS, testCtx.orderId)

        val itemIds = mutableMapOf<UUID, Int>()
        val items = externalServiceApi.getAvailableItems(testCtx.userId!!)
        repeat(Random.nextInt(1, 3)) {
            val amount = Random.nextInt(1, 5)
            val itemToAdd = items.random()
                .also { // todo should not to do on each addition but! we can randomise it
                    itemIds[it.id] = amount
                }

            externalServiceApi.putItemToOrder(testCtx.userId!!, testCtx.orderId!!, itemToAdd.id, amount)
        }

//        val orderMap = finalOrder.itemsMap.mapKeys { it.key }

        ConditionAwaiter.awaitAtMost(30, SECONDS, ofSeconds(5))
            .condition {
                val orderMap = externalServiceApi.getOrder(testCtx.userId!!, testCtx.orderId!!).itemsMap.mapKeys { it.key }

                itemIds.all { (id, count) ->
                    orderMap.containsKey(id) && orderMap[id] == count
                }
            }.onFailure {
                eventLogger.error(E_ADD_ITEMS_FAIL, testCtx.orderId)
                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }.startWaiting()

//        itemIds.forEach { (id, count) ->
//            if (!orderMap.containsKey(id)) {
//                eventLogger.error(E_ADD_ITEMS_FAIL, id, count, 0)
//                return TestStage.TestContinuationType.FAIL
//            }
//            if (orderMap[id] != count) {
//                eventLogger.error(E_ADD_ITEMS_FAIL, id, count, orderMap[id])
//                return TestStage.TestContinuationType.FAIL
//            }
//        }

        val finalOrder = externalServiceApi.getOrder(testCtx.userId!!, testCtx.orderId!!)
        val finalNumOfItems = finalOrder.itemsMap.size
        if (finalNumOfItems != itemIds.size) {
            eventLogger.error(E_ITEMS_MISMATCH, finalNumOfItems, itemIds.size)
            return TestStage.TestContinuationType.FAIL

        }

        eventLogger.info(I_ORDER_COLLECTING_SUCCESS, itemIds.size, testCtx.orderId)
        return TestStage.TestContinuationType.CONTINUE
    }

}