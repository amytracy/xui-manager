package dev.autorasqr.xuimanager.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Start destination: decides once where the app opens and replaces itself with that screen. */
@Composable
fun HomeScreen(
    onOnboarding: () -> Unit,
    onServer: (Long) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val target by viewModel.target.collectAsStateWithLifecycle()
    LaunchedEffect(target) {
        when (val current = target) {
            HomeTarget.Onboarding -> onOnboarding()
            is HomeTarget.ServerHome -> onServer(current.serverId)
            null -> Unit
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
}
