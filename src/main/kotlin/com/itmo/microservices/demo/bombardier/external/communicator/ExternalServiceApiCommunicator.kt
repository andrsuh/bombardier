package com.itmo.microservices.demo.bombardier.external.communicator

import com.itmo.microservices.demo.bombardier.BombardierProperties
import com.itmo.microservices.demo.bombardier.ServiceDescriptor
import com.itmo.microservices.demo.bombardier.flow.TestCtxKey
import com.itmo.microservices.demo.common.logging.LoggerWrapper
import okhttp3.*
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import java.net.URL
import java.time.Duration
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine
import com.itmo.microservices.demo.common.metrics.Metrics
import com.itmo.microservices.demo.common.metrics.PromMetrics
import io.micrometer.core.instrument.util.NamedThreadFactory
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpClient.Version.HTTP_2
import java.net.http.HttpRequest
import java.net.http.HttpRequest.BodyPublishers
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.nio.channels.SelectionKey
import java.nio.channels.Selector
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.abs


data class TrimmedResponse(
    private val body: String,
    private val code: Int,
    private val req: HttpRequest
) {
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
        private val JSON = "application/json; charset=utf-8".toMediaTypeOrNull()
        private val httpClientCallbackExecutor = Executors.newFixedThreadPool(
            16,
            NamedThreadFactory("http-client-callback-executor")
        ).also {
            Metrics.executorServiceMonitoring(it, "http-client-callback-executor")
        }
    }

    val logger = LoggerWrapper(
        LoggerFactory.getLogger(ExternalServiceApiCommunicator::class.java),
        descriptor.name
    )

    open suspend fun authenticate(username: String, password: String) = execute("authenticate", "/authentication") {
        POST(
            BodyPublishers.ofString(
            mapper.writeValueAsString(mapOf("name" to username, "password" to password))
        ))
        header(HttpHeaders.CONTENT_TYPE, "application/json")
    }.run {
        mapper.readValue(body(), TokenResponse::class.java).toExternalServiceToken(descriptor.url)
    }

    protected suspend fun reauthenticate(token: ExternalServiceToken) =
        execute("reauthenticate", "/authentication/refresh") {
            assert(!token.isRefreshTokenExpired())
            POST(BodyPublishers.noBody())
            header(HttpHeaders.CONTENT_TYPE, "application/json")
            header(HttpHeaders.AUTHORIZATION, "Bearer ${token.refreshToken}")
        }.run {
            mapper.readValue(body(), TokenResponse::class.java).toExternalServiceToken(descriptor.url)
        }

    suspend fun execute(method: String, url: String) = execute(method, url) {}

    suspend fun execute(
        externalApiMethod: String,
        url: String,
        builderContext: HttpRequest.Builder.() -> Unit
    ): TrimmedResponse {
//        val requestBuilder = CustomRequestBuilder(descriptor.url).apply {
//            _url(url)
//            builderContext(this)
//        }

        val finalUrl = "${descriptor.url}$url"
        val req = HttpRequest.newBuilder()
            .uri(URI(finalUrl))
//            .POST(HttpRequest.BodyPublishers.noBody())
            .apply {
//            _url(url)
            builderContext(this)
        }.build()

        val testId = coroutineContext[TestCtxKey]?.testId?.toString() ?: UUID.randomUUID().toString()
        val testStartTime = coroutineContext[TestCtxKey]?.testStartTime ?: -1

        return suspendCoroutine {
            val submissionTime = System.currentTimeMillis()
            val serviceName = descriptor.name

//            val req = requestBuilder
//                .tag(CallContext::class.java, CallContext(serviceName, externalApiMethod, submissionTime))
//                .build()


//            if (req.method() != "GET") {
//                logger.info("sending request to ${req.method()} ${req.url().url()}")
//            }

            PromMetrics.timeHttpRequestLatent(serviceName, externalApiMethod, System.currentTimeMillis() - testStartTime)

//            httpClientsManager.getClient(externalApiMethod).sendAsync(req, HttpResponse.BodyHandlers.ofString())
            httpClientsManager.getClient(testId).sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenApplyAsync({ resp ->
                    if (HttpStatus.Series.resolve(resp.statusCode()) == HttpStatus.Series.SUCCESSFUL) {
                        PromMetrics.externalMethodDurationRecord(
                            serviceName,
                            externalApiMethod,
                            "OK",
                            System.currentTimeMillis() - submissionTime
                        )
                        try {
                            it.resume(TrimmedResponse(resp.body(), resp.statusCode(), req))
                        } catch (t: Throwable) {
                            it.resumeWithException(t)
                        }
                    } else {
                        PromMetrics.externalMethodDurationRecord(
                            serviceName,
                            externalApiMethod, "CODE-${resp.statusCode()}", System.currentTimeMillis() - submissionTime
                        )

                        it.resumeWithException(
                            InvalidExternalServiceResponseException(
                                resp.statusCode(),
                                "${resp.request().method()} ${
                                    resp.request().uri()
                                } External service returned non-OK code: ${resp.statusCode()}\n\n${resp.body()}"
                            )
                        )
                    }
                }, httpClientCallbackExecutor)
                .exceptionallyAsync({ th ->
                    PromMetrics.externalMethodDurationRecord(
                        serviceName,
                        externalApiMethod, "FAILED", System.currentTimeMillis() - submissionTime
                    )
                    it.resumeWithException(th)
                }, httpClientCallbackExecutor)


//            httpClientsManager.getClient(externalApiMethod).newCall(req).enqueue(object : Callback {
//                override fun onFailure(call: Call, e: IOException) {
//                }
//
//                override fun onResponse(call: Call, response: Response) {
//
//                }
//            })
        }
    }

    suspend fun executeWithAuth(method: String, url: String, credentials: ExternalServiceToken) =
        executeWithAuth(method, url, credentials) {}

    suspend fun executeWithAuth(
        method: String,
        url: String,
        credentials: ExternalServiceToken,
        builderContext: HttpRequest.Builder.() -> Unit
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

//        fun jsonPost(vararg items: Pair<String, String>) {
//            post(RequestBody.create(JSON, mapper.writeValueAsString(items.toMap())));
//        }

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
        private val CALL_TIMEOUT = Duration.ofSeconds(10)
        private val READ_TIMEOUT = Duration.ofSeconds(10)
        private val WRITE_TIMEOUT = Duration.ofSeconds(10)
        private const val NUMBER_OF_CLIENTS = 5
        private const val NUMBER_OF_THREADS_PER_EXECUTOR = 16

        val logger = LoggerFactory.getLogger(HttpClientsManager::class.java)
    }

    private val clients = ConcurrentHashMap<Int, HttpClient>(5)
    private val dispatchers = ConcurrentHashMap<Int, Dispatcher>(5)
    private val executors = ConcurrentHashMap<Int, ExecutorService>(5)

    fun getClient(testIdentifier: String): HttpClient {
        val hash = abs(testIdentifier.hashCode()) % NUMBER_OF_CLIENTS

        executors.computeIfAbsent(hash) {
            Executors.newFixedThreadPool(
                NUMBER_OF_THREADS_PER_EXECUTOR,
                NamedThreadFactory("external-service-executor-$hash")
            ).also {
                Metrics.executorServiceMonitoring(it, "external-service-executor-$hash")
            }
//                .also {
//                Metrics.executorServiceMonitoring(it, "external-service-executor-$hash")
//            }.let {
//                Dispatcher(it).also {
//                    it.maxRequests = 15000
//                    it.maxRequestsPerHost = 15000
//                }
//            }
        }

        return clients.computeIfAbsent(hash) {
            logger.info("Creating new http client for test $testIdentifier")
            HttpClient.newBuilder()
                .executor(executors[hash])
                .version(HTTP_2)
//                .sslContext(SSLContext.getDefault())
                .build()
        }
    }

