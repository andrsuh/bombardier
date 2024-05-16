package com.itmo.microservices.demo.common.logging.lib.logging

interface NotableEvent {
    fun getTemplate(): String
    fun getName(): String
}
