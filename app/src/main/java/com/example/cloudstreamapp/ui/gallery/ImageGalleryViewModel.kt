package com.example.cloudstreamapp.ui.gallery

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.utils.isImageFile
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType
import com.example.cloudstreamapp.domain.usecase.GetStreamUrlUseCase
import com.example.cloudstreamapp.domain.usecase.ListFolderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class ImageGalleryViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listFolder: ListFolderUseCase,
    private val getStreamUrl: GetStreamUrlUseCase,
) : ViewModel() {

    private val _images = MutableStateFlow<List<CloudItem>>(emptyList())
    val images: StateFlow<List<CloudItem>> = _images.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // In-memory cache: item.id → resolved stream URL
    private val urlCache = mutableMapOf<String, String>()

    init {
        val cloudTypeStr = savedStateHandle.get<String>("cloudType")
        val sourceUrl = savedStateHandle.get<String>("encodedSourceUrl")
        val folderPath = savedStateHandle.get<String>("encodedFolderPath")

        if (cloudTypeStr != null && sourceUrl != null && folderPath != null) {
            loadImages(cloudTypeStr, sourceUrl, folderPath)
        } else {
            _isLoading.value = false
        }
    }

    private fun loadImages(cloudTypeStr: String, sourceUrl: String, folderPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val cloudType = CloudType.valueOf(cloudTypeStr)
                val path = CloudPath(sourceId = sourceUrl, relativePath = folderPath, cloudType = cloudType)
                val allItems = listFolder(path).first()

                // Check current folder first
                val imagesInRoot = allItems
                    .filter { it.type == CloudItem.ItemType.FILE && it.name.isImageFile() }
                    .sortedBy { it.name.lowercase() }

                if (imagesInRoot.isNotEmpty()) {
                    withContext(Dispatchers.Main) { _images.value = imagesInRoot }
                    return@launch
                }

                // No images in root — check immediate subfolders in parallel (up to 8)
                val subfolders = allItems
                    .filter { it.type == CloudItem.ItemType.DIRECTORY }
                    .take(8)

                if (subfolders.isNotEmpty()) {
                    val found = coroutineScope {
                        subfolders.map { dir ->
                            async {
                                runCatching {
                                    listFolder(dir.path).first()
                                        .filter { it.type == CloudItem.ItemType.FILE && it.name.isImageFile() }
                                        .sortedBy { it.name.lowercase() }
                                }.getOrDefault(emptyList())
                            }
                        }.awaitAll()
                    }

                    val subImages = found.firstOrNull { it.isNotEmpty() } ?: emptyList()
                    withContext(Dispatchers.Main) { _images.value = subImages }
                }
            } catch (_: Exception) {
                // leave empty list
            } finally {
                withContext(Dispatchers.Main) { _isLoading.value = false }
            }
        }
    }

    /** Returns the stream URL for an image item, resolving it on first access and caching in memory. */
    suspend fun resolveUrl(item: CloudItem): String? {
        urlCache[item.id]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { getStreamUrl(item) }.getOrNull()
                ?.also { urlCache[item.id] = it }
        }
    }
}
