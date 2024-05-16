package com.itmo.microservices.demo.bombardier.logging

import com.itmo.microservices.demo.common.logging.lib.logging.NotableEvent

enum class OrderSettingsDeliveryNotableEvents(private val template: String) : NotableEvent {

    I_CHOOSE_SLOT("Choose delivery slot for order {}"),
    I_SKIP_SETTING_SLOT("Skip setting delivery slot for order {}"),
    E_CHOOSE_SLOT_FAIL("Delivery slot was not chosen. DeliveryId: {}. orderId: {}"),
    I_CHOOSE_SLOT_SUCCESS("Successfully choose delivery slot: '{} sec' for order {}");


    override fun getTemplate(): String {
        return template
    }

    override fun getName(): String {
        return name
    }
}