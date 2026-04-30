package com.example.cloudstreamapp.data.source

import com.example.cloudstreamapp.domain.port.SourceRepositoryPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class SourceModule {

    @Binds
    @Singleton
    abstract fun bindSourceRepository(impl: SourceRepositoryImpl): SourceRepositoryPort
}
