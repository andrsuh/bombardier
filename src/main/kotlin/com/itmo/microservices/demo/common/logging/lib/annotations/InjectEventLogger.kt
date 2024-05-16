package com.itmo.microservices.demo.common.logging.lib.annotations

@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class InjectEventLogger
