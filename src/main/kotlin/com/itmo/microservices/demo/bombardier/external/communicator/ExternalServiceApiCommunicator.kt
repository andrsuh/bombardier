package com.itmo.microservices.demo.bombardier.external.communicator

import com.itmo.microservices.demo.bombardier.BombardierProperties
import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.bombardier.flow.TestCtxKey
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

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
    private val props: BombardierProperties,
    private val httpClientsManager: HttpClientsManager = HttpClientsManager()
) {
    companion object {
        private val JSON = MediaType.parse("application/json; charset=utf-8")
    }

    val logger = LoggerWrapper(
        LoggerFactory.getLogger(ExternalServiceApiCommunicator::class.java),
        descriptor.name
    )

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

    suspend fun execute(
        externalApiMethod: String,
        url: String,
        builderContext: CustomRequestBuilder.() -> Unit
    ): TrimmedResponse {
        val requestBuilder = CustomRequestBuilder(descriptor.url).apply {
            _url(url)
            builderContext(this)
        }
        val testId = coroutineContext[TestCtxKey]?.testId?.toString()

        return suspendCoroutine {
            val submissionTime = System.currentTimeMillis()
            val serviceName = descriptor.name

            val req = requestBuilder
                .tag(CallContext::class.java, CallContext(serviceName, externalApiMethod, submissionTime))
                .build()

            if (req.method() != "GET") {
                logger.info("sending request to ${req.method()} ${req.url().url()}")
            }

            httpClientsManager.getClient(testId ?: externalApiMethod).newCall(req).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    it.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
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

        fun jsonPost(vararg items: Pair<String, String>) {
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

class CallContext(
    val serviceName: String,
    val externalApiEndpoint: String,
    val submissionTime: Long = System.currentTimeMillis(),
    var startTime: Long = System.currentTimeMillis(),
)

class HttpClientsManager {
    companion object {
        private val CALL_TIMEOUT = Duration.ofSeconds(60)
        private val READ_TIMEOUT = Duration.ofSeconds(60)
        private val WRITE_TIMEOUT = Duration.ofSeconds(60)
        private const val NUMBER_OF_CLIENTS = 16
        private const val NUMBER_OF_THREADS_PER_EXECUTOR = 32

        val logger = LoggerFactory.getLogger(HttpClientsManager::class.java)
    }

    private val clients = ConcurrentHashMap<Int, OkHttpClient>(25)
    private val dispatchers = ConcurrentHashMap<Int, Dispatcher>(25)

    fun getClient(testIdentifier: String): OkHttpClient {
        val hash = abs(testIdentifier.hashCode()) % NUMBER_OF_CLIENTS

        val dispatcher = dispatchers.computeIfAbsent(hash) {
            Executors.newFixedThreadPool(
                NUMBER_OF_THREADS_PER_EXECUTOR,
                NamedThreadFactory("external-service-executor-$hash")
            ).also {
                Metrics.executorServiceMonitoring(it, "external-service-executor-$hash")
            }.let {
                Dispatcher(it).also {
                    it.maxRequests = 1024
                    it.maxRequestsPerHost = 1024
                }
            }
        }

        return clients.computeIfAbsent(abs(testIdentifier.hashCode()) % NUMBER_OF_CLIENTS) {
            logger.info("Creating new http client for test $testIdentifier")
            OkHttpClient.Builder().run {
                dispatcher(dispatcher)
                // protocols(mutableListOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
                protocols(mutableListOf(Protocol.H2_PRIOR_KNOWLEDGE))
                callTimeout(CALL_TIMEOUT)
                readTimeout(READ_TIMEOUT)
                writeTimeout(WRITE_TIMEOUT)
                this.eventListener(object : EventListener() {

                    override fun callStart(call: Call) {
                        call.request().tag(CallContext::class.java)?.let {
                            Metrics
                                .withTags(
                                    "service" to it.serviceName,
                                    "method" to it.externalApiEndpoint
                                )
                                .timeHttpRequestLatent(System.currentTimeMillis() - it.submissionTime)
                        }
                    }

                    override fun callEnd(call: Call) {
                        call.request().tag(CallContext::class.java)?.let {
                            val endTime = System.currentTimeMillis()
                            Metrics
                                .withTags(
                                    "service" to it.serviceName,
                                    "method" to it.externalApiEndpoint,
                                    "result" to "OK"
                                )
                                .externalMethodDurationRecord(endTime - it.startTime)
                        }
                    }

                    override fun callFailed(call: Call, ioe: IOException) {
                        call.request().tag(CallContext::class.java)?.let {
                            val endTime = System.currentTimeMillis()
                            Metrics
                                .withTags(
                                    "service" to it.serviceName,
                                    "method" to it.externalApiEndpoint,
                                    "result" to "FAILED"
                                )
                                .externalMethodDurationRecord(endTime - it.startTime)
                        }
                    }
                })
                connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
                build()
            }
        }

    }


}