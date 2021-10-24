package com.itmo.microservices.demo.bombardier.logging

import com.itmo.microservices.commonlib.logging.NotableEvent

enum class TestNotableEvents(private val template: String): NotableEvent  {

    I_TEST_SUCCESS("Test {} success"),
    I_TEST_FAIL("Test {} failed");

    override fun getTemplate(): String {
        return template
    }

    override fun getName(): String {
        return name
    }
}