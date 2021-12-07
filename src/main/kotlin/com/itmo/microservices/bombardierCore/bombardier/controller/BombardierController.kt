package com.itmo.microservices.bombardierCore.bombardier.controller

import com.itmo.microservices.bombardierCore.bombardier.dto.RunTestRequest
import com.itmo.microservices.bombardierCore.bombardier.flow.TestController
import com.itmo.microservices.bombardierCore.bombardier.flow.TestParameters
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.responses.ApiResponse
import com.itmo.microservices.bombardierCore.bombardier.dto.RunningTestsResponse
import com.itmo.microservices.bombardierCore.bombardier.dto.toExtended
import com.itmo.microservices.bombardierCore.bombardier.external.knownServices.KnownServices
import com.itmo.microservices.bombardierCore.bombardier.external.knownServices.ServiceDescriptor
import com.itmo.microservices.bombardierCore.bombardier.flow.TestEndedEvent
import com.itmo.microservices.bombardierCore.bombardier.stages.TestStageSetKind
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.bind.annotation.*
import java.util.concurrent.atomic.AtomicBoolean

@RestController
@RequestMapping("/test")
class BombardierController(private val testApi: TestController, private val allTestsRunner: AllTestsRunner) {
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
            .map { it.value.testParams.toExtended(
                it.value.testsStarted.get(),
                it.value.testsFinished.get())
            }
        return RunningTestsResponse(currentTests)
    }

    // Internal
    @PostMapping("/runForAll")
    fun runTestForAll() {
        allTestsRunner.start()
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
        testApi.startTestingForService(
            TestParameters(
                request.serviceName,
                request.testStageSetKind,
                request.usersCount,
                request.parallelProcCount,
                request.testCount
            )
        )
        // testApi.getTestingFlowForService(request.serviceName).testFlowCoroutine.complete()
    }


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

    @PostMapping("/stopAll")
    @Operation(
        summary = "Stop all tests",
        responses = [
            ApiResponse(description = "OK", responseCode = "200")
        ]
    )
    fun stopAllTests() {
        runBlocking {
            testApi.stopAllTests()
        }
    }
}

@Component
class AllTestsRunner(private val testApi: TestController) : ApplicationListener<TestEndedEvent> {
    private val descriptors = mutableListOf<ServiceDescriptor>()
    private val isRunning = AtomicBoolean(false)

    @Autowired
    private lateinit var applicationEventPublisher: ApplicationEventPublisher

    @Autowired
    lateinit var appContext: ApplicationContext

    @ResponseStatus(HttpStatus.BAD_REQUEST)
    private class AlreadyRunningException : IllegalStateException("tests are already running, pls wait dumbass")

    override fun onApplicationEvent(e: TestEndedEvent) {
        descriptors.remove(e.descriptor)
        if (descriptors.isNotEmpty()) {
            start(false)
        }
        else {
            isRunning.set(false)
            println("===================== END")
            applicationEventPublisher.publishEvent(AllTestsFinishedEvent(this))
        }
    }

    fun start(freshStart: Boolean = true) {
        if (freshStart) {
            if (isRunning.get()) {
                throw AlreadyRunningException()
            }
            isRunning.set(true)
            descriptors.addAll(KnownServices.getInstance().get())
            println("===================== TESTING START")
        }
        descriptors[0].let {
            println("\n\n\n\n===================== ${it.name} / ${it.url}")
            start(it)
        }
    }

    private fun start(descriptor: ServiceDescriptor) {
        testApi.startTestingForService(
            TestParameters(
                descriptor.name,
                TestStageSetKind.DEFAULT,
                1,
                1,
                1
            )
        )
    }
}

class AllTestsFinishedEvent(source: Any) : ApplicationEvent(source)