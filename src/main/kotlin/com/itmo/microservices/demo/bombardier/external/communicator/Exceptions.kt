package com.itmo.microservices.demo.bombardier.external.communicator

import java.io.IOException

class InvalidExternalServiceResponseException(val code: Int, msg: String) : IOException(msg)
class TooManyRequestsException(msg: String, val retryAfter: Long?) : RuntimeException(msg)
class TokenHasExpiredException : RuntimeException()