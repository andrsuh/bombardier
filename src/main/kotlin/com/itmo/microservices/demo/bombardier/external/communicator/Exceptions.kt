package com.itmo.microservices.demo.bombardier.external.communicator

import java.io.IOException
import java.lang.IllegalStateException

class InvalidExternalServiceResponseException(val code: Int, msg: String) : IOException(msg)
class TokenHasExpiredException : IllegalStateException()