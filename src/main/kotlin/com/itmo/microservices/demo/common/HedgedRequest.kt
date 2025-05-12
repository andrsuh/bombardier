package com.itmo.microservices.demo.common

import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.Summary
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpResponse.BodyHandlers
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

data class HedgedRequest(
    val javaClient: HttpClient,
    val request: HttpRequest,
    val scheduler: ScheduledExecutorService
) {
    private val isCompleted = AtomicBoolean(false)
    private val result = CompletableFuture<HttpResponse<String>>()
    private var startedAt: Long = 0L

    fun launch(): CompletableFuture<HttpResponse<String>> {
        startedAt = System.currentTimeMillis()
        internalCall()
        scheduler.schedule(this::internalCall, 50, TimeUnit.MILLISECONDS)
        scheduler.schedule(this::internalCall, 150, TimeUnit.MILLISECONDS)
        scheduler.schedule(this::internalCall, 300, TimeUnit.MILLISECONDS)
        return result
    }

    private fun internalCall() {
        if (isCompleted.get()) return
        javaClient.sendAsync(request, BodyHandlers.ofString())
            .thenApply {
                val alreadyCompleted = isCompleted.getAndSet(true)
                if (!alreadyCompleted) {
                    result.complete(it)
                    hedgedCallDuration.labels("ok").observe((System.currentTimeMillis() - startedAt).toDouble())
                }
            }
            .exceptionally { e ->
                val alreadyCompleted = isCompleted.getAndSet(true)
                if (!alreadyCompleted) {
                    result.completeExceptionally(e)
                    hedgedCallDuration.labels("fail").observe((System.currentTimeMillis() - startedAt).toDouble())
                }
            }
    }

    companion object {
        val promRegistry =
            (io.micrometer.core.instrument.Metrics.globalRegistry.registries.first() as PrometheusMeterRegistry).prometheusRegistry

        private val hedgedCallDuration = Summary.build()
            .name("hedged_call_duration")
            .help("Hedged call duration.")
            .labelNames("result")
            .register(promRegistry)
    }
}
