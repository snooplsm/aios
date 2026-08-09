package com.aios.phone.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aios.phone.PhoneRuntime

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneRuntime.dispatch(com.aios.phone.model.PhoneAction.ReloadAssistantPolicy)
        setContent {
            val state by PhoneRuntime.state.collectAsState()
            AiosPhoneTheme(state.themePreference) {
                SettingsScreen(state, PhoneRuntime::dispatch, ::finish)
            }
        }
    }
}
