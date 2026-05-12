package com.example.cloudstreamapp.core.playlist

import android.content.Context
import android.net.Uri
import com.example.cloudstreamapp.domain.model.PlaylistExportData
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaylistImportExportManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val gson = Gson()

    fun writeToUri(uri: Uri, data: PlaylistExportData) {
        context.contentResolver.openOutputStream(uri)?.use { stream ->
            stream.bufferedWriter().use { writer ->
                gson.toJson(data, writer)
            }
        }
    }

    fun parseFromUri(uri: Uri): PlaylistExportData? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.bufferedReader().use { reader ->
                val data = gson.fromJson(reader, PlaylistExportData::class.java)
                // Basic validation
                if (data?.name == null || data.tracks == null) null else data
            }
        }
    }.getOrNull()
}
