package com.itmo.microservices.demo.common.metrics

import com.itmo.microservices.demo.bombardier.stages.TestStage
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Timer
import java.util.concurrent.TimeUnit


class Metrics(private val tags: List<Tag>) {
    companion object {
        val globalRegistry = io.micrometer.core.instrument.Metrics.globalRegistry

        private const val externalCallDurationName = "http_external_duration"
        private const val stageDurationOkName = "stage_duration_ok"
        private const val stageDurationFailName = "stage_duration_fail"
        private const val testDurationName = "test_duration"
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
    }

    fun stageDurationRecord(timeMs: Long, state: TestStage.TestContinuationType) {
        if (state.iSFailState()) {
            Timer.builder(stageDurationFailName)
                .publishPercentiles(0.95)
                .tags(tags)
                .register(globalRegistry)
                .record(timeMs, TimeUnit.MILLISECONDS)
        } else {
            Timer.builder(stageDurationOkName)
                .publishPercentiles(0.95)
                .tags(tags)
                .register(globalRegistry)
                .record(timeMs, TimeUnit.MILLISECONDS)
        }
    }

    fun testDurationRecord(timeMs: Long) {
        Timer.builder(testDurationName)
            .publishPercentiles(0.95)
            .tags(tags)
            .register(globalRegistry)
            .record(timeMs, TimeUnit.MILLISECONDS)
    }

//    fun testFailDurationRecord(timeMs: Long) {
//        Timer.builder(testDurationFailName)
//            .publishPercentiles(0.95)
//            .tags(tags)
//            .register(globalRegistry).record(timeMs, TimeUnit.MILLISECONDS)
//    }

    fun paymentsAmountRecord(amount: Int) {
        Counter.builder(paymentsAmountName)
            .tags(tags)
            .register(globalRegistry)
            .increment(amount.toDouble())
    }
    fun paymentFinished() {
        Counter.builder(paymentFinishedName)
            .tags(tags)
            .register(globalRegistry)
            .increment()
    }
    fun externalSysChargeAmountRecord(amount: Int) {
        Counter.builder(extSysChargeAmountName)
            .tags(tags)
            .register(globalRegistry)
            .increment(amount.toDouble())
    }


    fun externalMethodDurationRecord(timeMs: Long) {
        Timer.builder(externalCallDurationName).publishPercentiles(0.95)
            .tags(tags)
            .register(globalRegistry).record(timeMs, TimeUnit.MILLISECONDS)
    }
}