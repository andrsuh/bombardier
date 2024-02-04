package com.itmo.microservices.demo

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication


@SpringBootApplication
class BombardierApplication {

}

fun main(args: Array<String>) {
    if (System.getProperty("is.local", "false").toBoolean()) {
        println("Running locally")
    }
    runApplication<BombardierApplication>(*args)
}