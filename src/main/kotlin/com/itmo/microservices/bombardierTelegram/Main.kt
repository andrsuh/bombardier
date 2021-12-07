package com.itmo.microservices.bombardierTelegram

import com.itmo.microservices.bombardierCore.DemoServiceApplication
import kotlinx.coroutines.runBlocking
import org.springframework.boot.runApplication

fun main(args: Array<String>) {
    val app = runApplication<DemoServiceApplication>(*args)

    app.addApplicationListener {
        println("keklik")
    }

    runBlocking {
    }
}