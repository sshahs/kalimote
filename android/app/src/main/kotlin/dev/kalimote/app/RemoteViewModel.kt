package dev.kalimote.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kalimote.atvremote.ClientIdentity
import dev.kalimote.atvremote.DeviceInfo
import dev.kalimote.atvremote.Direction
import dev.kalimote.atvremote.PairingSession
import dev.kalimote.atvremote.RemoteClient
import dev.kalimote.atvremote.RemoteState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

data class PairingUi(
    val device: TvDevice,
    val phase: Phase,
    val error: String? = null,
) {
    enum class Phase { CONNECTING, ENTER_CODE, VERIFYING }
}

data class UiState(
    val devices: List<TvDevice> = emptyList(),
    val discovered: List<DiscoveredTv> = emptyList(),
    val selectedId: String? = null,
    val remote: RemoteState = RemoteState(),
    val pairing: PairingUi? = null,
    val apps: List<AppShortcut> = DEFAULT_APPS,
    val touchpad: Boolean = false,
    val volumeKeys: Boolean = true,
    val message: String? = null,
) {
    val selected: TvDevice? get() = devices.firstOrNull { it.id == selectedId }

    /** Discovered TVs that have not been added yet. */
    val newDiscovered: List<DiscoveredTv>
        get() = discovered.filter { d -> devices.none { it.host == d.host } }
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {
    private val store = DeviceStore(app)
    private val _state = MutableStateFlow(
        UiState(
            devices = store.loadDevices(),
            selectedId = store.selectedId,
            apps = store.loadApps(),
            touchpad = store.touchpad,
            volumeKeys = store.volumeKeys,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val identityMutex = Mutex()
    private var identity: ClientIdentity? = null
    private var client: RemoteClient? = null
    @Volatile
    private var clientDeviceId: String? = null
    private var pairingSession: PairingSession? = null
    private var foreground = false
    private val discovery = Discovery(app) { found -> _state.update { it.copy(discovered = found) } }
    private val deviceInfo = DeviceInfo(
        model = Build.MODEL ?: "Android",
        vendor = Build.MANUFACTURER ?: "Kalimote",
        appVersion = "0.1.0",
    )

    init {
        if (_state.value.selected == null) {
            _state.update { it.copy(selectedId = it.devices.firstOrNull()?.id) }
        }
    }

    private suspend fun identity(): ClientIdentity = identityMutex.withLock {
        identity ?: withContext(Dispatchers.IO) {
            val file = File(getApplication<Application>().filesDir, "identity")
            val loaded = runCatching { ClientIdentity.decode(file.readText()) }.getOrNull()
            loaded ?: ClientIdentity.generate("kalimote-android").also { file.writeText(it.encode()) }
        }.also { identity = it }
    }

    // ------------------------------------------------------------ lifecycle

    fun onForeground() {
        foreground = true
        discovery.start()
        ensureClient()
        client?.reconnectNow()
    }

    fun onBackground() {
        foreground = false
        discovery.stop()
        // Drop the connection while in the background to save battery; it is
        // re-established within a second when the app is reopened.
        client?.stop()
    }

    override fun onCleared() {
        discovery.stop()
        client?.shutdown()
        pairingSession?.close()
    }

    private fun ensureClient() {
        val device = _state.value.selected
        if (device == null || !device.paired) {
            dropClient()
            return
        }
        if (clientDeviceId == device.id && client != null) {
            if (foreground) client?.start()
            return
        }
        dropClient()
        clientDeviceId = device.id
        viewModelScope.launch {
            val id = identity()
            if (clientDeviceId != device.id) return@launch
            val c = RemoteClient(device.host, id, deviceInfo) { remote -> onRemoteState(device.id, remote) }
            client = c
            if (foreground) c.start()
        }
    }

    private fun dropClient() {
        client?.shutdown()
        client = null
        clientDeviceId = null
        _state.update { it.copy(remote = RemoteState()) }
    }

    private fun onRemoteState(deviceId: String, remote: RemoteState) {
        if (clientDeviceId != deviceId) return
        _state.update { it.copy(remote = remote) }
        if (remote.status == RemoteState.Status.UNPAIRED) {
            viewModelScope.launch { updateDevice(deviceId) { it.copy(paired = false) } }
        }
    }

    // ------------------------------------------------------------ devices

    private fun updateDevices(change: (List<TvDevice>) -> List<TvDevice>) {
        _state.update { it.copy(devices = change(it.devices)) }
        store.saveDevices(_state.value.devices)
    }

    private fun updateDevice(id: String, change: (TvDevice) -> TvDevice) =
        updateDevices { list -> list.map { if (it.id == id) change(it) else it } }

    fun select(id: String) {
        store.selectedId = id
        _state.update { it.copy(selectedId = id) }
        ensureClient()
    }

    /** Adds a TV (or returns the existing one with the same host) and starts pairing. */
    fun addAndPair(host: String, name: String?) {
        val h = host.trim()
        if (!Regex("^[\\w.:-]+$").matches(h)) {
            toast("Enter a valid IP address or host name")
            return
        }
        val existing = _state.value.devices.firstOrNull { it.host == h }
        val device = existing ?: TvDevice(name = name?.trim().takeUnless { it.isNullOrEmpty() } ?: h, host = h)
        if (existing == null) updateDevices { it + device }
        select(device.id)
        startPairing(device)
    }

    fun rename(id: String, name: String) {
        if (name.isBlank()) return
        updateDevice(id) { it.copy(name = name.trim()) }
    }

    fun remove(id: String) {
        if (clientDeviceId == id) dropClient()
        updateDevices { list -> list.filterNot { it.id == id } }
        if (_state.value.selectedId == id) {
            _state.value.devices.firstOrNull()?.let { select(it.id) }
                ?: _state.update { it.copy(selectedId = null) }
        }
    }

    fun rescan() = discovery.restart()

    // ------------------------------------------------------------ pairing

    /** [notice] is shown in the code dialog once the TV displays a new code. */
    fun startPairing(device: TvDevice, notice: String? = null) {
        if (clientDeviceId == device.id) dropClient()
        pairingSession?.close()
        _state.update { it.copy(pairing = PairingUi(device, PairingUi.Phase.CONNECTING)) }
        viewModelScope.launch {
            try {
                val session = PairingSession(device.host, identity(), clientName = "Kalimote (${Build.MODEL})")
                pairingSession = session
                withContext(Dispatchers.IO) { session.start() }
                _state.update { s -> s.copy(pairing = s.pairing?.copy(phase = PairingUi.Phase.ENTER_CODE, error = notice)) }
            } catch (e: Exception) {
                _state.update { s ->
                    s.copy(pairing = s.pairing?.copy(phase = PairingUi.Phase.ENTER_CODE, error = describe(e)))
                }
            }
        }
    }

    fun submitCode(code: String) {
        val pairing = _state.value.pairing ?: return
        val session = pairingSession
        if (session == null || !session.isActive) {
            startPairing(pairing.device)
            return
        }
        _state.update { it.copy(pairing = pairing.copy(phase = PairingUi.Phase.VERIFYING, error = null)) }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { session.finish(code) }
                pairingSession = null
                updateDevice(pairing.device.id) { it.copy(paired = true) }
                _state.update { it.copy(pairing = null) }
                toast("Paired with ${pairing.device.name}")
                select(pairing.device.id)
            } catch (e: IllegalArgumentException) {
                // Mistyped: the session is still open, let the user try again.
                _state.update { it.copy(pairing = pairing.copy(phase = PairingUi.Phase.ENTER_CODE, error = e.message)) }
            } catch (e: Exception) {
                // The TV closes the session after a wrong code; request a new one.
                startPairing(pairing.device, "${describe(e)}. Enter the new code shown on the TV.")
            }
        }
    }

    fun cancelPairing() {
        pairingSession?.close()
        pairingSession = null
        _state.update { it.copy(pairing = null) }
        ensureClient()
    }

    // ------------------------------------------------------------ control

    fun sendKey(code: Int, direction: Direction = Direction.SHORT) {
        val c = client
        if (c == null || !c.state.connected) {
            val d = _state.value.selected
            toast(
                when {
                    d == null -> "Add a TV first"
                    !d.paired -> "Pair this TV first"
                    else -> "TV is not connected"
                },
            )
            return
        }
        c.sendKey(code, direction)
    }

    val isConnected: Boolean get() = client?.state?.connected == true

    fun sendText(text: String) {
        if (isConnected) client?.sendText(text) else toast("TV is not connected")
    }

    fun launchApp(app: AppShortcut) {
        if (isConnected) client?.launchApp(app.url) else toast("TV is not connected")
    }

    fun addApp(app: AppShortcut) {
        _state.update { it.copy(apps = it.apps + app) }
        store.saveApps(_state.value.apps)
    }

    fun removeApp(app: AppShortcut) {
        _state.update { it.copy(apps = it.apps - app) }
        store.saveApps(_state.value.apps)
    }

    fun setTouchpad(on: Boolean) {
        store.touchpad = on
        _state.update { it.copy(touchpad = on) }
    }

    fun setVolumeKeys(on: Boolean) {
        store.volumeKeys = on
        _state.update { it.copy(volumeKeys = on) }
    }

    fun reconnect() = client?.reconnectNow() ?: ensureClient()

    // ------------------------------------------------------------ messages

    fun toast(message: String) = _state.update { it.copy(message = message) }

    fun messageShown() = _state.update { it.copy(message = null) }

    private fun describe(e: Exception): String = when (e) {
        is java.net.ConnectException, is java.net.NoRouteToHostException ->
            "Cannot reach the TV. Check it is on and on the same Wi-Fi network"
        is java.net.SocketTimeoutException -> "The TV did not respond"
        is java.net.UnknownHostException -> "Unknown host"
        else -> e.message ?: e.javaClass.simpleName
    }
}
