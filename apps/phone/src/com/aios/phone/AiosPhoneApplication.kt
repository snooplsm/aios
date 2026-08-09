package com.aios.phone

import android.app.Application

class AiosPhoneApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        PhoneRuntime.initialize(this)
    }
}
