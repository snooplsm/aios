package com.aios.messaging.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.aios.messaging.MessagingRuntime
import com.aios.messaging.model.MessagingAction
import com.aios.messaging.ui.theme.AiosMessagingTheme

class MainActivity : ComponentActivity() {
    private val roleRequest = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        MessagingRuntime.refreshRole()
        requestNotificationPermission()
    }
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {}
    private val photoPicker = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            MessagingRuntime.dispatch(
                MessagingAction.SelectPhoto(uri.toString(), displayName(uri)),
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applySendToIntent(intent)
        setContent {
            val state by MessagingRuntime.state.collectAsState()
            BackHandler(enabled = state.selected != null) {
                MessagingRuntime.dispatch(MessagingAction.CloseConversation)
            }
            AiosMessagingTheme(state.theme) {
                MessagingScreen(
                    state = state,
                    dispatch = MessagingRuntime::dispatch,
                    requestRole = ::requestSmsRole,
                    pickPhoto = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                    call = { address ->
                        startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", address, null)))
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applySendToIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        MessagingRuntime.refreshRole()
    }

    private fun applySendToIntent(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SENDTO) return
        val scheme = intent.data?.scheme
        if (scheme !in setOf("sms", "smsto", "mms", "mmsto")) return
        val address = intent.data?.schemeSpecificPart.orEmpty().substringBefore('?')
        val body = intent.getStringExtra("sms_body").orEmpty()
        if (address.isNotBlank()) MessagingRuntime.openAddress(address, body)
    }

    private fun requestSmsRole() {
        val roles = getSystemService(RoleManager::class.java) ?: return
        if (roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
            roleRequest.launch(roles.createRequestRoleIntent(RoleManager.ROLE_SMS))
        }
    }

    private fun requestNotificationPermission() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun displayName(uri: Uri): String {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor?.moveToFirst() == true) cursor.getString(0).orEmpty()
                .takeIf(String::isNotBlank) ?: "Selected photo"
            else "Selected photo"
        } finally {
            cursor?.close()
        }
    }
}
