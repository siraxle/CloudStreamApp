package com.example.cloudstreamapp.data.playlist

import com.example.cloudstreamapp.domain.port.FavoritePlaylistRepositoryPort
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class FavoritePlaylistModule {

    @Binds
    @Singleton
    abstract fun bindFavoritePlaylistRepository(
        impl: FavoritePlaylistRepositoryImpl,
    ): FavoritePlaylistRepositoryPort
}
