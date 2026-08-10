package com.aios.context;

import com.aios.context.ConversationIdentity;
import com.aios.context.ContextDocument;
import com.aios.context.ContextSnippet;

interface ICommunicationContext {
    ConversationIdentity resolveIdentity(String rawAddress, String countryIso);
    void upsert(in ContextDocument document);
    void deleteSource(String sourceType, String sourceId, long revision);
    List<ContextSnippet> query(
            in ConversationIdentity identity, String query, int limit, long nowEpochMillis);
    void purgeExpired(long nowEpochMillis);
}
