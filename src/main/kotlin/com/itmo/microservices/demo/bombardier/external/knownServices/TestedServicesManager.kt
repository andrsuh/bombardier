package com.itmo.microservices.demo.bombardier.external.knownServices

import com.itmo.microservices.demo.bombardier.BombardierProperties
import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.external.RealExternalService
import com.itmo.microservices.demo.bombardier.external.communicator.UserAwareExternalServiceApiCommunicator
import com.itmo.microservices.demo.bombardier.external.storage.UserStorage
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus
import java.util.concurrent.ConcurrentHashMap

@ResponseStatus(HttpStatus.NOT_FOUND)
class ServiceDescriptorNotFoundException(name: String) : Exception("Descriptor for service $name was not found")

@ResponseStatus(HttpStatus.NOT_FOUND)
class TokenInvalidException(token: String) : Exception("Token $token invalid")

@ResponseStatus(HttpStatus.BAD_REQUEST)
class ServiceDescriptorExistsException() : Exception("descriptor already exists")

class KnownServicesOverflow : Exception("too much services")

data class ServiceProxy(val api: ExternalServiceApi, val userManagement: UserManagement)


private const val THRESHOLD_STORAGE = 100

@Service
class TestedServicesManager(
    private val props: BombardierProperties
) {
    companion object {
        const val ON_PREM_DESCRIPTOR_TOKEN = "on_premises"
    }

    val storage = mutableListOf<ServiceDescriptor>()
    private val apis = ConcurrentHashMap<String, ServiceProxy>()

    init {
        storage.addAll(props.getDescriptors())
    }

    fun all(): List<ServiceDescriptor> = storage

    fun add(descriptor: ServiceDescriptor) {
        if (storage.size > THRESHOLD_STORAGE) {
            throw KnownServicesOverflow()
        }
        if (storage.any { it.name == descriptor.name || it.url == descriptor.url }) {
            throw ServiceDescriptorExistsException()
        }

        storage.add(descriptor)
    }

    fun descriptorByToken(token: String) =
        storage.firstOrNull { it.token == token } ?: throw TokenInvalidException(token)

    fun descriptorByToken(token: String, onPremises: Boolean = false): ServiceDescriptor {
        return if (!onPremises) {
            descriptorByToken(token)
        } else {
            val onPremDescriptor = descriptorByToken(ON_PREM_DESCRIPTOR_TOKEN)
            val serviceDescriptor = descriptorByToken(token)
            return serviceDescriptor.copy(
                url = onPremDescriptor.url
            )
        }
    }

    fun getServiceProxy(descriptor: ServiceDescriptor, onPremises: Boolean): ServiceProxy {
        return apis.getOrPut("${descriptor.token}:onPremises=$onPremises") {
            val api = RealExternalService(UserStorage(), UserAwareExternalServiceApiCommunicator(descriptor, props))
            ServiceProxy(api, UserManagement(descriptor, api))
        }
    }
}