package com.example.cloudstreamapp.core.playlist

import android.content.Context
import android.net.Uri
import com.example.cloudstreamapp.domain.model.PlaylistBundleData
import com.example.cloudstreamapp.domain.model.PlaylistExportData
import com.google.gson.Gson
import com.google.gson.JsonObject
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImportExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    sealed class ParseResult {
        data class Single(val data: PlaylistExportData) : ParseResult()
        data class Bundle(val playlists: List<PlaylistBundleData.PlaylistEntry>) : ParseResult()
    }

    fun writeToUri(uri: Uri, data: PlaylistExportData) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.bufferedWriter().use { writer ->
                gson.toJson(data, writer)
            }
        }
    }

    fun writeBundleToUri(uri: Uri, playlists: List<PlaylistBundleData.PlaylistEntry>) {
        val bundle = PlaylistBundleData(exportedAt = System.currentTimeMillis(), playlists = playlists)
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.bufferedWriter().use { writer ->
                gson.toJson(bundle, writer)
            }
        }
    }

    fun parseFromUri(uri: Uri): PlaylistExportData? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().use { reader ->
                val data = gson.fromJson(reader, PlaylistExportData::class.java)
                if (data?.name == null || data.tracks == null) null else data
            }
        }
    }.getOrNull()

    fun parseMultiFromUri(uri: Uri): ParseResult? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().use { reader ->
                val json = gson.fromJson(reader, JsonObject::class.java) ?: return@use null
                val version = json["version"]?.asInt ?: 1
                if (version >= 2) {
                    val bundle = gson.fromJson(json, PlaylistBundleData::class.java)
                    if (bundle?.playlists == null) null
                    else ParseResult.Bundle(bundle.playlists)
                } else {
                    val data = gson.fromJson(json, PlaylistExportData::class.java)
                    if (data?.name == null || data.tracks == null) null
                    else ParseResult.Single(data)
                }
            }
        }
    }.getOrNull()
}
