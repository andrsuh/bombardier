package com.itmo.microservices.demo.bombardier.external.communicator

import java.net.URL
import java.util.*

data class TokenResponse(val accessToken: String, val refreshToken: String)

fun TokenResponse.toExternalServiceToken(service: String) = ExternalServiceToken(service, accessToken, refreshToken)