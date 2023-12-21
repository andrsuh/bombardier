package com.itmo.microservices.demo.common

import java.util.concurrent.atomic.AtomicInteger

class OngoingWindow(
    private val maxWinSize: Int
) {
    private val winSize = AtomicInteger()
 
    fun putIntoWindow(): WindowResponse {
        while (true) {
            val currentWinSize = winSize.get()
            if (currentWinSize >= maxWinSize) {
                return WindowResponse.Fail(currentWinSize)
            }
 
            if (winSize.compareAndSet(currentWinSize, currentWinSize + 1)) {
                break
            }
        }
        return WindowResponse.Success(winSize.get())
    }
 
    fun releaseWindow() = winSize.decrementAndGet()
 
 
    sealed class WindowResponse(val currentWinSize: Int) {
        public class Success(
            currentWinSize: Int
        ) : WindowResponse(currentWinSize)
 
        public class Fail(
            currentWinSize: Int
        ) : WindowResponse(currentWinSize)
    }
}