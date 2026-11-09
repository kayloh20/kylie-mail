package com.kayloh20.kyliemail

import androidx.activity.SystemBarStyle
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.KeyboardShortcutGroup
import android.view.KeyboardShortcutInfo
import android.view.Menu
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import com.kayloh20.kyliemail.data.push.MailPushService
import com.kayloh20.kyliemail.ui.mail.MailScreen
import com.kayloh20.kyliemail.ui.mail.MailViewModel
import com.kayloh20.kyliemail.ui.signin.SignInScreen
import com.kayloh20.kyliemail.ui.signin.SignInViewModel
import com.kayloh20.kyliemail.ui.theme.KylieMailTheme

class MainActivity : ComponentActivity() {
    /** A message to open, from a notification tap. */
    private val openRequests = MutableStateFlow<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is always dark, so system bar icons stay light even when the system is in light mode.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val container = (application as KylieMailApp).container
        handleIntent(intent)

        setContent {
            KylieMailTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    val account by container.credentials.account.collectAsStateWithLifecycle()
                    val signedIn = account
                    LaunchedEffect(signedIn?.email) {
                        if (signedIn != null) MailPushService.start(this@MainActivity) else MailPushService.stop(this@MainActivity)
                    }
                    if (signedIn == null) {
                        val vm: SignInViewModel = viewModel(factory = factoryOf { SignInViewModel(container) })
                        SignInScreen(vm, Modifier.safeDrawingPadding())
                    } else {
                        key(signedIn.email) {
                            val vm: MailViewModel = viewModel(
                                key = "mail:${signedIn.email}",
                                factory = factoryOf { MailViewModel(container.repository) },
                            )
                            val openRequest by openRequests.collectAsStateWithLifecycle()
                            MailScreen(
                                viewModel = vm,
                                openRequest = openRequest,
                                onOpenRequestHandled = { openRequests.value = null },
                                modifier = Modifier.safeDrawingPadding(),
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        val container = (application as KylieMailApp).container
        if (container.credentials.account.value != null) MailPushService.start(this)
    }

    /** Lists the app's shortcuts in the system keyboard shortcuts helper. */
    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>, menu: Menu?, deviceId: Int) {
        val ctrl = KeyEvent.META_CTRL_ON
        val shift = KeyEvent.META_SHIFT_ON
        data.add(
            KeyboardShortcutGroup(
                "Kylie Mail",
                listOf(
                    KeyboardShortcutInfo("Move to folder", KeyEvent.KEYCODE_M, ctrl or shift),
                    KeyboardShortcutInfo("Go to folder", KeyEvent.KEYCODE_K, ctrl),
                    KeyboardShortcutInfo("Undo", KeyEvent.KEYCODE_Z, ctrl),
                    KeyboardShortcutInfo("Select all", KeyEvent.KEYCODE_A, ctrl),
                    KeyboardShortcutInfo("Delete", KeyEvent.KEYCODE_FORWARD_DEL, 0),
                    KeyboardShortcutInfo("Next message", KeyEvent.KEYCODE_DPAD_DOWN, 0),
                    KeyboardShortcutInfo("Previous message", KeyEvent.KEYCODE_DPAD_UP, 0),
                    KeyboardShortcutInfo("Extend selection", KeyEvent.KEYCODE_DPAD_DOWN, shift),
                    KeyboardShortcutInfo("Clear selection", KeyEvent.KEYCODE_ESCAPE, 0),
                ),
            ),
        )
        super.onProvideKeyboardShortcuts(data, menu, deviceId)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.action != ACTION_OPEN_MESSAGE) return
        val id = intent.getLongExtra(EXTRA_MESSAGE_ID, -1)
        if (id >= 0) openRequests.value = id
    }

    companion object {
        const val ACTION_OPEN_MESSAGE = "com.kayloh20.kyliemail.OPEN_MESSAGE"
        const val EXTRA_MESSAGE_ID = "message_id"
    }
}

private inline fun <reified VM : ViewModel> factoryOf(crossinline create: () -> VM): ViewModelProvider.Factory =
    viewModelFactory { initializer { create() } }
