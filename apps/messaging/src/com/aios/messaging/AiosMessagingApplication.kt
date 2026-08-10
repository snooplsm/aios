package com.aios.messaging

import android.app.Application

class AiosMessagingApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MessagingRuntime.initialize(this)
    }
}
