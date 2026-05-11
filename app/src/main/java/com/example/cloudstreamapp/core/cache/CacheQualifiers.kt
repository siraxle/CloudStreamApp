package com.example.cloudstreamapp.core.cache

import javax.inject.Qualifier

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class PermanentMediaCache

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class TempMediaCache
