package com.example.cloudstreamapp.ui.torrent

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.data.torrent.TorrentCloudProvider
import com.example.cloudstreamapp.data.torrent.TorrentRepository
import com.example.cloudstreamapp.data.torrent.provider.TorrentSource
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudResult
import com.example.cloudstreamapp.domain.torrent.TorrentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TorrentBrowserViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val provider: TorrentCloudProvider,
    private val repository: TorrentRepository,
) : ViewModel() {

    sealed class UiState {
        object Idle : UiState()
        data class Searching(val query: String) : UiState()
        data class SearchResults(
            val query: String,
            val results: List<TorrentResult>,
            val activeFilter: TorrentSource?,
        ) : UiState()
        data class ResolvingMagnet(val name: String) : UiState()
        data class FileList(
            val items: List<CloudItem>,
            val infoHash: String,
            val magnetUri: String,
        ) : UiState()
        data class Error(val message: String) : UiState()
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    // Full unfiltered results kept in memory for instant re-filtering without a new network call
    private var fullResults: List<TorrentResult> = emptyList()
    private var currentMagnetUri: String = ""

    init {
        savedStateHandle.get<String>("magnetUri")?.let { openMagnet(Uri.decode(it)) }
    }

    fun onQueryChanged(value: String) {
        _query.value = value
    }

    fun search(queryOverride: String? = null) {
        val q = (queryOverride ?: _query.value).trim()
        if (q.isBlank()) return

        // If the input looks like a magnet link, resolve it directly
        if (q.startsWith("magnet:")) {
            openMagnet(q)
            return
        }

        _uiState.value = UiState.Searching(q)
        viewModelScope.launch {
            val results = runCatching { repository.search(q) }.getOrElse { emptyList() }
            fullResults = results
            _uiState.value = UiState.SearchResults(q, results, activeFilter = null)
        }
    }

    fun filterBySource(source: TorrentSource?) {
        val current = _uiState.value as? UiState.SearchResults ?: return
        val filtered = if (source == null) fullResults
                       else fullResults.filter { it.source == source.displayName }
        _uiState.value = current.copy(results = filtered, activeFilter = source)
    }

    fun openTorrentResult(result: TorrentResult) {
        _uiState.value = UiState.ResolvingMagnet(result.name)
        currentMagnetUri = result.magnetUri
        viewModelScope.launch {
            when (val r = runCatching { provider.resolve(result.magnetUri) }.getOrElse {
                CloudResult.Error(it.message ?: "Failed to resolve torrent")
            }) {
                is CloudResult.FolderResult ->
                    _uiState.value = UiState.FileList(r.items, r.path.relativePath, result.magnetUri)
                is CloudResult.FileResult ->
                    _uiState.value = UiState.FileList(
                        listOf(r.item),
                        r.item.id.substringBefore(":"),
                        result.magnetUri,
                    )
                is CloudResult.Error ->
                    _uiState.value = UiState.Error(r.message)
            }
        }
    }

    fun openMagnet(magnetUri: String = _query.value.trim()) {
        if (magnetUri.isBlank()) return
        currentMagnetUri = magnetUri
        _uiState.value = UiState.ResolvingMagnet(magnetUri.substringAfter("dn=").substringBefore("&").let {
            Uri.decode(it).ifBlank { "Torrent" }
        })
        viewModelScope.launch {
            when (val r = runCatching { provider.resolve(magnetUri) }.getOrElse {
                CloudResult.Error(it.message ?: "Failed to resolve magnet")
            }) {
                is CloudResult.FolderResult ->
                    _uiState.value = UiState.FileList(r.items, r.path.relativePath, magnetUri)
                is CloudResult.FileResult ->
                    _uiState.value = UiState.FileList(
                        listOf(r.item),
                        r.item.id.substringBefore(":"),
                        magnetUri,
                    )
                is CloudResult.Error ->
                    _uiState.value = UiState.Error(r.message)
            }
        }
    }

    fun backToResults() {
        if (fullResults.isNotEmpty()) {
            val q = (_uiState.value as? UiState.FileList)?.let { _query.value } ?: _query.value
            _uiState.value = UiState.SearchResults(q, fullResults, activeFilter = null)
        } else {
            _uiState.value = UiState.Idle
        }
    }

    fun currentMagnetUri(): String = currentMagnetUri
}
