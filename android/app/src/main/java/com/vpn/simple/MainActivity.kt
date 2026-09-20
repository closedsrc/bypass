package com.vpn.simple

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vpn.simple.AppExclusions
import com.vpn.simple.AppEntry
import com.vpn.simple.InstalledApps
import com.vpn.simple.ProfileStore
import com.vpn.simple.VpnStateHolder
import com.vpn.simple.ui.BypassViewModel
import com.vpn.simple.ui.Notice
import com.vpn.simple.ui.screens.ExcludedAppsScreen
import com.vpn.simple.ui.screens.HomeScreen
import com.vpn.simple.ui.screens.OnboardingScreen
import com.vpn.simple.ui.screens.SettingsScreen
import com.vpn.simple.ui.theme.BypassTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BypassTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    BypassApp()
                }
            }
        }
    }
}

private enum class Route { HOME, SETTINGS, EXCLUDED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BypassApp(vm: BypassViewModel = viewModel()) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by vm.status.collectAsStateWithLifecycle()
    val notice by vm.notice.collectAsStateWithLifecycle()

    var route by remember { mutableStateOf(Route.HOME) }
    var profile by remember { mutableStateOf(ProfileStore.info(context)) }

    // Excluded-apps screen state, loaded off the main thread.
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var appsLoading by remember { mutableStateOf(false) }
    var includeSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var excluded by remember { mutableStateOf(AppExclusions.load(context)) }

    // The pending action to run once the system VPN consent resolves.
    var pendingConnect by remember { mutableStateOf(false) }

    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            if (pendingConnect) vm.connect()
        } else {
            vm.setError("Android did not grant VPN access, so Bypass cannot route your traffic.")
        }
        pendingConnect = false
    }

    // Notification permission is requested at the moment it becomes meaningful — when the
    // user first asks to connect — rather than on launch, before they know what the app
    // does.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The tunnel still runs without it; the notification just stays hidden. */ }

    val ensureNotificationPermission: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importProfile(uri)
    }

    LaunchedEffect(status.hasProfile, notice) {
        profile = ProfileStore.info(context)
    }

    // Reload the app list whenever the picker is opened or the filter changes.
    LaunchedEffect(route, includeSystem) {
        if (route != Route.EXCLUDED) return@LaunchedEffect
        appsLoading = true
        apps = withContext(Dispatchers.IO) { InstalledApps.load(context, includeSystem) }
        appsLoading = false
    }

    val startConnect: () -> Unit = {
        ensureNotificationPermission()
        val consent = VpnService.prepare(context)
        if (consent != null) {
            pendingConnect = true
            consentLauncher.launch(consent)
        } else {
            vm.connect()
        }
    }

    val onImport: () -> Unit = {
        picker.launch(arrayOf("application/yaml", "text/yaml", "text/plain", "application/octet-stream"))
    }

    // Keep the Home state fresh when returning from another screen or the background.
    LaunchedEffect(route) { if (route == Route.HOME) vm.onResume() }

    BackHandler(enabled = route != Route.HOME) { route = Route.HOME }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (route != Route.HOME) {
                CenterAlignedTopAppBar(
                    title = {
                        Text(
                            text = if (route == Route.SETTINGS) "Settings" else "Apps that skip the tunnel",
                            style = MaterialTheme.typography.titleMedium,
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { route = Route.HOME }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                        }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
    ) { inner ->
        Box(Modifier.padding(inner)) {
            when (route) {
                Route.HOME -> {
                    if (!status.hasProfile && status.phase == com.vpn.simple.VpnPhase.DISCONNECTED) {
                        OnboardingScreen(onImport = onImport)
                    } else {
                        HomeScreen(
                            status = status,
                            notice = notice,
                            onConnect = startConnect,
                            onDisconnect = vm::disconnect,
                            onImport = onImport,
                            onOpenExcluded = { route = Route.EXCLUDED },
                            onOpenSettings = { route = Route.SETTINGS },
                            onDismissNotice = vm::dismissNotice,
                            onDismissError = vm::dismissError,
                        )
                    }
                }

                Route.SETTINGS -> SettingsScreen(
                    status = status,
                    profile = profile,
                    onImport = onImport,
                    onRemove = {
                        vm.removeProfile()
                        profile = ProfileStore.info(context)
                    },
                    onOpenExcluded = { route = Route.EXCLUDED },
                )

                Route.EXCLUDED -> ExcludedAppsScreen(
                    apps = apps,
                    loading = appsLoading,
                    excluded = excluded,
                    includeSystem = includeSystem,
                    query = query,
                    onQueryChange = { query = it },
                    onIncludeSystemChange = { includeSystem = it },
                    onToggle = { pkg ->
                        val next = excluded.toMutableSet().apply {
                            if (!add(pkg)) remove(pkg)
                        }
                        excluded = next
                        AppExclusions.save(context, next)
                        VpnStateHolder.refreshExcludedCount(context)
                        // A change while connected rebuilds the interface straight away.
                        if (com.vpn.simple.SimpleVpnService.running) {
                            scope.launch { vm.connect() }
                        }
                    },
                )
            }
        }
    }
}
