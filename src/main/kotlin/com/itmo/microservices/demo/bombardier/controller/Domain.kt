package com.itmo.microservices.demo.bombardier.controller

data class RunTestRequest(
    val serviceName: String,
    val usersCount: Int,
    val parallelProcCount: Int,
    val testCount: Int,
)