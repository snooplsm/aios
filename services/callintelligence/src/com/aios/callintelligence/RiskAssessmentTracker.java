package com.aios.callintelligence;

import java.util.function.LongSupplier;

/** Serializes heuristic/model assessment changes into a monotonic public stream. */
final class RiskAssessmentTracker {
    static final String SOURCE_HEURISTIC = "heuristic";
    static final String SOURCE_MODEL = "model";

    static final class Update {
        final SpamRiskEngine.Assessment assessment;
        final String source;
        final long revision;
        final long observedAtEpochMillis;

        Update(
                SpamRiskEngine.Assessment assessment,
                String source,
                long revision,
                long observedAtEpochMillis) {
            this.assessment = assessment;
            this.source = source;
            this.revision = revision;
            this.observedAtEpochMillis = observedAtEpochMillis;
        }
    }

    private final SpamRiskEngine heuristic;
    private final LongSupplier clock;
    private Update published;
    private boolean hasModelAssessment;
    private int modelRiskScore;
    private String modelLabel;
    private String modelReasonCode;
    private long revision;

    RiskAssessmentTracker(SpamRiskEngine heuristic) {
        this(heuristic, System::currentTimeMillis);
    }

    RiskAssessmentTracker(SpamRiskEngine heuristic, LongSupplier clock) {
        this.heuristic = heuristic;
        this.clock = clock;
    }

    synchronized Update initial() {
        return changedCombined();
    }

    synchronized Update current() {
        return published;
    }

    synchronized Update observeHeuristic(String text, String language) {
        heuristic.observe(text, language);
        return changedCombined();
    }

    synchronized Update observeModel(int riskScore, String label, String reasonCode) {
        if (!hasModelAssessment || riskScore > modelRiskScore
                || (modelRiskScore <= 15
                && SpamRiskEngine.LIKELY_LEGITIMATE.equals(label))) {
            hasModelAssessment = true;
            modelRiskScore = riskScore;
            modelLabel = label;
            modelReasonCode = reasonCode;
        }
        return changedCombined();
    }

    private Update changedCombined() {
        SpamRiskEngine.Assessment currentHeuristic = heuristic.current();
        SpamRiskEngine.Assessment combined = currentHeuristic;
        String source = SOURCE_HEURISTIC;
        if (hasModelAssessment
                && (modelRiskScore > currentHeuristic.score
                || (SpamRiskEngine.UNKNOWN.equals(currentHeuristic.label)
                && SpamRiskEngine.LIKELY_LEGITIMATE.equals(modelLabel)
                && currentHeuristic.score <= 15))) {
            combined = new SpamRiskEngine.Assessment(
                    modelRiskScore, modelLabel, "model_" + modelReasonCode);
            source = SOURCE_MODEL;
        }
        SpamRiskEngine.Assessment previous = published == null
                ? null : published.assessment;
        if (!combined.differsFrom(previous)) return null;
        published = new Update(combined, source, ++revision, clock.getAsLong());
        return published;
    }
}
