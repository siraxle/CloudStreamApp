package com.example.cloudstreamapp.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.cloudstreamapp.domain.model.CloudSource
import com.example.cloudstreamapp.domain.usecase.AddSourceUseCase
import com.example.cloudstreamapp.domain.usecase.GetSourcesUseCase
import com.example.cloudstreamapp.domain.port.SourceRepositoryPort
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class HomeViewModel @Inject constructor(
    getSources: GetSourcesUseCase,
    private val addSource: AddSourceUseCase,
    private val sourceRepo: SourceRepositoryPort,
) : ViewModel() {

    val sources: StateFlow<List<CloudSource>> = getSources()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun addUrl(url: String, customName: String? = null) {
        viewModelScope.launch {
            _uiState.value = UiState.Loading
            _uiState.value = when (val result = addSource(url, customName)) {
                is AddSourceUseCase.Result.Success -> UiState.Idle
                is AddSourceUseCase.Result.AlreadyExists -> UiState.AlreadyExists(result.source)
                is AddSourceUseCase.Result.Error -> UiState.Error(result.message)
            }
        }
    }

    fun renameSource(id: String, newName: String) {
        viewModelScope.launch {
            val source = sourceRepo.getById(id) ?: return@launch
            sourceRepo.update(source.copy(name = newName.trim().ifBlank { null }))
        }
    }

    fun deleteSource(id: String) {
        viewModelScope.launch { sourceRepo.delete(id) }
    }

    fun dismissError() {
        _uiState.value = UiState.Idle
    }

    sealed class UiState {
        object Idle : UiState()
        object Loading : UiState()
        data class AlreadyExists(val source: CloudSource) : UiState()
        data class Error(val message: String) : UiState()
    }
}
