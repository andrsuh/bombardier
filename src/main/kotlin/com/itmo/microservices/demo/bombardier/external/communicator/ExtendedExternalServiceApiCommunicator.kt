package com.itmo.microservices.demo.bombardier.external.communicator

import com.itmo.microservices.demo.bombardier.BombardierProperties
import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.common.metrics.Metrics
import io.micrometer.core.instrument.util.NamedThreadFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import java.net.http.HttpRequest
import java.util.concurrent.Executors

open class ExtendedExternalServiceApiCommunicator(descriptor: ServiceDescriptor, props: BombardierProperties) :
    ExternalServiceApiCommunicator(
        descriptor, props
    ) {

    companion object {
//        val mappingScope = CoroutineScope(Executors.newFixedThreadPool(128, NamedThreadFactory("mappingScope")).also {
//            Metrics.executorServiceMonitoring(it, "mappingScope")
//        }.asCoroutineDispatcher())
    }

    suspend inline fun <reified T> executeWithDeserialize(method: String, url: String) =
        executeWithDeserialize<T>(method, url) {}

    suspend inline fun <reified T> executeWithAuthAndDeserialize(
        method: String,
        url: String,
        credentials: ExternalServiceToken
    ) =
        executeWithAuthAndDeserialize<T>(method, url, credentials) {}

    suspend inline fun <reified T> executeWithDeserialize(
        method: String,
        url: String,
        noinline builderContext: HttpRequest.Builder.() -> Unit
    ): T {
        val res = execute(method, url, builderContext)
//        return try {
//            mappingScope.async<T> {
                return readValueBombardier(res.body())
//            }.await()
//        } catch (t: BombardierMappingException) {
//            throw t.exceptionWithUrl("${res.request().method()} ${res.request().uri()}")
//        }
    }

    suspend inline fun <reified T> executeWithAuthAndDeserialize(
        method: String,
        url: String,
        credentials: ExternalServiceToken,
        noinline builderContext: HttpRequest.Builder.() -> Unit
    ): T {
        val res = executeWithAuth(method, url, credentials, builderContext)
//        return try {
//            mappingScope.async<T> {
                return readValueBombardier(res.body())
//            }.await()
//        } catch (t: BombardierMappingException) {
//            throw t.exceptionWithUrl("${res.request().method()} ${res.request().uri()}")
//        }
    }
}