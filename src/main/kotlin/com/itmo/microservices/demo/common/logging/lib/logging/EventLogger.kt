package com.itmo.microservices.demo.common.logging.lib.logging

import org.slf4j.Logger
import org.slf4j.Marker

/**
 * This is specialized version of logback logger.
 * Every time we log specific {@link CommonNotableEvents}
 * we record metric called @{code events_total}
 * with event type as label derived from name of event.
 *
 * <p>
 * Example of instance declaration
 * <pre>{@code
 * class SomeClass {
 *   @InjectEventLogger
 *   KPIEventsLogger logger;
 * }
 * }</pre>
 *
 * <p>
 * Whenever you want to log an event
 * call it as you would typically do with logback
 * {@link EventLogger#trace}
 * {@link EventLogger#debug}
 * {@link EventLogger#info}
 * {@link EventLogger#warn}
 * {@link EventLogger#error}
 */
class EventLogger(private val log: Logger,
                  private val metrics: CommonMetricsCollector
) {

    fun trace(event: NotableEvent, vararg payload: Any?) {
        log.trace(event.getTemplate(), *payload)
        metrics.countEvent(event)
    }

    fun debug(event: NotableEvent, vararg payload: Any?) {
        log.debug(event.getTemplate(), *payload)
        metrics.countEvent(event)
    }

    fun info(event: NotableEvent, vararg payload: Any?) {
        log.info(event.getTemplate(), *payload)
        metrics.countEvent(event)
    }

    fun warn(event: NotableEvent, vararg payload: Any?) {
        log.warn(event.getTemplate(), *payload)
        metrics.countEvent(event)
    }

    fun error(event: NotableEvent, vararg payload: Any?) {
        log.error(event.getTemplate(), *payload)
        metrics.countEvent(event)
    }

    fun trace(marker: Marker, event: NotableEvent, vararg payload: Any?) {
        log.trace(marker, event.getTemplate(), *payload)
        metrics.countEvent(event)
    }

    fun debug(marker: Marker,event: NotableEvent, vararg payload: Any?) {
        log.debug(marker, event.getTemplate(), *payload)
        metrics.countEvent(event)
    }

    fun info(marker: Marker,event: NotableEvent, vararg payload: Any?) {
        log.info(marker, event.getTemplate(), *payload)
        metrics.countEvent(event)
    }

    fun warn(marker: Marker,event: NotableEvent, vararg payload: Any?) {
        log.warn(marker, event.getTemplate(), *payload)
        metrics.countEvent(event)
    }

    fun error(marker: Marker,event: NotableEvent, vararg payload: Any?) {
        log.error(marker, event.getTemplate(), *payload)
        metrics.countEvent(event)
    }
}