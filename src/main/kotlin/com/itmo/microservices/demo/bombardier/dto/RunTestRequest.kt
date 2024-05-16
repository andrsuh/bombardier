package com.itmo.microservices.demo.bombardier.dto

data class RunTestRequest(
    val serviceName: String,
    val usersCount: Int,
    val testCount: Int,
    val ratePerSecond: Int,
)