package com.itmo.microservices.bombardierCore.bombardier.dto

import com.itmo.microservices.bombardierCore.bombardier.stages.TestStageSetKind

data class RunTestRequest(
    val serviceName: String,
    val testStageSetKind: TestStageSetKind,
    val usersCount: Int,
    val parallelProcCount: Int,
    val testCount: Int,
)