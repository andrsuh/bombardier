package com.itmo.microservices.demo.bombardier.logging

import com.itmo.microservices.demo.common.logging.lib.logging.NotableEvent

enum class OrderCommonNotableEvents(private val template: String) : NotableEvent {
    E_ILLEGAL_ORDER_TRANSITION("Illegal transition for order {} from {} to {}"),
    E_BOOKING_STILL_IN_PROGRESS("Booking finished but order {} still in status {}");

    override fun getTemplate(): String {
        return template
    }

    override fun getName(): String {
        return name
    }
}