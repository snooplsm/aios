package com.aios.phone.model

object AssistantCallSemantics {
    fun shouldReplace(currentRevision: Long?, candidateRevision: Long): Boolean =
        candidateRevision > 0L && (currentRevision == null || candidateRevision > currentRevision)
}
