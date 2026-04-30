package com.example.cloudstreamapp.ui.browser

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.port.SourceRepositoryPort
import com.example.cloudstreamapp.domain.usecase.ListFolderUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BrowserViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val listFolder: ListFolderUseCase,
    private val sourceRepo: SourceRepositoryPort,
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val items: StateFlow<List<CloudItem>> = _pathStack
        .flatMapLatest { stack ->
            val path = stack.last()
            val source = sourceRepo.getById(sourceId)
                ?: return@flatMapLatest kotlinx.coroutines.flow.flowOf(emptyList())
            // Use source.url as the public key for cloud API calls, not source.id (UUID)
            val cloudPath = CloudPath(sourceId = source.url, relativePath = path, cloudType = source.provider)
            listFolder(cloudPath)
                .onStart { _isLoading.value = true }
                .onEach { _isLoading.value = false }
                .map { list ->
                    list.sortedWith(
                        compareBy<CloudItem> { it.type != CloudItem.ItemType.DIRECTORY }
                            .thenBy { it.name.lowercase() }
                    )
                }
                .catch { e ->
                    _isLoading.value = false
                    _error.value = e.message ?: "Неизвестная ошибка"
                    emit(emptyList())
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

    fun dismissError() { _error.value = null }
}
