package com.example.cloudstreamapp.core.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

class RetryInterceptor(private val maxRetries: Int = 4) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        // Don't retry non-idempotent methods (POST, PATCH, etc.)
        if (request.method != "GET" && request.method != "HEAD") {
            return chain.proceed(request)
        }
        var attempt = 0
        var lastException: IOException? = null
        while (attempt <= maxRetries) {
            try {
                val response = chain.proceed(request)
                if (response.isSuccessful || attempt == maxRetries) return response
                response.close()
            } catch (e: IOException) {
                lastException = e
                if (attempt == maxRetries) throw e
            }
            // Exponential backoff: 1s, 2s, 4s, 8s
            Thread.sleep(1000L shl attempt)
            attempt++
        }
        throw lastException ?: IOException("Request failed after $maxRetries retries")
    }
}
