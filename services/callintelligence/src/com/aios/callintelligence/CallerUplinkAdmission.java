package com.aios.callintelligence;

/** Separates release validation from the explicit userdebug-only manual test path. */
final class CallerUplinkAdmission {
    private CallerUplinkAdmission() {}

    static boolean developmentTestActive(
            boolean releaseValidated,
            boolean debuggableBuild,
            boolean explicitOptIn) {
        return !releaseValidated && debuggableBuild && explicitOptIn;
    }

    static boolean manualAnswerAllowed(
            boolean releaseValidated,
            boolean debuggableBuild,
            boolean explicitOptIn) {
        return releaseValidated
                || developmentTestActive(releaseValidated, debuggableBuild, explicitOptIn);
    }

    static boolean automaticAnswerAllowed(boolean releaseValidated) {
        return releaseValidated;
    }
}
