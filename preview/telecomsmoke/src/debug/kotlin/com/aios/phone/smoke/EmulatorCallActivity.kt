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

        val telecom = getSystemService(TelecomManager::class.java)
        when (intent.action) {
            ACTION_REGISTER -> telecom.registerPhoneAccount(buildPhoneAccount())
            ACTION_INCOMING -> {
                val number = intent.getStringExtra(EXTRA_NUMBER) ?: DEFAULT_NUMBER
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

    private fun isEmulator(): Boolean =
        Build.HARDWARE.equals("ranchu", ignoreCase = true) ||
            Build.HARDWARE.equals("goldfish", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true) ||
            Build.FINGERPRINT.startsWith("generic")

    companion object {
        const val ACTION_REGISTER = "com.aios.phone.smoke.REGISTER"
        const val ACTION_INCOMING = "com.aios.phone.smoke.INCOMING"
        const val ACTION_DISCONNECT = "com.aios.phone.smoke.DISCONNECT"
        const val ACTION_UNREGISTER = "com.aios.phone.smoke.UNREGISTER"
        const val ACTION_SHOW = "com.aios.phone.smoke.SHOW"
        const val EXTRA_NUMBER = "number"
        const val ACCOUNT_ID = "aios-emulator-smoke"
        const val DEFAULT_NUMBER = "15551230182"
    }
}
