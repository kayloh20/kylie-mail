package com.kayloh20.kyliemail

import android.app.Application
import com.kayloh20.kyliemail.data.relay.startFirebase
import kotlinx.coroutines.launch

class KylieMailApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Firebase isn't started at launch unless the push relay is set up, so it can deliver pushes.
        if (container.pushRelay.isSetUp) startFirebase(this)
        container.notifier.createChannels()
        container.settings.applyThemeMode()
        if (container.credentials.account.value != null) {
            container.syncScheduler.schedulePeriodic()
            if (container.keepAllMail.enabled.value) container.keepAllMail.schedule()
            // A relay that restarted can't watch until a device checks in.
            if (container.pushRelay.isSetUp) container.appScope.launch { container.pushRelay.checkIn() }
        }
    }
}
