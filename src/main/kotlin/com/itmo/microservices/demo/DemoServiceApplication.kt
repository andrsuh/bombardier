package com.itmo.microservices.demo

import com.itmo.microservices.demo.bombardier.external.PaymentLogRecord
import com.itmo.microservices.demo.common.SuspendableAwaiter
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.*


@SpringBootApplication
@Configuration
class DemoServiceApplication {

    @Bean
    fun merger(): SuspendableAwaiter<UUID, Boolean, PaymentLogRecord> {
        return SuspendableAwaiter()
    }
}

fun main(args: Array<String>) {
    if (System.getProperty("is.local", "false").toBoolean()) {
        println("Running locally")
    }
    runApplication<DemoServiceApplication>(*args)
}