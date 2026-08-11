package com.aios.phone

/** Decides how the non-sensitive Phone UI preferences enter device storage. */
internal object DirectBootPreferencePolicy {
    enum class Action {
        WAIT_FOR_UNLOCK,
        MIGRATE_LEGACY,
        KEEP_DEVICE_VALUES,
        ALREADY_COMPLETE,
    }

    fun action(
        userUnlocked: Boolean,
        migrationComplete: Boolean,
        deviceValuesPresent: Boolean,
    ): Action = when {
        migrationComplete -> Action.ALREADY_COMPLETE
        !userUnlocked -> Action.WAIT_FOR_UNLOCK
        deviceValuesPresent -> Action.KEEP_DEVICE_VALUES
        else -> Action.MIGRATE_LEGACY
    }
}
