package com.aios.phone.smoke

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import com.aios.phone.ui.InCallActivity
import java.io.File

/**
 * Shell-driven entry point for the emulator Telecom smoke test.
 *
 * This class exists only in the telecomsmoke debug source set. It refuses to
 * operate unless Android reports emulator hardware, so the fixture cannot be
 * used to inject calls on a physical handset even if its debug APK is copied.
 */
class EmulatorCallActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        check(isEmulator()) { "The Telecom smoke fixture only runs on an emulator" }

        handleCommand(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        check(isEmulator()) { "The Telecom smoke fixture only runs on an emulator" }
        handleCommand(intent)
    }

    private fun handleCommand(command: Intent) {
        val telecom = getSystemService(TelecomManager::class.java)
        when (command.action) {
            ACTION_REGISTER -> telecom.registerPhoneAccount(buildPhoneAccount())
            ACTION_INCOMING -> {
                val number = command.getStringExtra(EXTRA_NUMBER) ?: DEFAULT_NUMBER
                telecom.addNewIncomingCall(
                    phoneAccountHandle(),
                    Bundle().apply {
                        putParcelable(
                            TelecomManager.EXTRA_INCOMING_CALL_ADDRESS,
                            Uri.fromParts(PhoneAccount.SCHEME_TEL, number, null),
                        )
                    },
                )
            }
            ACTION_ACTIVATE -> EmulatorConnectionService.activateAll()
            ACTION_POST_DIAL_WAIT -> EmulatorConnectionService.requestPostDialWait()
            ACTION_RESET_AUDIT -> {
                EmulatorConnectionService.resetAudit()
                auditFile().delete()
            }
            ACTION_EXPORT_AUDIT -> auditFile().writeText(
                EmulatorConnectionService.auditSnapshot(),
                Charsets.UTF_8,
            )
            ACTION_DISCONNECT -> EmulatorConnectionService.disconnectAll()
            ACTION_UNREGISTER -> telecom.unregisterPhoneAccount(phoneAccountHandle())
            ACTION_SHOW -> startActivity(
                Intent(this, InCallActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                },
            )
        }
        finish()
    }

    private fun buildPhoneAccount(): PhoneAccount =
        PhoneAccount.builder(phoneAccountHandle(), "AIOS emulator smoke")
            .setAddress(Uri.fromParts(PhoneAccount.SCHEME_TEL, "5550001000", null))
            .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
            .setSupportedUriSchemes(listOf(PhoneAccount.SCHEME_TEL))
            .build()

    private fun phoneAccountHandle() = PhoneAccountHandle(
        ComponentName(this, EmulatorConnectionService::class.java),
        ACCOUNT_ID,
    )

    private fun auditFile() = File(cacheDir, AUDIT_FILE_NAME)

    private fun isEmulator(): Boolean =
        Build.HARDWARE.equals("ranchu", ignoreCase = true) ||
            Build.HARDWARE.equals("goldfish", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.FINGERPRINT.startsWith("generic")

    companion object {
        const val ACTION_REGISTER = "com.aios.phone.smoke.REGISTER"
        const val ACTION_INCOMING = "com.aios.phone.smoke.INCOMING"
        const val ACTION_ACTIVATE = "com.aios.phone.smoke.ACTIVATE"
        const val ACTION_POST_DIAL_WAIT = "com.aios.phone.smoke.POST_DIAL_WAIT"
        const val ACTION_RESET_AUDIT = "com.aios.phone.smoke.RESET_AUDIT"
        const val ACTION_EXPORT_AUDIT = "com.aios.phone.smoke.EXPORT_AUDIT"
        const val ACTION_DISCONNECT = "com.aios.phone.smoke.DISCONNECT"
        const val ACTION_UNREGISTER = "com.aios.phone.smoke.UNREGISTER"
        const val ACTION_SHOW = "com.aios.phone.smoke.SHOW"
        const val EXTRA_NUMBER = "number"
        const val ACCOUNT_ID = "aios-emulator-smoke"
        const val DEFAULT_NUMBER = "15551230182"
        const val AUDIT_FILE_NAME = "aios-telecom-smoke-audit.txt"
        const val POST_DIAL_SEQUENCE = "739164"
    }
}
