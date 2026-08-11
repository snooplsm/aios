package com.aios.modelbroker;

import java.io.File;
import java.util.List;

/** Immutable result of verifying one artifact against the AVB-protected manifest. */
final class VerifiedArtifact {
    final String modelId;
    final File file;
    final String sha256;
    final long sizeBytes;
    final String runtime;
    final String backend;
    final long estimatedResidentMb;
    final List<String> capabilities;
    final List<String> languages;

    VerifiedArtifact(
            String modelId,
            File file,
            String sha256,
            long sizeBytes,
            String runtime,
            String backend,
            List<String> capabilities,
            List<String> languages) {
        this(modelId, file, sha256, sizeBytes, runtime, backend,
                capabilities, languages, Long.MAX_VALUE);
    }

    VerifiedArtifact(
            String modelId,
            File file,
            String sha256,
            long sizeBytes,
            String runtime,
            String backend,
            List<String> capabilities,
            List<String> languages,
            long estimatedResidentMb) {
        if (estimatedResidentMb <= 0L) {
            throw new IllegalArgumentException("resident-memory estimate must be positive");
        }
        this.modelId = modelId;
        this.file = file;
        this.sha256 = sha256;
        this.sizeBytes = sizeBytes;
        this.runtime = runtime;
        this.backend = backend;
        this.estimatedResidentMb = estimatedResidentMb;
        this.capabilities = List.copyOf(capabilities);
        this.languages = List.copyOf(languages);
    }

    VerifiedArtifact withEstimatedResidentMb(long value) {
        return new VerifiedArtifact(
                modelId, file, sha256, sizeBytes, runtime, backend,
                capabilities, languages, value);
    }
}
