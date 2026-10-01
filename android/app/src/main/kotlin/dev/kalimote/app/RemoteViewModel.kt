package dev.kalimote.app

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.kalimote.atvremote.ClientIdentity
import dev.kalimote.atvremote.DeviceInfo
import dev.kalimote.atvremote.Direction
import dev.kalimote.atvremote.KeyCodes
import dev.kalimote.atvremote.Macro
import dev.kalimote.atvremote.MacroException
import dev.kalimote.atvremote.PairingSession
import dev.kalimote.atvremote.RemoteClient
import dev.kalimote.atvremote.RemoteState
import dev.kalimote.atvremote.WakeOnLan
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    val keepScreenOn: Boolean = false,
    val macros: List<SavedMacro> = emptyList(),
    val runningMacro: String? = null,
    val sleepAt: Long = 0,
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
            keepScreenOn = store.keepScreenOn,
            macros = store.loadMacros(),
            sleepAt = store.sleepAt.takeIf { it > System.currentTimeMillis() } ?: 0,
        ),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var client: RemoteClient? = null
    @Volatile
    private var clientDeviceId: String? = null
    private var pairingSession: PairingSession? = null
    private var foreground = false
    private var macroJob: Job? = null
    private var pendingLink: String? = null
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
        MacroShortcuts.update(app, _state.value.macros)
    }

    private suspend fun identity(): ClientIdentity =
        withContext(Dispatchers.IO) { Identity.get(getApplication()) }

    // ------------------------------------------------------------ lifecycle

    fun onForeground() {
        foreground = true
        // The sleep timer may have fired while we were away.
        _state.update { it.copy(sleepAt = store.sleepAt.takeIf { at -> at > System.currentTimeMillis() } ?: 0) }
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
        if (remote.connected) {
            pendingLink?.let { url ->
                pendingLink = null
                client?.launchApp(url)
                toast("Opening on TV…")
            }
        }
        if (remote.status == RemoteState.Status.UNPAIRED) {
            viewModelScope.launch { updateDevice(deviceId) { it.copy(paired = false) } }
        }
    }

    // ------------------------------------------------------------ devices

    private fun updateDevices(change: (List<TvDevice>) -> List<TvDevice>) {
        _state.update { it.copy(devices = change(it.devices)) }
        store.saveDevices(_state.value.devices)
        RemoteWidget.refresh(getApplication())
    }

    private fun updateDevice(id: String, change: (TvDevice) -> TvDevice) =
        updateDevices { list -> list.map { if (it.id == id) change(it) else it } }

    fun select(id: String) {
        store.selectedId = id
        _state.update { it.copy(selectedId = id) }
        RemoteWidget.refresh(getApplication())
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

    /** Returns an error message, or null on success. */
    fun editDevice(id: String, name: String, mac: String): String? {
        val normalized = if (mac.isBlank()) null else WakeOnLan.normalize(mac) ?: return "MAC address must look like aa:bb:cc:dd:ee:ff"
        if (name.isBlank()) return "Name is required"
        updateDevice(id) { it.copy(name = name.trim(), mac = normalized) }
        return null
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
            // The power button can wake a TV that dropped off the network.
            if (code == KeyCodes.POWER && d?.paired == true && d.mac != null) {
                wake()
                return
            }
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

    /** Opens a link on the TV; queued until connected (e.g. when shared from another app). */
    fun openLink(raw: String) {
        val url = Regex("[a-zA-Z][\\w+.-]*://\\S+").find(raw)?.value ?: run {
            toast("That doesn't look like a link")
            return
        }
        when {
            isConnected -> {
                client?.launchApp(url)
                toast("Opening on TV…")
            }
            _state.value.selected?.paired == true -> {
                pendingLink = url
                toast("Will open when the TV connects")
                client?.reconnectNow()
            }
            else -> toast("Pair a TV first")
        }
    }

    // ------------------------------------------------------------ power, sleep, volume

    fun wake() {
        val d = _state.value.selected ?: return
        val mac = d.mac ?: run {
            toast("Set the TV's MAC address first (TVs → ⋮ → Edit)")
            return
        }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { WakeOnLan.wake(mac) }.isSuccess }
            toast(if (ok) "Wake-on-LAN sent" else "Could not send Wake-on-LAN")
            delay(3000)
            client?.reconnectNow()
        }
    }

    fun setSleepTimer(minutes: Int) {
        val d = _state.value.selected ?: return
        val app = getApplication<Application>()
        if (minutes <= 0) {
            SleepTimer.cancel(app)
            _state.update { it.copy(sleepAt = 0) }
            toast("Sleep timer off")
        } else {
            val at = SleepTimer.schedule(app, d.id, minutes)
            _state.update { it.copy(sleepAt = at) }
            toast("${d.name} will turn off in $minutes minutes")
        }
    }

    /** Steps the volume to an absolute level with volume key presses. */
    fun setVolume(level: Int) {
        val c = client ?: return
        val v = c.state.volume ?: return
        val delta = level.coerceIn(0, if (v.max > 0) v.max else 100) - v.level
        val key = if (delta > 0) KeyCodes.VOLUME_UP else KeyCodes.VOLUME_DOWN
        viewModelScope.launch {
            repeat(minOf(kotlin.math.abs(delta), 100)) {
                c.sendKey(key)
                delay(60)
            }
        }
    }

    // ------------------------------------------------------------ macros

    /** Returns an error message, or null on success. */
    fun saveMacro(existing: SavedMacro?, name: String, script: String): String? {
        if (name.isBlank()) return "Macro needs a name"
        val steps = try {
            Macro.parse(script)
        } catch (e: MacroException) {
            return e.message
        }
        if (steps.isEmpty()) return "Macro is empty"
        val macro = SavedMacro(existing?.id ?: java.util.UUID.randomUUID().toString(), name.trim(), script)
        _state.update { s ->
            s.copy(macros = if (existing == null) s.macros + macro else s.macros.map { if (it.id == existing.id) macro else it })
        }
        saveMacros()
        return null
    }

    fun deleteMacro(macro: SavedMacro) {
        _state.update { s -> s.copy(macros = s.macros.filterNot { it.id == macro.id }) }
        saveMacros()
    }

    /** Returns an error message (bad script / not connected), or null if started. */
    fun runMacro(name: String, script: String): String? {
        val c = client
        if (c == null || !c.state.connected) return "TV is not connected"
        val steps = try {
            Macro.parse(script)
        } catch (e: MacroException) {
            return e.message
        }
        macroJob?.cancel()
        val job = viewModelScope.launch(Dispatchers.IO) {
            try {
                Macro.run(c, steps) { !isActive }
            } catch (_: InterruptedException) {
            }
        }
        macroJob = job
        _state.update { it.copy(runningMacro = name) }
        job.invokeOnCompletion {
            if (macroJob === job) _state.update { it.copy(runningMacro = null) }
        }
        return null
    }

    fun stopMacro() {
        macroJob?.cancel()
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

    private fun saveMacros() {
        store.saveMacros(_state.value.macros)
        MacroShortcuts.update(getApplication(), _state.value.macros)
    }

    fun setKeepScreenOn(on: Boolean) {
        store.keepScreenOn = on
        _state.update { it.copy(keepScreenOn = on) }
    }

    fun exportBackup(): String = Backup.export(_state.value.macros, _state.value.apps)

    /** Merges a backup; items with the same name are replaced. Returns a summary or error. */
    fun importBackup(text: String): String {
        val contents = try {
            Backup.parse(text)
        } catch (e: IllegalArgumentException) {
            return e.message ?: "Invalid backup"
        }
        val bad = contents.macros.firstOrNull { m -> runCatching { Macro.parse(m.script) }.isFailure }
        if (bad != null) return "Macro “${bad.name}” has an error; nothing imported"
        _state.update { s ->
            val macros = s.macros.filterNot { m -> contents.macros.any { it.name.equals(m.name, true) } } + contents.macros
            val apps = s.apps.filterNot { a -> contents.apps.any { it.name.equals(a.name, true) } } + contents.apps
            s.copy(macros = macros, apps = apps)
        }
        saveMacros()
        store.saveApps(_state.value.apps)
        return "Imported ${contents.macros.size} macros and ${contents.apps.size} app shortcuts"
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
