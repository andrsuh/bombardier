package com.itmo.microservices.demo.bombardier.stages

import com.itmo.microservices.demo.common.logging.lib.annotations.InjectEventLogger
import com.itmo.microservices.demo.common.logging.lib.logging.EventLogger
import com.itmo.microservices.demo.bombardier.external.BookingStatus
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.external.OrderStatus
import com.itmo.microservices.demo.bombardier.flow.TestContext
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import com.itmo.microservices.demo.bombardier.logging.OrderCommonNotableEvents
import com.itmo.microservices.demo.bombardier.logging.OrderFinaizingNotableEvents.*
import com.itmo.microservices.demo.bombardier.utils.ConditionAwaiter
import com.itmo.microservices.demo.common.logging.EventLoggerWrapper
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

@Component
class OrderFinalizingStage : TestStage {
    @InjectEventLogger
    lateinit var eventLog: EventLogger

    lateinit var eventLogger: EventLoggerWrapper


    override suspend fun run(
        testCtx: TestContext,
        userManagement: UserManagement,
        externalServiceApi: ExternalServiceApi
    ): TestStage.TestContinuationType {
        eventLogger = EventLoggerWrapper(eventLog, testCtx.serviceName)

        eventLogger.info(I_START_FINALIZING, testCtx.orderId)

// todo sukhoa: commenting this out to avoid suspension during adding the stage into the list of "finished". The suspension is a way expensive there
//        if (!testCtx.finalizationNeeded()) {
//            eventLogger.info(I_NO_FINALIZING_REQUIRED, testCtx.orderId)
//            return TestStage.TestContinuationType.CONTINUE
//        }

        val orderStateBeforeFinalizing = externalServiceApi.getOrder(testCtx.userId!!, testCtx.orderId!!)

        val bookingResult = externalServiceApi.bookOrder(testCtx.userId!!, testCtx.orderId!!)

        var orderStateAfterBooking = externalServiceApi.getOrder(testCtx.userId!!, testCtx.orderId!!)

        val awaitingTime = when(testCtx.launchTestsRatePerSec) { // todo sukhoa refactor
            in (0..10) -> 80L
            in (11..40) -> 180L
            else -> testCtx.launchTestsRatePerSec * 500L
        }

        ConditionAwaiter.awaitAtMost(awaitingTime, TimeUnit.SECONDS, Duration.ofSeconds(10))
            .condition {
                val bookingRecords = externalServiceApi.getBookingHistory(testCtx.userId!!, bookingResult.id)
                val booked = bookingRecords.map { it.itemId }.toSet()

                orderStateAfterBooking.itemsMap.keys.all { it in booked }
            }.onFailure {
                eventLogger.error(E_BOOKING_LOG_RECORD_NOT_FOUND, bookingResult.id, testCtx.orderId)
                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }.startWaiting()

        ConditionAwaiter.awaitAtMost(awaitingTime, TimeUnit.SECONDS, Duration.ofSeconds(10))
            .condition {
                orderStateAfterBooking.status == OrderStatus.OrderBooked.also {
                    orderStateAfterBooking = externalServiceApi.getOrder(testCtx.userId!!, testCtx.orderId!!)
                }
            }.onFailure {
                if (orderStateAfterBooking.status == OrderStatus.OrderBooked) return@onFailure

                eventLogger.error(
                    OrderCommonNotableEvents.E_BOOKING_STILL_IN_PROGRESS,
                    orderStateAfterBooking.id,
                    orderStateAfterBooking.status.javaClass.simpleName,
                )
                throw TestStage.TestStageFailedException("Exception instead of silently fail")
            }.startWaiting()

        val bookingRecords = externalServiceApi.getBookingHistory(testCtx.userId!!, bookingResult.id)

        when (orderStateAfterBooking.status) {
            OrderStatus.OrderBooked -> {
                if (bookingResult.failedItems.isNotEmpty()) {
                    eventLogger.error(E_ORDER_HAS_FAIL_ITEMS, testCtx.orderId)
                    return TestStage.TestContinuationType.FAIL
                }

                for (id in orderStateAfterBooking.itemsMap.keys) {
                    val itemRecord = bookingRecords.firstOrNull { it.itemId == id }
                    if (itemRecord == null || itemRecord.status != BookingStatus.SUCCESS) {
                        eventLogger.error(
                            E_ITEMS_FAIL,
                            bookingResult.id,
                            testCtx.orderId,
                            id,
                            itemRecord?.status
                        )
                        return TestStage.TestContinuationType.FAIL
                    }
                }
                eventLogger.info(I_SUCCESS_VALIDATE_BOOKED, testCtx.orderId)
            }

            OrderStatus.OrderCollecting -> {
                if (bookingResult.failedItems.isEmpty()) {
                    eventLogger.error(E_BOOKING_FAIL_BUT_ITEMS_SUCCESS, testCtx.orderId, bookingResult.id)
                    return TestStage.TestContinuationType.FAIL
                }

                val failed = bookingRecords
                    .filter { it.status != BookingStatus.SUCCESS }
                    .map { it.itemId }
                    .toSet()

                if (failed != bookingResult.failedItems) {
                    eventLogger.error(E_LIST_FAILED_ITEMS_MISMATCH, bookingResult.failedItems, failed)
                    return TestStage.TestContinuationType.FAIL
                }

                val failedList = orderStateAfterBooking.itemsMap.filter { it.key in failed }
                    .map { (it.key to it.value) }

                eventLogger.info(I_SUCCESS_VALIDATE_NOT_BOOKED, testCtx.orderId, failedList)
                return TestStage.TestContinuationType.STOP
            }

            else -> {
                eventLogger.error(
                    OrderCommonNotableEvents.E_ILLEGAL_ORDER_TRANSITION,
                    orderStateAfterBooking.id,
                    orderStateBeforeFinalizing.status,
                    orderStateAfterBooking.status
                )

                return TestStage.TestContinuationType.FAIL
            }
        }

        return TestStage.TestContinuationType.CONTINUE
    }
}