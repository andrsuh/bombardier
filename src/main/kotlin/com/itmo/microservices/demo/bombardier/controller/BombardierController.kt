package com.itmo.microservices.demo.bombardier.controller

import com.itmo.microservices.demo.bombardier.dto.RunTestRequest
import com.itmo.microservices.demo.bombardier.dto.RunningTestsResponse
import com.itmo.microservices.demo.bombardier.dto.toExtended
import com.itmo.microservices.demo.bombardier.external.knownServices.TestedServicesManager
import com.itmo.microservices.demo.bombardier.flow.LoadProfile
import com.itmo.microservices.demo.bombardier.flow.QuotaService
import com.itmo.microservices.demo.bombardier.flow.SinLoad
import com.itmo.microservices.demo.bombardier.flow.TestLauncher
import com.itmo.microservices.demo.bombardier.flow.TestParameters
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import java.time.Duration

@RestController
@RequestMapping("/test")
class BombardierController(
    private val testApi: TestLauncher,
    private val services: TestedServicesManager,
    private val serviceManager: TestedServicesManager,
    private val quotaService: QuotaService
) {
    companion object {
        val logger = LoggerFactory.getLogger(BombardierController::class.java)
    }

    @GetMapping("running/{id}", produces = ["application/json"])
    @Operation(
        summary = "View info about running tests on service",
        responses = [
            ApiResponse(description = "OK", responseCode = "200"),
            ApiResponse(description = "Test with name {id} was not found", responseCode = "404")
        ]
    )
    fun listRunningTestsPerService(@PathVariable id: String): RunningTestsResponse {
        val currentServiceTestFlow = testApi.getTestingFlowForService(id)

        val testParamsExt = currentServiceTestFlow.testParams.toExtended(
            currentServiceTestFlow.testsStarted.get(),
            currentServiceTestFlow.testsFinished.get()
        )
        return RunningTestsResponse(listOf(testParamsExt))
    }

    @GetMapping("running/index", produces = ["application/json"])
    @Operation(
        summary = "View info about all services and their running tests",
        responses = [
            ApiResponse(description = "OK", responseCode = "200"),
        ]
    )
    fun listAllRunningTests(): RunningTestsResponse {
        val currentTests = testApi.runningTests
            .map {
                it.value.testParams.toExtended(
                    it.value.testsStarted.get(),
                    it.value.testsFinished.get()
                )
            }
        return RunningTestsResponse(currentTests)
    }

    @PostMapping("/run")
    @Operation(
        summary = "Run Test with params",
        responses = [
            ApiResponse(description = "OK", responseCode = "200"),
            ApiResponse(
                description = "There is no such feature launch several flows for the service in parallel",
                responseCode = "400",
            )
        ]
    )
    fun runTest(@RequestBody request: RunTestRequest) {
        logger.warn("Request: $request")
        // auth
        val desc = serviceManager.descriptorByToken(request.token)

        if (request.onPremises && !quotaService.startTestRun(request.token)) {
            logger.warn("Quota exceeded for service: ${desc.name}")
            throw IllegalStateException("Quota exceeded for provided token")
        }

        testApi.startTestingForService(
            TestParameters(
                serviceName = desc.name,
                numberOfUsers = request.usersCount,
                numberOfTests = request.testCount,
                ratePerSecond = request.ratePerSecond,
                testSuccessByThePaymentFact = request.testSuccessByThePaymentFact,
                stopAfterOrderCreation = request.stopAfterOrderCreation,
                paymentProcessingTimeMillis = request.processingTimeMillis,
                paymentProcessingTimeAmplitude = request.ptvaMillis,
                variatePaymentProcessingTime = request.variatePaymentProcessingTime,
                loadProfile = loadProfile(request.profile, request.ratePerSecond),
                token = request.token,
                onPremises = request.onPremises,
            )
        )
        // testApi.getTestingFlowForService(request.serviceName).testFlowCoroutine.complete()
    }

//    @PostMapping("/runDefault")
//    @Operation(
//        summary = "Run Test with default params",
//        responses = [
//            ApiResponse(description = "OK", responseCode = "200"),
//            ApiResponse(
//                description = "There is no such feature launch several flows for the service in parallel",
//                responseCode = "400",
//            )
//        ],
//    )
//    fun runTestDefault(@ApiParam(value = "service name", example = "p11") @RequestParam serviceName: String) {
//        testApi.startTestingForService(
//            TestParameters(serviceName, 2, 1, 1)
//        )
//        // testApi.getTestingFlowForService(request.serviceName).testFlowCoroutine.complete()
//    }


    @PostMapping("/stop/{serviceName}")
    @Operation(
        summary = "Stop test by service name",
        responses = [
            ApiResponse(description = "OK", responseCode = "200"),
            ApiResponse(
                description = "There is no running test with current serviceName",
                responseCode = "400",
                content = [Content()]
            )
        ]
    )
    fun stopTest(@PathVariable serviceName: String) {
        runBlocking {
            testApi.stopTestByServiceName(serviceName)
        }
    }

//    @PostMapping("/stopAll")
//    @Operation(
//        summary = "Stop all tests",
//        responses = [
//            ApiResponse(description = "OK", responseCode = "200")
//        ]
//    )
//    fun stopAllTests() {
//        runBlocking {
//            testApi.stopAllTests()
//        }
//    }

    @GetMapping("allServices", produces = ["application/json"])
    @Operation(
        summary = "Get all services in bombarider system",
        responses = [
            ApiResponse(description = "OK", responseCode = "200"),
        ]
    )
    fun getAllServices() = services.all().associate { it.name to it.url }

//    @PostMapping("/newService")
//    @Operation(
//        summary = "Add new service to list",
//        responses = [
//            ApiResponse(description = "OK", responseCode = "200"),
//            ApiResponse(
//                description = "the url to service is invalid",
//                responseCode = "400",
//            )
//        ]
//    )
//    fun newService(@RequestBody request: NewServiceRequest) {
//        val url = try {
//            URL(request.url)
//        } catch (t: Throwable) {
//            throw InvalidServiceUrlException()
//        }
//        services.add(ServiceDescriptor(request.name, request.url, ))
//    }

    private fun loadProfile(key: String?, ratePerSec: Int): LoadProfile {
        if (key == null) {
            return LoadProfile(ratePerSecond = ratePerSec)
        }
        return LoadProfile(ratePerSecond = ratePerSec, sinLoad = key.parseSin())
    }

    fun String?.parseSin(): SinLoad? = when (this) {
        null -> null
        "s_n" -> null
        else -> {
            val parts = this.split("_")
            if (parts.size != 3) {
                throw IllegalArgumentException("Invalid sin load format")
            } else {
                SinLoad(parts[1].toDouble(), Duration.ofSeconds(parts[2].toLong()))
            }
        }
    }
}