package com.aios.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class DirectBootPreferencePolicyTest {
    @Test
    fun credentialPreferencesWaitWhileUserIsLocked() {
        assertEquals(
            DirectBootPreferencePolicy.Action.WAIT_FOR_UNLOCK,
            DirectBootPreferencePolicy.action(
                userUnlocked = false,
                migrationComplete = false,
                deviceValuesPresent = false,
            ),
        )
    }

    @Test
    fun legacyPreferencesMigrateOnlyIntoAnEmptyDeviceStore() {
        assertEquals(
            DirectBootPreferencePolicy.Action.MIGRATE_LEGACY,
            DirectBootPreferencePolicy.action(
                userUnlocked = true,
                migrationComplete = false,
                deviceValuesPresent = false,
            ),
        )
        assertEquals(
            DirectBootPreferencePolicy.Action.KEEP_DEVICE_VALUES,
            DirectBootPreferencePolicy.action(
                userUnlocked = true,
                migrationComplete = false,
                deviceValuesPresent = true,
            ),
        )
    }

    @Test
    fun completedMigrationNeverRunsAgain() {
        assertEquals(
            DirectBootPreferencePolicy.Action.ALREADY_COMPLETE,
            DirectBootPreferencePolicy.action(
                userUnlocked = false,
                migrationComplete = true,
                deviceValuesPresent = false,
            ),
        )
    }
}
