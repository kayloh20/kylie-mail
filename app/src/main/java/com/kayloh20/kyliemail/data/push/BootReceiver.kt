package com.kayloh20.kyliemail.data.push

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kayloh20.kyliemail.KylieMailApp

/** Reconnects after a restart or an app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val container = (context.applicationContext as KylieMailApp).container
        if (container.credentials.account.value != null) MailPushService.start(context)
    }
}
