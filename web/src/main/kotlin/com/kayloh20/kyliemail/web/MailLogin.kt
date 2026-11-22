package com.kayloh20.kyliemail.web

import com.kayloh20.kyliemail.data.mail.ImapConnector
import javax.mail.AuthenticationFailedException

sealed interface MailLoginResult {
    /** iCloud accepted the password; [username] is the form it took. */
    data class Accepted(val username: String) : MailLoginResult
    data object Rejected : MailLoginResult
    data object Unreachable : MailLoginResult
}

/** Checks an iCloud address and app-specific password. */
fun interface MailLogin {
    fun check(email: String, password: String): MailLoginResult
}

class ImapMailLogin(private val connector: ImapConnector = ImapConnector()) : MailLogin {
    override fun check(email: String, password: String): MailLoginResult = try {
        MailLoginResult.Accepted(connector.verify(email, password))
    } catch (e: AuthenticationFailedException) {
        MailLoginResult.Rejected
    } catch (e: Exception) {
        MailLoginResult.Unreachable
    }
}
