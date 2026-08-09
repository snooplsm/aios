package com.aios.phone.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aios.phone.PhoneRuntime
import com.aios.phone.model.PhoneAction

class InCallActivity : ComponentActivity() {
    private var pendingCameraAction: PhoneAction? = null
    private val cameraPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val action = pendingCameraAction
        pendingCameraAction = null
        if (granted && action != null) {
            PhoneRuntime.dispatch(action)
        } else if (!granted) {
            PhoneRuntime.showMessage("Camera permission is required to send call video")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val state by PhoneRuntime.state.collectAsState()
            AiosPhoneTheme(state.themePreference) {
                InCallScreen(
                    state = state,
                    dispatch = PhoneRuntime::dispatch,
                    requestCameraAction = ::requestCameraAction,
                    close = ::finish,
                )
            }
        }
    }

    private fun requestCameraAction(action: PhoneAction) {
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            PhoneRuntime.dispatch(action)
        } else {
            pendingCameraAction = action
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }
}
