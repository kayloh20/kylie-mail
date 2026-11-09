package com.kayloh20.kyliemail.ui.signin

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.kayloh20.kyliemail.AppContainer
import com.kayloh20.kyliemail.data.account.Account
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.mail.AuthenticationFailedException

class SignInViewModel(private val container: AppContainer) : ViewModel() {
    var email by mutableStateOf("")
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    val canSubmit: Boolean
        get() = !busy && email.contains('@') && password.isNotBlank()

    fun signIn() {
        if (!canSubmit) return
        val address = email.trim()
        val secret = password.trim()
        busy = true
        error = null
        viewModelScope.launch {
            try {
                val username = withContext(Dispatchers.IO) { container.connector.verify(address, secret) }
                container.repository.signIn(Account(address, username, secret))
            } catch (e: AuthenticationFailedException) {
                error = "iCloud didn't accept that password. Make sure it's an app-specific password, not your Apple Account password."
            } catch (e: Exception) {
                error = "Couldn't reach iCloud. Check your connection and try again."
            } finally {
                busy = false
            }
        }
    }
}
