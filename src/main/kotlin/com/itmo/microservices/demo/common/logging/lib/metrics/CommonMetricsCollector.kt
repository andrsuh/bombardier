package com.itmo.microservices.demo.common.logging.lib.logging

import io.micrometer.core.instrument.Metrics

open class CommonMetricsCollector(private val serviceName: String) {

    fun countEvent(event: NotableEvent) {
        Metrics.counter(EVENTS, EVENTS_TYPE_LABEL, event.getName()).increment()
    }
    companion object {
        const val EVENTS = "events_total"
        const val EVENTS_TYPE_LABEL = "type"
    }
}