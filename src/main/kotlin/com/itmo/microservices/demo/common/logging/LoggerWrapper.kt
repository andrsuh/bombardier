package com.itmo.microservices.demo.common.logging

import net.logstash.logback.marker.Markers.append
import org.slf4j.Logger

const val testServiceFiledName = "test_service"

class LoggerWrapper(
    var logger: Logger,
    var serviceName: String
) {
    fun debug(msg: String) {
        logger.debug(append(testServiceFiledName, serviceName), msg)
    }

    fun info(msg: String) {
        logger.info(append(testServiceFiledName, serviceName), msg)
    }

    fun warn(msg: String) {
        logger.warn(append(testServiceFiledName, serviceName), msg)
    }

    fun error(msg: String) {
        logger.error(append(testServiceFiledName, serviceName), msg)
    }

    fun error(msg: String, throwable: Throwable) {
        logger.error(append(testServiceFiledName, serviceName), msg, throwable)
    }
}