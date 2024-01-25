package com.itmo.microservices.demo.bombardier.external.communicator

import com.itmo.microservices.demo.bombardier.BombardierProperties
import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.common.logging.LoggerWrapper
import okhttp3.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.io.IOException
import java.net.URL
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import com.itmo.microservices.demo.common.metrics.Metrics
import io.micrometer.core.instrument.util.NamedThreadFactory
import kotlinx.coroutines.flow.callbackFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class CachedResponseBody internal constructor(_body: ResponseBody) {
    private val string: String

    init {
        string = _body.string()
    }

    fun string() = string
}

class TrimmedResponse private constructor(
    private val body: CachedResponseBody,
    private val code: Int,
    private val req: Request
) {
    companion object {
        fun fromResponse(resp: Response): TrimmedResponse {
            return TrimmedResponse(CachedResponseBody(resp.body()!!), resp.code(), resp.request()).apply {
                resp.close()
            }
        }
    }

    fun body() = body
    fun code() = code
    fun request() = req
}

open class ExternalServiceApiCommunicator(
    private val descriptor: ServiceDescriptor,
    private val props: BombardierProperties
) {
    companion object {
        private val CALL_TIMEOUT = Duration.ofSeconds(10)
        private val READ_TIMEOUT = Duration.ofSeconds(5)
        private val WRITE_TIMEOUT = Duration.ofSeconds(5)
        private val JSON = MediaType.parse("application/json; charset=utf-8")

        private val externalServiceExecutor =
            Executors.newFixedThreadPool(16, NamedThreadFactory("external-service-executor")).also {
                Metrics.executorServiceMonitoring(it, "external-service-executor")
            }
    }

    val logger = LoggerWrapper(
        LoggerFactory.getLogger(ExternalServiceApiCommunicator::class.java),
        descriptor.name
    )

    private val client = OkHttpClient.Builder().run {
        dispatcher(Dispatcher(externalServiceExecutor))
        protocols(mutableListOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
        callTimeout(CALL_TIMEOUT)
        readTimeout(READ_TIMEOUT)
        writeTimeout(WRITE_TIMEOUT)
        this.eventListener(object : EventListener() {
            private val callMap = ConcurrentHashMap<Call, Long>()

            override fun callStart(call: Call) {
                callMap[call] = System.currentTimeMillis()
            }

            override fun callEnd(call: Call) {
                val startTime = callMap.remove(call) ?: return
                Metrics
                    .withTags(
                        "method" to call.request().method(),
                        "url" to call.request().url().toString(),
                        "result" to "OK"
                    )
                    .externalMethodDurationRecord(System.currentTimeMillis() - startTime)
            }

            override fun callFailed(call: Call, ioe: IOException) {
                val startTime = callMap.remove(call) ?: return
                Metrics
                    .withTags(
                        "method" to call.request().method(),
                        "url" to call.request().url().toString(),
                        "result" to "FAIL"
                    )
                    .externalMethodDurationRecord(System.currentTimeMillis() - startTime)
            }
        })
        connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        build()
    }

    open suspend fun authenticate(username: String, password: String) = execute("authenticate", "/authentication") {
        jsonPost(
            "name" to username,
            "password" to password
        )
    }.run {
        val resp = body().string()
        mapper.readValue(resp, TokenResponse::class.java).toExternalServiceToken(descriptor.url)
    }

    protected suspend fun reauthenticate(token: ExternalServiceToken) =
        execute("reauthenticate", "/authentication/refresh") {
            assert(!token.isRefreshTokenExpired())
            post()
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token.refreshToken}")
        }.run {
            mapper.readValue(body().string(), TokenResponse::class.java).toExternalServiceToken(descriptor.url)
        }

    suspend fun execute(method: String, url: String) = execute(method, url) {}

    suspend fun execute(method: String, url: String, builderContext: CustomRequestBuilder.() -> Unit): TrimmedResponse {
        val requestBuilder = CustomRequestBuilder(descriptor.url).apply {
            _url(url)
            builderContext(this)
        }
        return suspendCoroutine {
            val req = requestBuilder.build()
            if (req.method() != "GET") {
                logger.info("sending request to ${req.method()} ${req.url().url()}")
            }
            val startTime = System.currentTimeMillis()
            val serviceName = descriptor.name

            client.newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
//                    Metrics
//                        .withTags(
//                            "service" to serviceName,
//                            "method" to method,
//                            "code" to "FAILED"
//                        )
//                        .externalMethodDurationRecord(System.currentTimeMillis() - startTime)
                    it.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    val endTime = System.currentTimeMillis()
//                    Metrics
//                        .withTags(
//                            "service" to serviceName,
//                            "method" to method,
//                            "code" to response.code().toString()
//                        )
//                        .externalMethodDurationRecord(endTime - startTime)

                    if (HttpStatus.Series.resolve(response.code()) == HttpStatus.Series.SUCCESSFUL) {
                        try {
                            it.resume(TrimmedResponse.fromResponse(response))
                        } catch (t: Throwable) {
                            it.resumeWithException(t)
                        }
                        return
                    }

                    it.resumeWithException(
                        InvalidExternalServiceResponseException(
                            response.code(),
                            response,
                            "${response.request().method()} ${
                                response.request().url()
                            } External service returned non-OK code: ${response.code()}\n\n${response.body()?.string()}"
                        )
                    )
                }
            })
        }
    }

    suspend fun executeWithAuth(method: String, url: String, credentials: ExternalServiceToken) =
        executeWithAuth(method, url, credentials) {}

    suspend fun executeWithAuth(
        method: String,
        url: String,
        credentials: ExternalServiceToken,
        builderContext: CustomRequestBuilder.() -> Unit
    ): TrimmedResponse {
        return execute(method, url) {
            if (props.authEnabled) {
                header(HttpHeaders.AUTHORIZATION, "Bearer ${credentials.accessToken}")
            }
            builderContext(this)
        }
    }


    class CustomRequestBuilder(private val baseUrl: String) : Request.Builder() {
        companion object {
            val emptyBody = RequestBody.create(null, ByteArray(0))
        }

        fun _url(url: String) = super.url("$baseUrl$url")

        /**
         * Don't call this method in builder
         */
        @Deprecated("Not allowed to use")
        override fun url(url: URL) = throw IllegalStateException("Not allowed to call this function")

        /**
         * Don't call this method in builder
         */
        @Deprecated("Not allowed to use")
        override fun url(url: String) = throw IllegalStateException("Not allowed to call this function")

//        fun jsonPost(ctx: JSONObject.() -> Unit) {
//            val obj = JSONObject()
//            ctx(obj)
//            post(obj.toRequestBody())
//        }

        fun jsonPost(vararg items: Pair<String, String>) {
//            post(JSONObject().withItems(*items).toRequestBody())
            post(RequestBody.create(JSON, mapper.writeValueAsString(items.toMap())));
        }

        fun post() {
            post(emptyBody)
        }

        fun put() {
            put(emptyBody)
        }
    }
}