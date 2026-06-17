package com.example.cloudstreamapp.core.utils

import com.example.cloudstreamapp.domain.model.CueSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CueSheetFetcher @Inject constructor(private val okHttpClient: OkHttpClient) {

    suspend fun fetch(url: String): CueSheet? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        val body = runCatching {
            okHttpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        }.getOrNull() ?: return@withContext null
        CueSheetParser.parse(body)
    }
}
