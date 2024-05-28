package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.demo.common.logging.lib.annotations.InjectEventLogger
import com.itmo.microservices.demo.bombardier.external.PaymentStatus
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.external.OrderStatus
import com.itmo.microservices.demo.bombardier.flow.*
import com.itmo.microservices.demo.bombardier.logging.OrderCommonNotableEvents
import com.itmo.microservices.demo.bombardier.logging.OrderPaymentNotableEvents.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import com.itmo.microservices.demo.common.logging.EventLoggerWrapper
import com.itmo.microservices.demo.common.logging.lib.logging.EventLogger
import com.itmo.microservices.demo.common.metrics.Metrics
import io.micrometer.core.instrument.util.NamedThreadFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Component
class OrderPaymentStage : TestStage {
    companion object {
        const val paymentOutcome = "outcome"
        const val paymentFailureReason = "failReason"
    }

    @InjectEventLogger
    lateinit var eventLog: EventLogger

    lateinit var eventLogger: EventLoggerWrapper

    override suspend fun run(
        userManagement: UserManagement,
        externalServiceApi: ExternalServiceApi
    ): TestStage.TestContinuationType {
        eventLogger = EventLoggerWrapper(eventLog, testCtx().serviceName)

        var order = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)

        val paymentDetails = testCtx().paymentDetails
        paymentDetails.attempt++

        eventLogger.info(I_PAYMENT_STARTED, order, paymentDetails.attempt)

        val paymentSubmissionDto = externalServiceApi.payOrder(
            testCtx().userId!!,
            testCtx().orderId!!
        )

        eventLog.info(I_STARTED_PAYMENT, testCtx().orderId!!, paymentSubmissionDto.timestamp, paymentSubmissionDto.transactionId)

        val paymentSubmissionTimeout = 20L
        ConditionAwaiter.awaitAtMost(paymentSubmissionTimeout, TimeUnit.SECONDS, Duration.ofSeconds(15))
            .condition {
                order = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!)
                order.status == OrderStatus.OrderPaymentInProgress || order.status == OrderStatus.OrderPayed || order.status == OrderStatus.OrderPaymentFailed
            }
            .onFailure {
                eventLogger.error(E_SUBMISSION_TIMEOUT_EXCEEDED, order.id, paymentSubmissionTimeout)
                if (it != null) {
                    throw it
                }
                Metrics
                    .withTags(Metrics.serviceLabel to testCtx().serviceName, paymentOutcome to "FAIL", paymentFailureReason to "SUBMIT_TIMEOUT")
                    .paymentFinished()
                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }.startWaiting()

        val startWaitingPayment = System.currentTimeMillis()
        eventLog.info(I_START_WAITING_FOR_PAYMENT_RESULT, testCtx().orderId!!, paymentSubmissionDto.transactionId, startWaitingPayment - paymentSubmissionDto.timestamp)


        val awaitingTime = 80L + 25L

        ConditionAwaiter.awaitAtMost(awaitingTime, TimeUnit.SECONDS, Duration.ofSeconds(30))
            .condition {
                externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!).paymentHistory
                    .any { it.transactionId == paymentSubmissionDto.transactionId }
            }
            .onFailure {
                eventLogger.error(E_PAYMENT_NO_OUTCOME_FOUND, order.id)
                if (it != null) {
                    throw it
                }
                Metrics
                    .withTags(Metrics.serviceLabel to testCtx().serviceName, paymentOutcome to "FAIL", paymentFailureReason to "NO_OUTCOME")
                    .paymentFinished()

                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }.startWaiting()

        val paymentLogRecord = externalServiceApi.getOrder(testCtx().userId!!, testCtx().orderId!!).paymentHistory
            .find { it.transactionId == paymentSubmissionDto.transactionId }!!

        val paymentTimeout = 80L
        when (val status = paymentLogRecord.status) {
            PaymentStatus.SUCCESS -> {
                if (paymentLogRecord.timestamp - paymentSubmissionDto.timestamp > Duration.ofSeconds(paymentTimeout).toMillis()) {
                    eventLogger.error(E_PAYMENT_TIMEOUT_EXCEEDED, order.id, paymentTimeout, Duration.ofMillis(paymentLogRecord.timestamp - paymentSubmissionDto.timestamp))
                    Metrics
                        .withTags(Metrics.serviceLabel to testCtx().serviceName, paymentOutcome to "FAIL", paymentFailureReason to "TIMEOUT")
                        .paymentFinished()
                    return TestStage.TestContinuationType.FAIL
                }

//                ConditionAwaiter.awaitAtMost(10, TimeUnit.SECONDS)
//                    .condition {
//                        val userChargedRecord =
//                            externalServiceApi.userFinancialHistory(testCtx().userId!!, testCtx().orderId!!)
//                                .find { it.paymentId == paymentSubmissionDto.transactionId }
//
//                        userChargedRecord?.type == FinancialOperationType.WITHDRAW
//                    }
//                    .onFailure {
//                        eventLogger.error(E_WITHDRAW_NOT_FOUND, order.id, testCtx().userId)
//                        if (it != null) {
//                            throw it
//                        }
//                        Metrics
//                            .withTags(Metrics.serviceLabel to testCtx().serviceName, paymentOutcome to "FAIL", paymentFailureReason to "WITHDRAW_NOT_FOUND")
//                            .paymentFinished()
//                        throw TestStage.TestStageFailedException("Exception instead of silently fail")
//                    }.startWaiting()

                Metrics
                    .withTags(Metrics.serviceLabel to testCtx().serviceName)
                    .paymentsAmountRecord(paymentLogRecord.amount)

                Metrics
                    .withTags(Metrics.serviceLabel to testCtx().serviceName, paymentOutcome to "SUCCESS", paymentFailureReason to "")
                    .paymentFinished()

//                paymentDetails.finishedAt = System.currentTimeMillis()
                eventLogger.info(I_PAYMENT_SUCCESS, order.id, paymentSubmissionDto.transactionId, paymentLogRecord.timestamp - paymentSubmissionDto.timestamp)

                return TestStage.TestContinuationType.CONTINUE
            }
            PaymentStatus.FAILED -> { // todo sukhoa check order status hasn't changed and user ne charged
//                if (paymentDetails.attempt < 3) {
//                    eventLogger.info(I_PAYMENT_RETRY, order.id, paymentDetails.attempt)
//                    Metrics
//                        .withTags(Metrics.serviceLabel to testCtx().serviceName, paymentOutcome to "RETRY", paymentFailureReason to "SHOP_REJECTED")
//                        .paymentFinished()
//
//                    return TestStage.TestContinuationType.RETRY
//                } else {
                    eventLogger.error(E_PAYMENT_FAILED, order.id, paymentSubmissionDto.transactionId, paymentLogRecord.timestamp - paymentSubmissionDto.timestamp)
//                    paymentDetails.failedAt = System.currentTimeMillis()
                    Metrics
                        .withTags(Metrics.serviceLabel to testCtx().serviceName, paymentOutcome to "FAIL", paymentFailureReason to "SHOP_REJECTED")
                        .paymentFinished()
                    return TestStage.TestContinuationType.FAIL
//                }
            } // todo sukhoa not enough money
            else -> {
                eventLogger.error(
                    OrderCommonNotableEvents.E_ILLEGAL_ORDER_TRANSITION,
                    order.id, order.status, status
                )
                Metrics
                    .withTags(Metrics.serviceLabel to testCtx().serviceName, paymentOutcome to "FAIL", paymentFailureReason to "UNEXPECTED")
                    .paymentFinished()
                return TestStage.TestContinuationType.FAIL
            }
        }
    }
}