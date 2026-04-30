package com.example.cloudstreamapp.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.core.database.dao.MediaMetadataDao
import com.example.cloudstreamapp.domain.model.CacheStatus
import com.example.cloudstreamapp.domain.model.CloudItem
import com.example.cloudstreamapp.domain.model.CloudPath
import com.example.cloudstreamapp.domain.model.CloudType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val metadataDao: MediaMetadataDao,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val results: StateFlow<List<CloudItem>> = _query
        .debounce(300)
        .flatMapLatest { q ->
            if (q.isBlank()) flowOf(emptyList())
            else metadataDao.search("%$q%").map { list ->
                list.map { entity ->
                    CloudItem(
                        id = entity.id,
                        name = entity.title ?: entity.path.substringAfterLast('/'),
                        path = CloudPath(
                            sourceId = entity.sourceId,
                            relativePath = entity.path,
                            cloudType = runCatching {
                                CloudType.valueOf(entity.cloudType)
                            }.getOrDefault(CloudType.HTTP),
                        ),
                        type = CloudItem.ItemType.FILE,
                        mimeType = entity.mimeType,
                        sizeBytes = entity.sizeBytes,
                        durationMs = entity.durationMs,
                        cacheStatus = CacheStatus.REMOTE,
                    )
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onQueryChange(value: String) { _query.value = value }
    fun clearQuery() { _query.value = "" }
}
