package com.itmo.microservices.demo.bombardier.external

import com.itmo.microservices.demo.bombardier.BombardierProperties
import com.itmo.microservices.demo.bombardier.external.communicator.ExternalServiceToken
import com.itmo.microservices.demo.bombardier.external.communicator.InvalidExternalServiceResponseException
import com.itmo.microservices.demo.bombardier.external.communicator.UserAwareExternalServiceApiCommunicator
import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.bombardier.external.communicator.mapper
import com.itmo.microservices.demo.bombardier.external.storage.UserStorage
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.net.http.HttpRequest.BodyPublishers
import java.util.*

class UserNotAuthenticatedException(username: String) : Exception(username)

class RealExternalService(
    override val descriptor: ServiceDescriptor,
    private val userStorage: UserStorage,
    props: BombardierProperties
) : ExternalServiceApi {
    private val communicator = UserAwareExternalServiceApiCommunicator(descriptor, props)

    suspend fun getUserSession(id: UUID): ExternalServiceToken {
        val username = getUser(id).name

        return communicator.getUserSession(username) ?: throw UserNotAuthenticatedException(username)
    }

    override suspend fun getUser(id: UUID): User {
        return userStorage.get(id)
    }

    override suspend fun createUser(name: String): User {
        val user = communicator.executeWithDeserialize<User>(
            "createUser",
            "/users",
        ) {
            this.POST(BodyPublishers.ofString(
//                mapper.writeValueAsString(mapOf("name" to name, "password" to "pwd_$name"))
            "{\"name\": \"${name}\", \"password\": \"pwd_$name\"}"
            ))
            header(HttpHeaders.CONTENT_TYPE, "application/json")
        }

        communicator.authenticate(name, "pwd_$name")

        userStorage.create(user)

        return user
    }

    override suspend fun userFinancialHistory(userId: UUID, orderId: UUID?): List<UserAccountFinancialLogRecord> {
        val session = getUserSession(userId)
        val url = if (orderId != null) "orders/${orderId}/finlog" else "/finlog"

        return communicator.executeWithAuthAndDeserialize("userFinancialHistory", url, session)
    }

    override suspend fun createOrder(userId: UUID, price: Int): Order {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("createOrder", "/orders?userId=${userId}&price=${price}", session) {
            POST(BodyPublishers.noBody())
        }
    }

    override suspend fun getOrder(userId: UUID, orderId: UUID): Order {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("getOrder", "/orders/$orderId", session)
    }

    override suspend fun getItems(userId: UUID, available: Boolean): List<CatalogItem> {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("getItems", "/items?available=$available&size=150", session)
    }

    override suspend fun putItemToOrder(userId: UUID, orderId: UUID, itemId: UUID, amount: Int): Boolean {
        val session = getUserSession(userId)

        val okCode = HttpStatus.OK.value()
        val badCode = HttpStatus.BAD_REQUEST.value()

        val code = try {
            communicator.executeWithAuth("putItemToOrder", "/orders/$orderId/items/$itemId?amount=$amount", session) {
                PUT(BodyPublishers.noBody())
            }
        } catch (e: InvalidExternalServiceResponseException) {
            if (e.code != badCode) {
                throw e
            }
            badCode
        }

        return code == okCode
    }

    override suspend fun bookOrder(userId: UUID, orderId: UUID): BookingDto {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("bookOrder", "/orders/$orderId/bookings", session) {
            POST(BodyPublishers.noBody())
        }
    }

    override suspend fun getDeliverySlots(userId: UUID): List<Long> {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("getDeliverySlots", "/orders/delivery/slots", session)
    }

    override suspend fun setDeliveryTime(userId: UUID, orderId: UUID, slot: Long): UUID {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize(
            "setDeliveryTime",
            "/orders/$orderId/delivery?slot=${slot}",
            session
        ) {
            POST(BodyPublishers.noBody())
        }
    }

    override suspend fun payOrder(userId: UUID, orderId: UUID, deadline: Long): PaymentSubmissionDto {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("payOrder", "/orders/$orderId/payment?deadline=${deadline}", session) {
            POST(BodyPublishers.noBody())
        }
    }

    override suspend fun simulateDelivery(userId: UUID, orderId: UUID) {
    }

    override suspend fun abandonedCardHistory(orderId: UUID): List<AbandonedCardLogRecord> {
        TODO("Not yet implemented")
    }

    override suspend fun getBookingHistory(userId: UUID, bookingId: UUID): List<BookingLogRecord> {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize(
            "getBookingHistory",
            "/_internal/bookingHistory/$bookingId",
            session
        )
    }

    override suspend fun deliveryLog(userId: UUID, orderId: UUID): List<DeliveryInfoRecord> {
        val session = getUserSession(userId)

        return communicator.executeWithAuthAndDeserialize("deliveryLog", "/_internal/deliveryLog/$orderId", session)
    }
}