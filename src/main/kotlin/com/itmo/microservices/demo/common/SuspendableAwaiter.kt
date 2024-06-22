package com.itmo.microservices.demo.common

import com.itmo.microservices.demo.common.SuspendableAwaiter.MergeData.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class SuspendableAwaiter<Key, FirstClientValue, SecondClientValue>(
) {
    private sealed class MergeData<V1, V2> {
        data class FirstClientData<V1, V2>(
            val continuation: Continuation<V2?>,
            val valueForSecondClient: V1,
        ) : MergeData<V1, V2>()


        data class SecondClientData<V1, V2>(
            val continuation: Continuation<V1?>,
            val valueForFirstClient: V2,
        ) : MergeData<V1, V2>()

        class ValidKeyData<V1, V2> : MergeData<V1, V2>()
    }

    private val validKeyIndicator = ValidKeyData<FirstClientValue, SecondClientValue>()

    private val mergerData: MutableMap<Key, MergeData<FirstClientValue, SecondClientValue>> = ConcurrentHashMap()

    fun placeKey(key: Key) {
        mergerData.put(key, validKeyIndicator)?.let {
            error("Key $key already exists in merger")
        }
    }

    fun invalidateKey(key: Key) = mergerData.remove(key) != null

    suspend fun putSecondValueAndWaitForFirst(key: Key, value: SecondClientValue): FirstClientValue? = try {

        suspendCancellableCoroutine {
            val mergeData = SecondClientData<FirstClientValue, SecondClientValue>(it, value)

            val exhaustive = when (val existing = mergerData.put(key, mergeData)) {
                is FirstClientData -> {
                    existing.continuation.resume(value)
                    it.resume(existing.valueForSecondClient)
                }

                is ValidKeyData -> {
                    Unit // guarantee that the key exists
                }

                is SecondClientData -> {
                    it.resume(null)
                }

                null -> {
                }
            }
        }
    } finally {
        invalidateKey(key)
    }

    suspend fun putFirstValueAndWaitForSecond(key: Key, value: FirstClientValue): SecondClientValue? = try {
        suspendCancellableCoroutine {
            val mergeData = FirstClientData<FirstClientValue, SecondClientValue>(it, value)

            val exhaustive = when (val existing = mergerData.put(key, mergeData)) {
                is SecondClientData -> {
                    existing.continuation.resume(value)
                    it.resume(existing.valueForFirstClient)
                }

                is FirstClientData -> {
                    it.resume(null)
                }

                is ValidKeyData -> {
                    Unit
                }

                null -> {
                }
            }
        }
    } finally {
        invalidateKey(key)
    }

    fun size() = mergerData.size
}