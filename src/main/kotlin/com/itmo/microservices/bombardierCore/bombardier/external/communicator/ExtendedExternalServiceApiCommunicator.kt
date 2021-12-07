package com.itmo.microservices.bombardierCore.bombardier.external.communicator

import java.net.URL
import java.util.concurrent.ExecutorService

open class ExtendedExternalServiceApiCommunicator(baseUrl: URL, ex: ExecutorService) : ExternalServiceApiCommunicator(baseUrl, ex) {
    suspend inline fun <reified T> executeWithDeserialize(url: String) = executeWithDeserialize<T>(url) {}
    suspend inline fun <reified T> executeWithAuthAndDeserialize(url: String, credentials: ExternalServiceToken)
        = executeWithAuthAndDeserialize<T>(url, credentials) {}

    suspend inline fun <reified T> executeWithDeserialize(url: String, noinline builderContext: CustomRequestBuilder.() -> Unit): T {
        val res = execute(url, builderContext)
        return try {
            readValueBombardier(res.body()!!.string())
        }
        catch (t: BombardierMappingException) {
            throw t.exceptionWithUrl("${res.request().method()} ${res.request().url()}")
        }
    }


    suspend inline fun <reified T> executeWithAuthAndDeserialize(
        url: String,
        credentials: ExternalServiceToken,
        noinline builderContext: CustomRequestBuilder.() -> Unit
    ): T {
        val res = executeWithAuth(url, credentials, builderContext)
        return try {
            readValueBombardier(res.body()!!.string())
        }
        catch (t: BombardierMappingException) {
            throw t.exceptionWithUrl("${res.request().method()} ${res.request().url()}")
        }
    }
}