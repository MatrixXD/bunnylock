package dev.ririxi.bunnylock.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

enum class Tab(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Rounded.Home),
    BANDS("Bands", BunnyIcons.Bands),
    CELL("Cell lock", BunnyIcons.Tower),
    TOOLS("Tools", Icons.Rounded.Build),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun BunnyApp(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var selectedTab by rememberSaveable { mutableStateOf(Tab.HOME) }
    val snackbar = remember { SnackbarHostState() }
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    LaunchedEffect(Unit) { vm.events.collect { snackbar.showSnackbar(it) } }

    Scaffold(
        topBar = {
            LargeFlexibleTopAppBar(
                title = { Wordmark() },
                subtitle = { Text(topBarSubtitle(state)) },
                actions = {
                    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        when {
                            state.busy || state.root == RootState.Checking -> {
                                LoadingIndicator(Modifier.size(36.dp))
                            }

                            state.root == RootState.Granted -> {
                                IconButton(onClick = vm::refresh) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                                }
                            }
                        }
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    ),
                scrollBehavior = scrollBehavior,
            )
        },
        bottomBar = {
            if (state.root == RootState.Granted) {
                ShortNavigationBar {
                    Tab.entries.forEach { tab ->
                        ShortNavigationBarItem(
                            selected = selectedTab == tab,
                            onClick = { selectedTab = tab },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { innerPadding ->
        when (val root = state.root) {
            RootState.Checking -> {
                Centered(innerPadding) {
                    LoadingIndicator(Modifier.size(96.dp))
                    Text(state.connectStep, textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMediumEmphasized)
                }
            }

            is RootState.Denied -> {
                Centered(innerPadding) {
                    LockBadge(locked = false, size = 120.dp)
                    Text("Root or Shizuku needed", style = MaterialTheme.typography.headlineMediumEmphasized)
                    Text(
                        root.reason,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(onClick = vm::connect, shapes = ButtonDefaults.shapes()) { Text("Try again") }
                    TextButton(onClick = vm::copyDiagnostics, shapes = ButtonDefaults.shapes()) { Text("Copy diagnostics") }
                }
            }

            RootState.Granted -> {
                val pullToRefreshState = rememberPullToRefreshState()
                val contentPadding = PaddingValues(bottom = innerPadding.calculateBottomPadding())
                PullToRefreshBox(
                    isRefreshing = state.refreshing,
                    onRefresh = vm::refresh,
                    state = pullToRefreshState,
                    modifier = Modifier.fillMaxSize().padding(top = innerPadding.calculateTopPadding()),
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = pullToRefreshState,
                            isRefreshing = state.refreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    },
                ) {
                    // Inside the refresh box so the app bar expands before an overscroll becomes a refresh.
                    AnimatedContent(
                        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
                        targetState = selectedTab,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "tab",
                    ) { tab ->
                        when (tab) {
                            Tab.HOME -> HomeScreen(state, vm, contentPadding) { selectedTab = it }
                            Tab.BANDS -> BandsScreen(state, vm, contentPadding)
                            Tab.CELL -> CellScreen(state, vm, contentPadding)
                            Tab.TOOLS -> ToolsScreen(state, vm, contentPadding)
                        }
                    }
                }
            }
        }
    }
}

private fun topBarSubtitle(state: UiState): String {
    val backendAndSim =
        listOfNotNull(
            state.backend?.label,
            if (state.sims.size > 1) "SIM ${state.sim}" else null,
        )
    val throughShizuku = state.access == Access.SHIZUKU_ADB || state.access == Access.SHIZUKU_ROOT
    val accessSuffix = if (throughShizuku) ", through Shizuku" else ""
    return backendAndSim.joinToString(" on ").ifEmpty { "Band and cell locking" } + accessSuffix
}

@Composable
private fun Centered(
    innerPadding: PaddingValues,
    content: @Composable () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(innerPadding).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
        }
    }
}

fun PaddingValues.withScreenMargins(): PaddingValues =
    PaddingValues(
        start = 16.dp,
        end = 16.dp,
        top = calculateTopPadding() + 4.dp,
        bottom = calculateBottomPadding() + 24.dp,
    )
