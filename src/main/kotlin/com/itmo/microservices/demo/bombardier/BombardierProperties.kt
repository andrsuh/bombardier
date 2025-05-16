package com.itmo.microservices.demo.bombardier

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import kotlin.properties.Delegates

@ConfigurationProperties(prefix = "bombardier", ignoreInvalidFields = false, ignoreUnknownFields = false)
@Component
class BombardierProperties {
    var teams by Delegates.notNull<List<Map<String, String>>>()
    var authEnabled by Delegates.notNull<Boolean>()
    fun getDescriptors() = teams.map {
        ServiceDescriptor(
            it["name"]!!,
            it["url"]!!,
            it["token"]!!,
            it["expiresAt"]!!.toLong(),
        )
    }
}

data class ServiceDescriptor(
    var name: String,
    var url: String,
    val token: String,
    var expiresAt: Long,
)