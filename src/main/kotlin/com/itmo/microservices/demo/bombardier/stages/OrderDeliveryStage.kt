package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.demo.bombardier.flow.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import java.util.concurrent.TimeUnit

class OrderDeliveryStage(
    private val serviceApi: ServiceApi
) : TestStage {
    companion object {
        val log = CoroutineLoggingFactory.getLogger(OrderPaymentStage::class.java)
    }

    override suspend fun run(): TestStage.TestContinuationType {
        val orderBeforeDelivery = serviceApi.getOrder(testCtx().orderId!!)

        if (orderBeforeDelivery.status !is OrderStatus.OrderInDelivery) {
            log.error("Incorrect order ${orderBeforeDelivery.id} status for OrderDeliveryStage ${orderBeforeDelivery.status}")
            return TestStage.TestContinuationType.FAIL
        }

        serviceApi.simulateDelivery(testCtx().orderId!!)

        val lastDeliveryTimeInMillis = (orderBeforeDelivery.status.deliveryStartTime + orderBeforeDelivery.deliveryDuration!!) * 1000
        ConditionAwaiter.awaitAtMost(lastDeliveryTimeInMillis - System.currentTimeMillis(), TimeUnit.MILLISECONDS)
            .condition {
                val updatedOrder = serviceApi.getOrder(testCtx().orderId!!)
                updatedOrder.status != orderBeforeDelivery.status || serviceApi.userFinancialHistory(
                    testCtx().userId!!,
                    testCtx().orderId!!
                ).firstOrNull { it.type == FinancialOperationType.REFUND } != null
            }
            .onFailure {
                log.error("Order status of order ${orderBeforeDelivery.id} not changed and no refund")
                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }
        val orderAfterDelivery = serviceApi.getOrder(testCtx().orderId!!)
        when (orderAfterDelivery.status) {
            is OrderStatus.OrderDelivered -> {
                val deliveryLog = serviceApi.deliveryLog(testCtx().orderId!!)
                if (deliveryLog.outcome != DeliverySubmissionOutcome.SUCCESS) {
                    log.error("Delivery log for order ${orderAfterDelivery.id} is not DeliverySubmissionOutcome.SUCCESS")
                    return TestStage.TestContinuationType.FAIL
                }
                log.info("Order ${orderAfterDelivery.id} was successfully delivered")
            }
            is OrderStatus.OrderFailed -> {
                val userFinancialHistory = serviceApi.userFinancialHistory(testCtx().userId!!, testCtx().orderId!!)
                if (userFinancialHistory.filter { it.type == FinancialOperationType.WITHDRAW }.sumOf { it.amount } !=
                    userFinancialHistory.filter { it.type == FinancialOperationType.REFUND }.sumOf { it.amount }) {
                    log.error("Withdraw and refund amount are different for order ${orderAfterDelivery.id}, " +
                            "withdraw = ${userFinancialHistory.filter { it.type == FinancialOperationType.WITHDRAW }.map { it.amount }.sum()}, " +
                            "refund = ${userFinancialHistory.filter { it.type == FinancialOperationType.REFUND }.sumOf { it.amount }}")
                }
                log.info("Refund for order ${orderAfterDelivery.id} is correct")
            }
            else -> {
                log.error(
                    "Illegal transition for order ${orderBeforeDelivery.id} from ${orderBeforeDelivery.status} " +
                            "to ${orderAfterDelivery.status}"
                )
                return TestStage.TestContinuationType.FAIL
            }
        }
        return TestStage.TestContinuationType.CONTINUE
    }
}