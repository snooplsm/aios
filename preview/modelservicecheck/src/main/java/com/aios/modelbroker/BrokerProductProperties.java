package com.aios.modelbroker;

/** Compile-check substitute that cannot enable research-only model/runtime admission. */
final class BrokerProductProperties {
    private BrokerProductProperties() {}

    static boolean isDebuggableBuild() {
        return false;
    }
}
