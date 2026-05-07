package com.example.cloudstreamapp.ui.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.cache.MediaCacheManager
import com.example.cloudstreamapp.core.utils.isMediaFile
import com.example.cloudstreamapp.data.playlist.PlaylistRepositoryImpl
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.Playlist
import com.example.cloudstreamapp.domain.port.SourceRepositoryPort
import com.example.cloudstreamapp.domain.usecase.ListFolderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listFolder: ListFolderUseCase,
    private val sourceRepo: SourceRepositoryPort,
    private val playlistRepo: PlaylistRepositoryImpl,
    private val cacheManager: MediaCacheManager,
) : ViewModel() {

    private val sourceId: String = checkNotNull(savedStateHandle["sourceId"])
    private val initialPath: String = savedStateHandle["encodedPath"] ?: "root"

    private val _pathStack = MutableStateFlow<List<String>>(listOf(initialPath))
    val pathStack: StateFlow<List<String>> = _pathStack.asStateFlow()

    val currentPath: String get() = _pathStack.value.last()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _sortOrder = MutableStateFlow(SortOrder.NAME_ASC)
    val sortOrder: StateFlow<SortOrder> = _sortOrder.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<CloudItem>> = combine(_pathStack, _sortOrder) { stack, sort ->
        Pair(stack, sort)
    }
        .flatMapLatest { (stack, sort) ->
            val path = stack.last()
            val source = sourceRepo.getById(sourceId)
                ?: return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList())
            val cloudPath = CloudPath(sourceId = source.url, relativePath = path, cloudType = source.provider)
            listFolder(cloudPath)
                .onStart { _isLoading.value = true }
                .onEach { _isLoading.value = false }
                .map { list ->
                    val dirFirst = compareBy<CloudItem> { it.type != CloudItem.ItemType.DIRECTORY }
                    when (sort) {
                        SortOrder.NAME_ASC -> list.sortedWith(dirFirst.thenBy { it.name.lowercase() })
                        SortOrder.NAME_DESC -> list.sortedWith(dirFirst.thenByDescending { it.name.lowercase() })
                        SortOrder.SIZE_ASC -> list.sortedWith(dirFirst.thenBy { it.sizeBytes ?: 0L })
                        SortOrder.SIZE_DESC -> list.sortedWith(dirFirst.thenByDescending { it.sizeBytes ?: 0L })
                        SortOrder.TYPE -> list.sortedWith(
                            dirFirst
                                .thenBy { it.name.substringAfterLast('.').lowercase() }
                                .thenBy { it.name.lowercase() }
                        )
                    }
                }
                .catch { e ->
                    _isLoading.value = false
                    _error.value = e.message ?: "Неизвестная ошибка"
                    emit(emptyList())
                }
        }
        .map { list ->
            list.map { item ->
                if (item.type == CloudItem.ItemType.FILE) {
                    item.copy(cacheStatus = cacheManager.getCacheStatus(item.id, item.sizeBytes))
                } else item
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun navigateTo(path: String) {
        _pathStack.value = _pathStack.value + path
    }

    fun navigateUp(): Boolean {
        val stack = _pathStack.value
        if (stack.size <= 1) return false
        _pathStack.value = stack.dropLast(1)
        return true
    }

    fun navigateToIndex(index: Int) {
        val stack = _pathStack.value
        if (index < stack.size) {
            _pathStack.value = stack.take(index + 1)
        }
    }

    fun setSortOrder(order: SortOrder) { _sortOrder.value = order }
    fun dismissError() { _error.value = null }

    // Playlists
    val playlists: StateFlow<List<Playlist>> = playlistRepo.getAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _playlistMessage = MutableStateFlow<String?>(null)
    val playlistMessage: StateFlow<String?> = _playlistMessage.asStateFlow()

    fun addToPlaylist(item: CloudItem, playlistId: String) {
        viewModelScope.launch {
            playlistRepo.saveMediaAndAddToPlaylist(item, playlistId)
            _playlistMessage.value = "«${item.name}» добавлен в плейлист"
        }
    }

    fun addFolderToPlaylist(folder: CloudItem, playlistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val mediaFiles = collectMediaFiles(folder.path)
            mediaFiles.forEach { playlistRepo.saveMediaAndAddToPlaylist(it, playlistId) }
            _playlistMessage.value = "Добавлено ${mediaFiles.size} файлов из «${folder.name}»"
        }
    }

    private suspend fun collectMediaFiles(path: CloudPath, depth: Int = 0): List<CloudItem> {
        if (depth > 10) return emptyList()
        val items = listFolder(path).first()
        val files = items.filter { it.type == CloudItem.ItemType.FILE && it.name.isMediaFile() }
        val subFiles = items
            .filter { it.type == CloudItem.ItemType.DIRECTORY }
            .flatMap { collectMediaFiles(it.path, depth + 1) }
        return files + subFiles
    }

    fun createPlaylistAndAdd(item: CloudItem, name: String) {
        viewModelScope.launch {
            val playlist = Playlist(
                id = UUID.randomUUID().toString(),
                name = name,
                coverPath = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            playlistRepo.create(playlist)
            playlistRepo.saveMediaAndAddToPlaylist(item, playlist.id)
            _playlistMessage.value = "Плейлист «$name» создан"
        }
    }

    fun createPlaylistAndAddFolder(folder: CloudItem, name: String) {
        viewModelScope.launch {
            val playlist = Playlist(
                id = UUID.randomUUID().toString(),
                name = name,
                coverPath = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            playlistRepo.create(playlist)
            addFolderToPlaylist(folder, playlist.id)
        }
    }

    fun dismissPlaylistMessage() { _playlistMessage.value = null }
}

enum class SortOrder(val label: String) {
    NAME_ASC("По имени А-Я"),
    NAME_DESC("По имени Я-А"),
    SIZE_ASC("По размеру ↑"),
    SIZE_DESC("По размеру ↓"),
    TYPE("По типу"),
}
