package com.itmo.microservices.demo.common.config

import org.springframework.context.ApplicationListener
import org.springframework.context.event.ContextRefreshedEvent
import org.springframework.stereotype.Component


@Component
class MyContextRefreshListener : ApplicationListener<ContextRefreshedEvent?> {
    override fun onApplicationEvent(event: ContextRefreshedEvent) {
        println("Received ContextRefreshedEvent")
    }
}