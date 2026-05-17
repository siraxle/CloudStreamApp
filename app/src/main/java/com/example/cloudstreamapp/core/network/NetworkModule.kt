package com.example.cloudstreamapp.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(@ApplicationContext context: Context): OkHttpClient {
        val httpCache = Cache(File(context.cacheDir, "http"), 50L * 1024 * 1024)

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }

        val cookieManager = CookieManager().apply { setCookiePolicy(CookiePolicy.ACCEPT_ALL) }
        val cookieJar = object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                val uri = URI(url.toString())
                cookies.forEach { c ->
                    val jc = HttpCookie(c.name, c.value).apply {
                        domain = c.domain
                        path = c.path
                        secure = c.secure
                        isHttpOnly = c.httpOnly
                    }
                    cookieManager.cookieStore.add(uri, jc)
                }
            }
            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                val uri = URI(url.toString())
                return cookieManager.cookieStore.get(uri).mapNotNull { jc ->
                    Cookie.Builder()
                        .name(jc.name ?: return@mapNotNull null)
                        .value(jc.value ?: return@mapNotNull null)
                        .domain(url.host)
                        .path(jc.path ?: "/")
                        .build()
                }
            }
        }

        return OkHttpClient.Builder()
            .cache(httpCache)
            .cookieJar(cookieJar)
            .addInterceptor(RetryInterceptor())
            .addInterceptor(logging)
            .build()
    }
}