//    fun getClient(testIdentifier: String): HttpClient {
//        val hash = abs(testIdentifier.hashCode()) % NUMBER_OF_CLIENTS
//
//        executors.computeIfAbsent(hash) {
//            Executors.newFixedThreadPool(
//                NUMBER_OF_THREADS_PER_EXECUTOR,
//                NamedThreadFactory("external-service-executor-$hash")
//            ).also {
//                Metrics.executorServiceMonitoring(it, "external-service-executor-$hash")
//            }
////                .also {
////                Metrics.executorServiceMonitoring(it, "external-service-executor-$hash")
////            }.let {
////                Dispatcher(it).also {
////                    it.maxRequests = 15000
////                    it.maxRequestsPerHost = 15000
////                }
////            }
//        }
//
//        return clients.computeIfAbsent(hash) {
//            logger.info("Creating new http client for test $testIdentifier")
//            HttpClient.newBuilder()
//                .executor(executors[hash])
//                .version(HTTP_2)
////                .sslContext(SSLContext.getDefault())
//                .build()
//        }

//        return clients.computeIfAbsent(abs(testIdentifier.hashCode()) % NUMBER_OF_CLIENTS) {
//            logger.info("Creating new http client for test $testIdentifier")
//            OkHttpClient.Builder().run {
//                dispatcher(dispatcher)
//                // protocols(mutableListOf(Protocol.HTTP_1_1, Protocol.HTTP_2))
//                protocols(mutableListOf(Protocol.H2_PRIOR_KNOWLEDGE))
//                callTimeout(CALL_TIMEOUT)
//                readTimeout(READ_TIMEOUT)
//                writeTimeout(WRITE_TIMEOUT)
//                this.eventListener(object : EventListener() {
//
//                    override fun callStart(call: Call) {
//                        call.request().tag(CallContext::class.java)?.let {
//                            Metrics
//                                .withTags(
//                                    "service" to it.serviceName,
//                                    "method" to it.externalApiEndpoint
//                                )
//                                .timeHttpRequestLatent(System.currentTimeMillis() - it.submissionTime)
//                        }
//                    }
//
//                    override fun callEnd(call: Call) {
//                        call.request().tag(CallContext::class.java)?.let {
//                            val endTime = System.currentTimeMillis()
//                            Metrics
//                                .withTags(
//                                    "service" to it.serviceName,
//                                    "method" to it.externalApiEndpoint,
//                                    "result" to "OK"
//                                )
//                                .externalMethodDurationRecord(endTime - it.startTime)
//                        }
//                    }
//
//                    override fun callFailed(call: Call, ioe: IOException) {
//                        call.request().tag(CallContext::class.java)?.let {
//                            val endTime = System.currentTimeMillis()
//                            Metrics
//                                .withTags(
//                                    "service" to it.serviceName,
//                                    "method" to it.externalApiEndpoint,
//                                    "result" to "FAILED"
//                                )
//                                .externalMethodDurationRecord(endTime - it.startTime)
//                        }
//                    }
//                })
//                connectionPool(ConnectionPool(20, 30, TimeUnit.SECONDS))
//                build()
//            }
//        }

