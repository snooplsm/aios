package com.aios.modelbroker;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class EmbeddingWorkClassTest {
    @Test
    public void queryIsInteractiveAndIndexingIsPreemptible() {
        assertEquals(WorkClass.CALL_AGENT,
                WorkClass.fromAuthorizedWorkload("context_query"));
        assertEquals(WorkClass.MEDIA_BACKGROUND,
                WorkClass.fromAuthorizedWorkload("context_background"));
    }
}
