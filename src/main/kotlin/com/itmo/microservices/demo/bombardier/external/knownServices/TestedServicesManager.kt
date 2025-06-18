package com.itmo.microservices.demo.bombardier.external.knownServices

import com.itmo.microservices.demo.bombardier.BombardierProperties
import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.bombardier.external.ExternalServiceApi
import com.itmo.microservices.demo.bombardier.external.RealExternalService
import com.itmo.microservices.demo.bombardier.external.storage.UserStorage
import com.itmo.microservices.demo.bombardier.flow.UserManagement
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.ResponseStatus

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
    val storage = mutableListOf<ServiceDescriptor>()
    private val apis = mutableMapOf<ServiceDescriptor, ServiceProxy>() // todo make concurrent

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

//    fun descriptorByName(name: String): ServiceDescriptor {
//        return storage.firstOrNull { it.name == name } ?: throw ServiceDescriptorNotFoundException(name)
//    }

    fun descriptorByToken(token: String): ServiceDescriptor {
        return storage.firstOrNull { it.token == token } ?: throw TokenInvalidException(token)
    }

    fun getServiceProxy(token: String): ServiceProxy {
        val descriptor = descriptorByToken(token)
        return apis.getOrPut(descriptor) {
            val api = RealExternalService(descriptor, UserStorage(), props)
            ServiceProxy(api, UserManagement(api))
        }
    }
}