//    }


    class Reactor : Runnable {
        private val selector = Selector.open()
        private val socket = ServerSocketChannel.open().also {
            it.bind(InetSocketAddress(5555))
            it.configureBlocking(false)
        }

        init {
            socket.register(selector, SelectionKey.OP_ACCEPT).attach(Acceptor())
        }

        override fun run() {
            try {
                while (!Thread.interrupted()) {
                    selector.select()
                    val selected = selector.selectedKeys()

                    selected.forEach {
                        dispatch(it)
                    }

                    selected.clear()
                }
            } catch (_: IOException) {
            }
        }

        private fun dispatch(k: SelectionKey) {
            (k.attachment() as Runnable?)?.run()
        }

        inner class Acceptor {
            fun run() {
                try {
                    this@Reactor.socket.accept()?.let {
                        Handler(selector, it)
                    }
                } catch (_: IOException) {
                }
            }
        }
    }


    class Handler(
        val sel: Selector,
        val socket: SocketChannel
    ) {

        var input: ByteBuffer = ByteBuffer.allocate(2048)
        var output: ByteBuffer = ByteBuffer.allocate(2048)

        val READING: Int = 0
        val SENDING: Int = 1
        var state: Int = READING

        init {
            socket.configureBlocking(false)
        }

        val sk = socket.register(sel, 0).also {
            it.attach(this)
            it.interestOps(SelectionKey.OP_READ)
            sel.wakeup()
        }

        fun run() {
            try {
                if (state == READING) read()
                else if (state == SENDING) send()
            } catch (ex: IOException) {
            }
        }

        fun read() {
            socket.read(input)
            if (inputIsComplete()) {
                process()
            }
            state = SENDING
            sk.interestOps(SelectionKey.OP_WRITE)
        }

        fun send() {
            socket.write(output)
            if (outputIsComplete()) sk.cancel()
        }

        fun inputIsComplete(): Boolean {
            return false
        }

        fun outputIsComplete(): Boolean {
            return false
        }

        fun process() {
        }
    }
}
