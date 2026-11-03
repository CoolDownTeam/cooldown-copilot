package com.pleos.cooldown

import android.app.Application
import com.pleos.cooldown.di.AppContainer

class CooldownApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
