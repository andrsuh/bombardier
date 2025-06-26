package com.itmo.microservices.demo.bombardier.flow

import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.external.User
import com.itmo.microservices.demo.bombardier.external.communicator.ExternalServiceApiCommunicator
import com.itmo.microservices.demo.common.logging.LoggerWrapper
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.NoSuchElementException

//@Component
class UserManagement(
    private val descriptor: ServiceDescriptor,
    private val externalServiceApi: ExternalServiceApi
) {

    val log = LoggerWrapper(
        LoggerFactory.getLogger(UserManagement::class.java),
        descriptor.name
    )

    private val userIdsByService = mutableListOf<UUID>()

    suspend fun createUsersPool(numberOfUsers: Int): List<UUID> {
        repeat(numberOfUsers) { index ->
            kotlin.runCatching {
                externalServiceApi.createUser("service-${descriptor.name}-user-$index-${System.currentTimeMillis()}")
            }.onSuccess { user ->
                userIdsByService.add(user.id)
            }.onFailure {
                log.error("User has not been created, url ${descriptor.url}", it)
            }
        }

        if (userIdsByService.isEmpty()) {
            throw IllegalStateException("No users were created for service ${descriptor.name}")
        }

        return userIdsByService
    }

    fun getRandomUserId(service: String): UUID {
        return try {
            userIdsByService.random()
        } catch (t: NoSuchElementException) {
            throw IllegalStateException("There are no users for service $service")
        }
    }
}