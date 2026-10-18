package dev.autorasqr.xuimanager.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.autorasqr.xuimanager.domain.model.Server
import dev.autorasqr.xuimanager.domain.repository.ServerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface HomeTarget {
    data object Onboarding : HomeTarget
    data class ServerHome(val serverId: Long) : HomeTarget
}

/** The app manages one server: routes to it, or to onboarding when there is none yet. */
@HiltViewModel
class HomeViewModel @Inject constructor(repository: ServerRepository) : ViewModel() {
    private val _target = MutableStateFlow<HomeTarget?>(null)
    val target: StateFlow<HomeTarget?> = _target.asStateFlow()

    init {
        viewModelScope.launch {
            val server = pick(repository.observeServers().first())
            _target.value = server?.let { HomeTarget.ServerHome(it.id) } ?: HomeTarget.Onboarding
        }
    }

    companion object {
        /**
         * Older versions allowed several servers; the one with a deployed panel wins, then the oldest. Servers deployed
         * as nodes are reached from their master's node list, never as the home server.
         */
        fun pick(servers: List<Server>): Server? = servers.filterNot { it.isNode }.let { own ->
            own.filter { it.panel != null }.minByOrNull { it.id } ?: own.minByOrNull { it.id }
        }
    }
}
