package com.itmo.microservices.demo.bombardier.external

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.KeyDeserializer
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.itmo.microservices.demo.bombardier.external.OrderStatus.OrderCollecting
import java.util.*


interface ExternalServiceApi {
    suspend fun getUser(id: UUID): User
    suspend fun createUser(name: String): User

    suspend fun userFinancialHistory(userId: UUID) = userFinancialHistory(userId, null)
    suspend fun userFinancialHistory(userId: UUID, orderId: UUID?): List<UserAccountFinancialLogRecord>

    suspend fun createOrder(userId: UUID, price: Int): Order

    //suspend fun getOrders(userId: UUID): List<Order>
    suspend fun getOrder(userId: UUID, orderId: UUID): Order

    suspend fun getItems(userId: UUID, available: Boolean): List<CatalogItem>
    suspend fun getAvailableItems(userId: UUID) = getItems(userId, true)

    suspend fun putItemToOrder(
        userId: UUID,
        orderId: UUID,
        itemId: UUID,
        amount: Int
    ): Boolean // todo sukhoa consider using add instead of put

    suspend fun bookOrder(userId: UUID, orderId: UUID): BookingDto //синхронный
    suspend fun getDeliverySlots(
        userId: UUID
    ): List<Long>

    suspend fun setDeliveryTime(userId: UUID, orderId: UUID, slot: Long): UUID
    suspend fun payOrder(userId: UUID, orderId: UUID, deadline: Long): PaymentSubmissionDto

    suspend fun simulateDelivery(userId: UUID, orderId: UUID)

    suspend fun abandonedCardHistory(orderId: UUID): List<AbandonedCardLogRecord>

    suspend fun getBookingHistory(userId: UUID, bookingId: UUID): List<BookingLogRecord>
    //suspend fun getBookingHistory(orderId: UUID): List<BookingLogRecord>

    suspend fun deliveryLog(userId: UUID, orderId: UUID): List<DeliveryInfoRecord>
}

class DeliveryInfoRecord(
    val outcome: DeliverySubmissionOutcome,
    val preparedTime: Long,
    val attempts: Int = 0,
    val submittedTime: Long? = null,
    val transactionId: UUID?,
    val submissionStartedTime: Long? = null,
)

enum class DeliverySubmissionOutcome {
    SUCCESS,
    FAILURE,
    EXPIRED
}


class PaymentSubmissionDto(
    val timestamp: Long,
    val transactionId: UUID
)

data class User(
    val id: UUID,
    val name: String
)

data class UserAccountFinancialLogRecord(
    val type: FinancialOperationType,
    val amount: Int,
    val orderId: UUID,
    val paymentId: UUID,
    val timestamp: Long
)

enum class FinancialOperationType {
    REFUND,
    WITHDRAW
}

data class BookingDto(
    val id: UUID,
    val failedItems: Set<UUID> = emptySet()
)

data class CatalogItem(
    val id: UUID,
    val title: String,
    val description: String,
    val price: Int = 100,
    val amount: Int, // number of items allowed for booking
)

data class OrderItem(
    val id: UUID,
    val title: String,
    val price: Int = 100
)

class BookingLogRecord(
    val bookingId: UUID,
    val itemId: UUID,
    val status: BookingStatus,
    val amount: Int,
    val timestamp: Long = System.currentTimeMillis(),
)

sealed class OrderStatus {
    object OrderCollecting : OrderStatus()
    object OrderDiscarded : OrderStatus()
    object OrderBooked : OrderStatus()
    object OrderBookingInProgress : OrderStatus()
    object OrderDeliverySet : OrderStatus()
    object OrderRefund : OrderStatus()
    object OrderPaymentFailed : OrderStatus()
    object OrderPaymentInProgress : OrderStatus()
    object OrderPayed : OrderStatus()
    class OrderInDelivery(val deliveryStartTime: Long) : OrderStatus()
    class OrderDelivered(val deliveryStartTime: Long, val deliveryFinishTime: Long) : OrderStatus()
    class OrderFailed(reason: String, previousStatus: OrderStatus) : OrderStatus()
}

class OrderStatusDeserializer : JsonDeserializer<OrderStatus>() {
    override fun deserialize(p0: JsonParser, p1: DeserializationContext): OrderStatus {
        return when (p0.text) {
            "COLLECTING" -> OrderCollecting
            "BOOKING_IN_PROGRESS" -> OrderStatus.OrderBookingInProgress
            "DISCARD" -> OrderStatus.OrderDiscarded
            "BOOKED" -> OrderStatus.OrderBooked
            "DELIVERY_SET" -> OrderStatus.OrderDeliverySet
            "PAYMENT_IN_PROGRESS" -> OrderStatus.OrderPaymentInProgress
            "PAYMENT_FAILED" -> OrderStatus.OrderPaymentFailed
            "PAYED" -> OrderStatus.OrderPayed
            "SHIPPING" -> OrderStatus.OrderInDelivery(0)
            "REFUND" -> OrderStatus.OrderRefund
            "COMPLETED" -> OrderStatus.OrderDelivered(0, System.currentTimeMillis())
            else -> throw Exception("Invalid order status")
        }
    }

}

data class Order(
    val id: UUID,
    val userId: UUID,
    val timeCreated: Long,
    @JsonDeserialize(using = OrderStatusDeserializer::class)
    val status: OrderStatus = OrderCollecting,
    val itemsMap: Map<UUID, Int>,
    val deliveryId: UUID? = null,
    val deliveryDuration: Long? = null,
    val paymentHistory: List<PaymentLogRecord>
)

class OrderKeyDeserializer : KeyDeserializer() {
    override fun deserializeKey(p0: String?, p1: DeserializationContext?): OrderItem {
        return OrderItem(UUID.fromString(p0), "Deserialized order item $p0")
    }

}

data class AbandonedCardLogRecord(
    val transactionId: UUID,
    val timestamp: Long,
    val userInteracted: Boolean
)

class PaymentLogRecord(
    val timestamp: Long,
    val status: PaymentStatus,
    val amount: Int,
    val transactionId: UUID,
)

enum class PaymentStatus {
    FAILED,
    SUCCESS
}

enum class BookingStatus {
    FAILED,
    SUCCESS
}