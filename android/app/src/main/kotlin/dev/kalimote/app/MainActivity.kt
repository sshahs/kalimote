package dev.kalimote.app

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dev.kalimote.app.ui.KalimoteApp
import dev.kalimote.app.ui.KalimoteTheme
import dev.kalimote.atvremote.KeyCodes

class MainActivity : ComponentActivity() {
    private val vm: RemoteViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KalimoteTheme {
                KalimoteApp(vm)
            }
        }
        if (savedInstanceState == null) {
            handleShare(intent)
            if (vm.state.value.mediaControls) MediaControlsService.apply(this, true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** "Share → Kalimote" from YouTube, Netflix, a browser… opens the link on the TV. */
    private fun handleShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        vm.openLink(text)
        setIntent(Intent(this, MainActivity::class.java)) // don't re-handle on rotation
    }

    override fun onStart() {
        super.onStart()
        vm.onForeground()
    }

    override fun onStop() {
        vm.onBackground()
        super.onStop()
    }

    /** The phone's volume buttons control the TV while connected. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val tvKey = volumeKey(keyCode)
        if (tvKey != null) {
            vm.sendKey(tvKey)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        volumeKey(keyCode) != null || super.onKeyUp(keyCode, event)

    private fun volumeKey(keyCode: Int): Int? {
        if (!vm.state.value.volumeKeys || !vm.isConnected) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> KeyCodes.VOLUME_UP
            KeyEvent.KEYCODE_VOLUME_DOWN -> KeyCodes.VOLUME_DOWN
            else -> null
        }
    }
}
