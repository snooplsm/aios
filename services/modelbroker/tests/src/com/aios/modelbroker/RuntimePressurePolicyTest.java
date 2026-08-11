package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;

import java.io.File;
import java.util.List;

import org.junit.Test;

public final class RuntimePressurePolicyTest {
    private static final VerifiedArtifact QUALITY = artifact("quality", 2_200L);
    private static final VerifiedArtifact COMPACT = artifact("compact", 840L);

    @Test
    public void healthySystemPreservesMeasuredQualityOrder() {
        RuntimePressurePolicy.Decision decision = RuntimePressurePolicy.decide(
                WorkClass.CALL_AGENT, true, false, 2);

        assertEquals(RuntimePressurePolicy.Decision.PREFER_QUALITY, decision);
        assertEquals(List.of(QUALITY, COMPACT), RuntimePressurePolicy.order(
                List.of(QUALITY, COMPACT), decision));
    }

    @Test
    public void constrainedCallPrefersLowerMeasuredResidentMemory() {
        RuntimePressurePolicy.Decision memory = RuntimePressurePolicy.decide(
                WorkClass.CALL_AGENT, true, true, 0);
        RuntimePressurePolicy.Decision thermal = RuntimePressurePolicy.decide(
                WorkClass.CALL_RX, true, false, 3);
        RuntimePressurePolicy.Decision unknown = RuntimePressurePolicy.decide(
                WorkClass.CALL_AGENT, false, false,
                RuntimePressurePolicy.THERMAL_STATUS_UNKNOWN);

        assertEquals(RuntimePressurePolicy.Decision.PREFER_LOWER_MEMORY, memory);
        assertEquals(RuntimePressurePolicy.Decision.PREFER_LOWER_MEMORY, thermal);
        assertEquals(RuntimePressurePolicy.Decision.PREFER_LOWER_MEMORY, unknown);
        assertEquals(List.of(COMPACT, QUALITY), RuntimePressurePolicy.order(
                List.of(QUALITY, COMPACT), memory));
    }

    @Test
    public void constrainedOrUnmeasurableBackgroundWorkIsBlocked() {
        assertEquals(RuntimePressurePolicy.Decision.BLOCK_BACKGROUND,
                RuntimePressurePolicy.decide(
                        WorkClass.MEDIA_BACKGROUND, true, false, 3));
        assertEquals(RuntimePressurePolicy.Decision.BLOCK_BACKGROUND,
                RuntimePressurePolicy.decide(
                        WorkClass.MEDIA_BACKGROUND, false, false,
                        RuntimePressurePolicy.THERMAL_STATUS_UNKNOWN));
    }

    private static VerifiedArtifact artifact(String id, long residentMb) {
        return new VerifiedArtifact(
                id,
                new File("/" + id),
                "a".repeat(64),
                1L,
                "litert_lm",
                "cpu",
                List.of("text_generation"),
                List.of("en", "es"),
                residentMb);
    }
}
