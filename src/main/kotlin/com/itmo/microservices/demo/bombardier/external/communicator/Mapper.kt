package com.itmo.microservices.demo.bombardier.external.communicator

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.itmo.microservices.demo.bombardier.external.Order
import com.itmo.microservices.demo.bombardier.external.PaymentSubmissionDto
import java.util.*
import java.util.regex.Matcher
import java.util.regex.Pattern

class BombardierMappingExceptionWithUrl(url: String, content: String, originalException: Throwable) : Exception("""
    URL: $url
    $content
""".trimIndent(), originalException)

class BombardierMappingException(content: String, clazz: Class<*>, originalException: Throwable) : Exception(
    """
        MAPPING EXCEPTION
        
        Original request
        $content
        
        Excepted to parse to
        ${clazz.name}
        
        with fields
        ${clazz.declaredFields.map { "${it.name}: ${it.type.name}" }.joinToString("\n\t")}
        
        Original exception message
        ${originalException.message}
    """.trimIndent(),
    originalException
) {
    fun exceptionWithUrl(url: String) = BombardierMappingExceptionWithUrl(url, message!!, cause!!)
}

val mapper = jacksonObjectMapper().apply {
    findAndRegisterModules()
}.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

val regexOrderId: String = "\"id\"\\s*:\\s*\"([^\"]*)\""
val regexTimestamp: String = "\"timestamp\"\\s*:\\s*(\"([^\"]*)\"|(\\d+))"
val regexTransactionId: String = "\"transactionId\"\\s*:\\s*\"([^\"]*)\""

val patternOrderId: Pattern = Pattern.compile(regexOrderId)
val patternTimestamp: Pattern = Pattern.compile(regexTimestamp)
val patternTransactionId: Pattern = Pattern.compile(regexTransactionId)

val fakeUUID = UUID.randomUUID()

inline fun <reified T> readValueBombardier(content: String): T {
    return try {
        if (T::class == Order::class) {
            val matcher: Matcher = patternOrderId.matcher(content)
            if (matcher.find()) {
                val idValue: String = matcher.group(1)
                return Order(UUID.fromString(idValue), fakeUUID, 1L, itemsMap = emptyMap(), paymentHistory = emptyList()) as T
            }
        }

        if (T::class == PaymentSubmissionDto::class) {
            val matcher: Matcher = patternTimestamp.matcher(content)
            val matcher2: Matcher = patternTransactionId.matcher(content)
            if (matcher.find() && matcher2.find()) {
                val ts: String = matcher.group(1)
                val tId: String = matcher2.group(1)
                return PaymentSubmissionDto(ts.toLong(), UUID.fromString(tId)) as T
            }
        }

        mapper.readValue(content, object : TypeReference<T>(){})
    } catch (t: JsonProcessingException) {
        throw BombardierMappingException(content, T::class.java, t)
    }
}