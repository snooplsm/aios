package com.aios.callintelligence;

/** Lets exactly one matching TTS or uplink terminal event consume active assistant audio. */
final class AssistantAudioIdentityGate {
    private Object speech;
    private Object uplink;

    synchronized boolean attach(Object speechIdentity, Object uplinkIdentity) {
        if (speechIdentity == null || uplinkIdentity == null
                || speech != null || uplink != null) {
            return false;
        }
        speech = speechIdentity;
        uplink = uplinkIdentity;
        return true;
    }

    synchronized boolean acceptsUplink(Object expectedUplink) {
        return expectedUplink != null && uplink == expectedUplink;
    }

    synchronized boolean consumeSpeech(Object expectedSpeech) {
        if (expectedSpeech == null || speech != expectedSpeech) return false;
        clear();
        return true;
    }

    synchronized boolean consumeUplink(Object expectedUplink) {
        if (!acceptsUplink(expectedUplink)) return false;
        clear();
        return true;
    }

    synchronized void clear() {
        speech = null;
        uplink = null;
    }
}
