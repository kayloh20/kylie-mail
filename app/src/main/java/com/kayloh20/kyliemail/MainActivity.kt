package com.kayloh20.kyliemail

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
import androidx.activity.SystemBarStyle
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kayloh20.kyliemail.data.settings.ThemeMode
import com.kayloh20.kyliemail.ui.settings.SettingsDialog
import com.kayloh20.kyliemail.ui.settings.FilingSettings
import com.kayloh20.kyliemail.ui.settings.TrainingDialog
import com.kayloh20.kyliemail.data.filing.TrainingState
import java.text.NumberFormat
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
        enableEdgeToEdge()
        val container = (application as KylieMailApp).container
        handleIntent(intent)

        setContent {
            val themeMode by container.settings.themeMode.collectAsStateWithLifecycle()
            val dark = when (themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            // Status and navigation bar icons follow the app's mode, not only the system's.
            LaunchedEffect(dark) {
                val style = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            var showSettings by rememberSaveable { mutableStateOf(false) }
            var showTraining by rememberSaveable { mutableStateOf(false) }

            KylieMailTheme(darkTheme = dark) {
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
                                factory = factoryOf { MailViewModel(container.repository, container.filing, container.settings) },
                            )
                            val openRequest by openRequests.collectAsStateWithLifecycle()
                            MailScreen(
                                viewModel = vm,
                                openRequest = openRequest,
                                onOpenRequestHandled = { openRequests.value = null },
                                onOpenSettings = { showSettings = true },
                                onOpenTraining = { showTraining = true },
                                modifier = Modifier.safeDrawingPadding(),
                            )
                            val filing = container.filing
                            val training by filing.training.collectAsStateWithLifecycle()
                            val trained by filing.trainedFolders.collectAsStateWithLifecycle(emptyList())
                            val learned by filing.exampleCount.collectAsStateWithLifecycle(0)
                            val filingEnabled by container.settings.filingSuggestions.collectAsStateWithLifecycle()
                            if (showSettings) {
                                SettingsDialog(
                                    themeMode = themeMode,
                                    onThemeModeChange = container.settings::setThemeMode,
                                    filing = FilingSettings(
                                        enabled = filingEnabled,
                                        onEnabledChange = container.settings::setFilingSuggestions,
                                        status = filingStatus(training, learned, trained.size),
                                        onTrain = {
                                            showSettings = false
                                            showTraining = true
                                        },
                                    ),
                                    accountEmail = signedIn.email,
                                    onSignOut = {
                                        showSettings = false
                                        vm.signOut()
                                    },
                                    versionName = versionName(),
                                    onDismiss = { showSettings = false },
                                )
                            }
                            if (showTraining) {
                                val folders by container.repository.folders.collectAsStateWithLifecycle(emptyList())
                                TrainingDialog(
                                    folders = folders,
                                    trainedNames = trained.map { it.fullName }.toSet(),
                                    state = training,
                                    onStart = filing::train,
                                    onStop = filing::cancelTraining,
                                    onClose = {
                                        showTraining = false
                                        filing.acknowledgeTraining()
                                    },
                                )
                            }
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
                    KeyboardShortcutInfo("Settings", KeyEvent.KEYCODE_COMMA, ctrl),
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

    private fun filingStatus(state: TrainingState, learned: Int, folders: Int): String = when {
        state is TrainingState.Running -> "Training: folder ${state.index} of ${state.total}…"
        learned == 0 -> "Not trained yet. Train it on your folders to get suggestions right away."
        folders == 0 -> "Learning from the mail you file."
        else -> "Learned from ${NumberFormat.getIntegerInstance().format(learned)} emails in " +
            (if (folders == 1) "1 folder" else "$folders folders") + ", and keeps learning as you file."
    }

    private fun versionName(): String =
        runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull().orEmpty()

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
