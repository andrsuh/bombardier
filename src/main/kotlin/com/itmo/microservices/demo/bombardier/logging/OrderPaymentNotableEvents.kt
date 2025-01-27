package com.itmo.microservices.demo.bombardier.logging

import com.itmo.microservices.demo.common.logging.lib.logging.NotableEvent


enum class OrderPaymentNotableEvents(private val template: String) : NotableEvent {

    I_PAYMENT_STARTED("Payment started for order {}"),
    E_SUBMISSION_TIMEOUT_EXCEEDED("Payment submission is started for order: {} but hasn't finished withing {} millis"),
    I_STARTED_PAYMENT("Payment submission is started for order: {} at {}, tx: {}"),
    I_START_WAITING_FOR_PAYMENT_RESULT("Start waiting for order: {}, tx: {} result. Duration: {}"),
    E_PAYMENT_NO_OUTCOME_FOUND("Payment no outcome found: {}"),
    E_PAYMENT_TIMEOUT_EXCEEDED("Payment is started for order: {} but hasn't finished within {} sec. Duration: {}"),
    E_PAYMENT_STATUS_FAILED("There is payment record for order: {} for order status is different"),
    E_WITHDRAW_NOT_FOUND("Order {} is paid but there is not withdrawal operation found for user: {}"),
    I_PAYMENT_SUCCESS("Payment succeeded for order {}, tx: {}, duration {}"),
    I_PAYMENT_RETRY("Payment failed for order {}, go to retry. Attempt {}"),
    E_PAYMENT_FAILED("Payment failed for order {}, tx: {}, duration {}");

    override fun getTemplate(): String {
        return template
    }

    override fun getName(): String {
        return name
    }
}