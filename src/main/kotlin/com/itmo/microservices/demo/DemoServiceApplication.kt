package com.itmo.microservices.demo

import com.itmo.microservices.demo.bombardier.external.PaymentLogRecord
import com.itmo.microservices.demo.common.SuspendableAwaiter
import org.eclipse.jetty.http2.server.HTTP2CServerConnectionFactory
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.web.embedded.jetty.JettyServerCustomizer
import org.springframework.boot.web.embedded.jetty.JettyServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.*


@SpringBootApplication
@Configuration
class DemoServiceApplication {

    @Bean
    fun jettyServerCustomizer(): JettyServletWebServerFactory {
        val jettyServletWebServerFactory = JettyServletWebServerFactory()

        val c = JettyServerCustomizer {
            (it.connectors[0].getConnectionFactory("h2c") as HTTP2CServerConnectionFactory).maxConcurrentStreams = 1_000_000
        }

        jettyServletWebServerFactory.serverCustomizers.add(c)
        return jettyServletWebServerFactory
    }

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