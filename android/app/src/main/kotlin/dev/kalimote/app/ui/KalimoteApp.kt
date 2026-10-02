package dev.kalimote.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.kalimote.app.RemoteViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KalimoteApp(vm: RemoteViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    var showDevices by rememberSaveable { mutableStateOf(state.devices.isEmpty()) }
    val snackbar = remember { SnackbarHostState() }
    var showJellyfinSettings by rememberSaveable { mutableStateOf(false) }

    val view = LocalView.current
    DisposableEffect(state.keepScreenOn) {
        view.keepScreenOn = state.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            vm.messageShown()
            snackbar.showSnackbar(it)
        }
    }

    BackHandler(enabled = showDevices && state.devices.isNotEmpty()) { showDevices = false }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
                navigationIcon = {
                    if (showDevices && state.devices.isNotEmpty()) {
                        IconButton(onClick = { showDevices = false }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back to remote")
                        }
                    }
                },
                title = {
                    if (showDevices) {
                        Text("TVs")
                    } else {
                        DevicePicker(state, onSelect = { vm.select(it) }, onManage = { showDevices = true })
                    }
                },
                actions = {
                    if (!showDevices) {
                        IconButton(onClick = { showDevices = true }) { Icon(Icons.Filled.Tv, "Manage TVs") }
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding).fillMaxSize()
        if (showDevices) {
            DevicesScreen(
                state,
                DeviceActions(
                    select = {
                        vm.select(it.id)
                        showDevices = false
                    },
                    pair = { vm.startPairing(it) },
                    add = { host, name, type -> vm.addAndPair(host, name, type) },
                    edit = { d, name, mac -> vm.editDevice(d.id, name, mac) },
                    remove = { vm.remove(it.id) },
                    rescan = vm::rescan,
                    setVolumeKeys = vm::setVolumeKeys,
                    setKeepScreenOn = vm::setKeepScreenOn,
                    setMediaControls = vm::setMediaControls,
                    exportBackup = vm::exportBackup,
                    importBackup = vm::importBackup,
                    toast = vm::toast,
                    openJellyfin = { showJellyfinSettings = true },
                ),
                modifier,
            )
        } else {
            RemoteScreen(
                state,
                RemoteActions(
                    key = { code, dir -> vm.sendKey(code, dir) },
                    text = vm::sendText,
                    launch = vm::launchApp,
                    addApp = vm::addApp,
                    removeApp = vm::removeApp,
                    setTouchpad = vm::setTouchpad,
                    pair = { state.selected?.let { vm.startPairing(it) } },
                    reconnect = { vm.reconnect() },
                    openDevices = { showDevices = true },
                    openLink = vm::openLink,
                    wake = vm::wake,
                    setSleep = vm::setSleepTimer,
                    setVolume = vm::setVolume,
                    saveMacro = vm::saveMacro,
                    deleteMacro = vm::deleteMacro,
                    runMacro = vm::runMacro,
                    stopMacro = vm::stopMacro,
                    toast = vm::toast,
                    jellyfin = JellyfinActions(
                        key = { vm.sendKey(it) },
                        control = vm::jellyfinControl,
                        openSettings = { showJellyfinSettings = true },
                        poster = vm::jellyfinPoster,
                        browse = { view, query, item -> vm.jellyfinBrowse(view, query, item) },
                        play = { vm.jellyfinPlay(it) },
                    ),
                    listApps = vm::listApps,
                    launchPackage = vm::launchPackage,
                    screenshot = vm::screenshot,
                    installApk = vm::installApk,
                ),
                modifier,
            )
        }
    }

    if (showJellyfinSettings) {
        JellyfinSettingsDialog(
            url = state.jellyfinUrl,
            configured = state.jellyfinConfigured,
            onSave = vm::saveJellyfin,
            onRemove = vm::removeJellyfin,
            onDismiss = { showJellyfinSettings = false },
        )
    }

    state.install?.let { InstallDialog(it, onDismiss = vm::dismissInstall) }

    state.pairing?.let { pairing ->
        PairingDialog(
            pairing,
            onSubmit = vm::submitCode,
            onCancel = vm::cancelPairing,
            onRetry = { vm.startPairing(pairing.device) },
        )
    }

    // Return to the remote once a pairing completes.
    LaunchedEffect(state.pairing == null, state.selected?.paired) {
        if (state.pairing == null && state.selected?.paired == true && showDevices) {
            showDevices = false
        }
    }
}

@Composable
private fun DevicePicker(
    state: dev.kalimote.app.UiState,
    onSelect: (String) -> Unit,
    onManage: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val device = state.selected
    Box {
        TextButton(onClick = { open = true }) {
            StatusDot(
                when {
                    state.remote.connected -> Ok
                    device?.paired == true -> Warn
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.width(10.dp))
            Text(
                device?.name ?: "No TV",
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Filled.ArrowDropDown, null, tint = MaterialTheme.colorScheme.onBackground)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            state.devices.forEach { d ->
                DropdownMenuItem(
                    text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.name)
                            if (!d.paired) Text("· not paired", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    trailingIcon = { if (d.id == state.selectedId) Icon(Icons.Filled.Check, null) },
                    onClick = {
                        open = false
                        onSelect(d.id)
                    },
                )
            }
            if (state.devices.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text("Manage TVs…") },
                onClick = {
                    open = false
                    onManage()
                },
            )
        }
    }
}
