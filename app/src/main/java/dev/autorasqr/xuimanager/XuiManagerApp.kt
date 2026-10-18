package dev.autorasqr.xuimanager

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import dev.autorasqr.xuimanager.data.ssh.CryptoProviders
import dev.autorasqr.xuimanager.service.DeployNotifications

@HiltAndroidApp
class XuiManagerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CryptoProviders.install()
        DeployNotifications.createChannel(this)
    }
}
