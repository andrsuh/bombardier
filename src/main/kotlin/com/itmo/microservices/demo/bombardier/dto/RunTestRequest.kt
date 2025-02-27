package com.itmo.microservices.demo.bombardier.dto

data class RunTestRequest(
    val serviceName: String = "onlineStore",
    val usersCount: Int = 1,
    val testCount: Int,
    val ratePerSecond: Int,
    val testSuccessByThePaymentFact: Boolean = false,
    val stopAfterOrderCreation: Boolean = false,
    val processingTimeMillis: Long = 80_000,
    val variatePaymentProcessingTime: Boolean = false,
    val profile: String = "default",
)