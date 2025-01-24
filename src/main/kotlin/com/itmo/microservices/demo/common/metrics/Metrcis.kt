package com.itmo.microservices.demo.common.metrics

import com.itmo.microservices.demo.bombardier.stages.TestStage.TestContinuationType
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.ExecutorServiceMetrics
import io.micrometer.core.instrument.util.NamedThreadFactory
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.Summary
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit


class PromMetrics {
    companion object {
        val promRegistry =
            (io.micrometer.core.instrument.Metrics.globalRegistry.registries.first() as PrometheusMeterRegistry).prometheusRegistry
        val stageLabel = "stage"
        val serviceLabel = "service"

        private val executor = Executors.newFixedThreadPool(8, NamedThreadFactory("prom-metrics-dispatcher")).also {
            Metrics.executorServiceMonitoring(it, "prom-metrics-dispatcher")
        }

        private val stageDuration = Summary.build()
            .name("stage_duration_ok")
            .help("Stage duration.")
            .labelNames(stageLabel, serviceLabel, "result")
            .register(promRegistry)


        private val testDuration = Summary.build()
            .name("test_duration")
            .help("Test duration.")
            .labelNames(serviceLabel, "testOutcome")
            .register(promRegistry)

        private val externalSysDuration = Summary.build()
            .name("external_sys_duration")
            .help("External system duration.")
            .labelNames(serviceLabel, "accountName", "outcome")
            .register(promRegistry)

        private val httpRequestLatent = Summary.build()
            .name("http_request_latent")
            .help("HTTP request latency.")
            .labelNames(serviceLabel, "method")
            .register(promRegistry)

        private val httpExternalDuration = Summary.build()
            .name("http_external_duration")
            .help("HTTP external duration.")
            .labelNames(serviceLabel, "method", "result")
            .register(promRegistry)

        fun testDurationRecord(serviceName: String, testOutcome: String, timeMs: Long) {
            executor.submit{
                testDuration.labels(serviceName, testOutcome).observe(timeMs.toDouble())
            }
        }

        fun stageDurationRecord(
            stageName: String,
            serviceName: String,
            timeMs: Long,
            state: TestContinuationType,
            failed: Boolean
        ) {
            executor.submit {
                stageDuration.labels(stageName, serviceName, if (failed) "failed" else "success")
                    .observe(timeMs.toDouble())
            }
        }

        fun externalSysDurationRecord(serviceName: String, accountName: String, outcome: String, timeMs: Long) {
            executor.submit {
                externalSysDuration.labels(serviceName, accountName, outcome).observe(timeMs.toDouble())
            }
        }

        fun timeHttpRequestLatent(serviceName: String, method: String, timeMs: Long) {
            executor.submit {
                httpRequestLatent.labels(serviceName, method).observe(timeMs.toDouble())
            }
        }

        fun externalMethodDurationRecord(serviceName: String, method: String, result: String, timeMs: Long) {
            executor.submit {
                httpExternalDuration.labels(serviceName, method, result).observe(timeMs.toDouble())
            }
        }
    }
}


class Metrics(private val tags: List<Tag>) {

    companion object {
        val globalRegistry = io.micrometer.core.instrument.Metrics.globalRegistry

        private const val externalSysSubmitName = "external_sys_submit"
        private const val testDurationFailName = "test_duration_fail"
        private const val paymentsAmountName = "payments_amount"
        private const val paymentFinishedName = "payment_finished"
        private const val extSysChargeAmountName = "external_amount"

        val stageLabel = "stage"
        val serviceLabel = "service"

        @JvmStatic
        fun withTags(vararg labels: Pair<String, String>): Metrics {
            return Metrics(labels.map { Tag.of(it.first, it.second) })
        }

        @JvmStatic
        fun executorServiceMonitoring(executorService: ExecutorService, executorName: String) {
            ExecutorServiceMetrics.monitor(globalRegistry, executorService, executorName, listOf())
        }

        private val executor = Executors.newFixedThreadPool(8, NamedThreadFactory("metrics-dispatcher")).also {
            executorServiceMonitoring(it, "metrics-dispatcher")
        }
    }

    fun externalSysRequestSubmitted(bulkSize: Double) {
        executor.submit {
            Counter.builder(externalSysSubmitName)
                .tags(tags)
                .register(globalRegistry)
                .increment(bulkSize)
        }
    }

    fun paymentsAmountRecord(amount: Int) {
        executor.submit {
            Counter.builder(paymentsAmountName)
                .tags(tags)
                .register(globalRegistry)
                .increment(amount.toDouble())
        }
    }

    fun paymentFinished() {
        executor.submit {
            Counter.builder(paymentFinishedName)
                .tags(tags)
                .register(globalRegistry)
                .increment()
        }
    }

    fun rateLimitDemo(value: Long) {
        executor.submit {
            Counter.builder("rate_limit_demo")
                .tags(tags)
                .register(globalRegistry)
                .increment(value.toDouble())
        }
    }

    fun externalSysChargeAmountRecord(amount: Int) {
        executor.submit {
            Counter.builder(extSysChargeAmountName)
                .tags(tags)
                .register(globalRegistry)
                .increment(amount.toDouble())
        }
    }
}