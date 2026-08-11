package com.aios.mediaintelligence;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

public final class MediaJobCommitFenceTest {
    @Test
    public void stoppedJobCannotPublish() throws Exception {
        MediaJobCommitFence fence = new MediaJobCommitFence();
        AtomicInteger publications = new AtomicInteger();

        assertFalse(fence.runIfActive(publications::incrementAndGet));
        assertEquals(0, publications.get());
    }

    @Test
    public void activeJobCanPublishOnceInsideFence() throws Exception {
        MediaJobCommitFence fence = new MediaJobCommitFence();
        AtomicInteger publications = new AtomicInteger();
        fence.start();

        assertTrue(fence.runIfActive(publications::incrementAndGet));
        assertEquals(1, publications.get());
    }

    @Test
    public void stopWaitsForInProgressPublication() throws Exception {
        MediaJobCommitFence fence = new MediaJobCommitFence();
        fence.start();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> publication = workers.submit(() -> fence.runIfActive(() -> {
                entered.countDown();
                try {
                    if (!release.await(1L, TimeUnit.SECONDS)) {
                        throw new AssertionError("test publication was not released");
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(error);
                }
            }));
            assertTrue(entered.await(1L, TimeUnit.SECONDS));
            CountDownLatch stopStarted = new CountDownLatch(1);
            Future<?> stop = workers.submit(() -> {
                stopStarted.countDown();
                fence.stop();
            });
            assertTrue(stopStarted.await(1L, TimeUnit.SECONDS));
            try {
                stop.get(50L, TimeUnit.MILLISECONDS);
                throw new AssertionError("stop crossed an active publication");
            } catch (TimeoutException expected) {
                // The publication owns the fence until its durable operation returns.
            }
            release.countDown();
            assertTrue(publication.get(1L, TimeUnit.SECONDS));
            stop.get(1L, TimeUnit.SECONDS);
            assertTrue(fence.isStopped());
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }
}
