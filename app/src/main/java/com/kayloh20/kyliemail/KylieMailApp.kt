package com.kayloh20.kyliemail

import android.app.Application

class KylieMailApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.notifier.createChannels()
        container.settings.applyThemeMode()
        if (container.credentials.account.value != null) {
            container.syncScheduler.schedulePeriodic()
        }
    }
}
