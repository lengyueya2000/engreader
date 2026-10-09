package com.engreader.app

import android.app.Application
import com.engreader.app.data.AppContainer

class EngReaderApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
