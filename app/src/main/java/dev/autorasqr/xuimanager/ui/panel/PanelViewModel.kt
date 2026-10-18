package dev.autorasqr.xuimanager.ui.panel

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.domain.model.AppError
import dev.autorasqr.xuimanager.domain.model.PanelWebSession
import dev.autorasqr.xuimanager.domain.repository.PanelWebRepository
import dev.autorasqr.xuimanager.ui.common.asAppError
import dev.autorasqr.xuimanager.ui.navigation.PanelRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface PanelUiState {
    data object Loading : PanelUiState
    data class Ready(val session: PanelWebSession) : PanelUiState
    data class Failed(val error: AppError) : PanelUiState
}

@HiltViewModel
class PanelViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: PanelWebRepository,
) : ViewModel() {

    private val serverId = savedStateHandle.toRoute<PanelRoute>().serverId
    private val _state = MutableStateFlow<PanelUiState>(PanelUiState.Loading)
    val state: StateFlow<PanelUiState> = _state.asStateFlow()

    init {
        load()
    }

    /** Signs in again, e.g. after the session expired inside the WebView. */
    fun load() {
        _state.value = PanelUiState.Loading
        viewModelScope.launch {
            _state.value = try {
                PanelUiState.Ready(repository.openSession(serverId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PanelUiState.Failed(e.asAppError())
            }
        }
    }
}
