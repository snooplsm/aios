package com.aios.contextintelligence;

import java.util.List;

/** Builds a parameterized source filter after ContextPolicy validates the scope. */
final class ContextSourceScope {
    private ContextSourceScope() {}

    static String selectionClause(String[] sourceTypes, List<String> arguments) {
        if (sourceTypes == null || sourceTypes.length < 1 || arguments == null) {
            throw new IllegalArgumentException("source scope is required");
        }
        StringBuilder clause = new StringBuilder(" AND e.source_type IN (");
        for (int index = 0; index < sourceTypes.length; index++) {
            String sourceType = sourceTypes[index];
            if (sourceType == null || sourceType.isBlank()) {
                throw new IllegalArgumentException("source scope contains an empty value");
            }
            if (index > 0) clause.append(',');
            clause.append('?');
            arguments.add(sourceType);
        }
        return clause.append(')').toString();
    }
}
