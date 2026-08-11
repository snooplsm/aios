package com.aios.mediaintelligence;

import java.io.IOException;

/** Serializes JobScheduler stop ownership with final durable publication. */
final class MediaJobCommitFence {
    interface Operation {
        void run() throws IOException;
    }

    private boolean stopped = true;

    synchronized void start() {
        stopped = false;
    }

    synchronized void stop() {
        stopped = true;
    }

    synchronized boolean runIfActive(Operation operation) throws IOException {
        if (stopped) return false;
        operation.run();
        return true;
    }

    synchronized boolean isStopped() {
        return stopped;
    }
}
