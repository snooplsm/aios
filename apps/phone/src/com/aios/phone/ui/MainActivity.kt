package com.aios.phone.ui

import android.app.role.RoleManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import com.aios.phone.PhoneRuntime

class MainActivity : ComponentActivity() {
    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { PhoneRuntime.refreshRole() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applyDialIntent(intent)
        setContent {
            val state by PhoneRuntime.state.collectAsState()
            AiosPhoneTheme(state.themePreference) {
                PhoneHomeScreen(
                    state = state,
                    dispatch = PhoneRuntime::dispatch,
                    requestRole = ::requestDialerRole,
                    openSettings = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    openCall = {
                        startActivity(Intent(this, InCallActivity::class.java))
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDialIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        PhoneRuntime.refreshRole()
        PhoneRuntime.dispatch(com.aios.phone.model.PhoneAction.ReloadRecentCalls)
        PhoneRuntime.dispatch(com.aios.phone.model.PhoneAction.ReloadVoicemails)
    }

    private fun applyDialIntent(intent: Intent?) {
        val number = intent?.data?.takeIf { it.scheme == "tel" }?.schemeSpecificPart ?: return
        PhoneRuntime.dispatch(com.aios.phone.model.PhoneAction.ChangeDialInput(number))
    }

    private fun requestDialerRole() {
        val roles = getSystemService(RoleManager::class.java)
        if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_DIALER)) {
            roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER))
        }
    }
}
