package com.kayloh20.kyliemail

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.kayloh20.kyliemail.ui.mail.MailScreen
import com.kayloh20.kyliemail.ui.mail.MailViewModel
import com.kayloh20.kyliemail.ui.signin.SignInScreen
import com.kayloh20.kyliemail.ui.signin.SignInViewModel
import com.kayloh20.kyliemail.ui.theme.KylieMailTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as KylieMailApp).container

        setContent {
            KylieMailTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    val account by container.credentials.account.collectAsStateWithLifecycle()
                    val signedIn = account
                    if (signedIn == null) {
                        val vm: SignInViewModel = viewModel(factory = factoryOf { SignInViewModel(container) })
                        SignInScreen(vm, Modifier.safeDrawingPadding())
                    } else {
                        key(signedIn.email) {
                            val vm: MailViewModel = viewModel(
                                key = "mail:${signedIn.email}",
                                factory = factoryOf { MailViewModel(container.repository) },
                            )
                            MailScreen(vm, Modifier.safeDrawingPadding())
                        }
                    }
                }
            }
        }
    }
}

private inline fun <reified VM : ViewModel> factoryOf(crossinline create: () -> VM): ViewModelProvider.Factory =
    viewModelFactory { initializer { create() } }
