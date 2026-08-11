package com.aios.context;

import com.aios.context.ConversationIdentity;
import com.aios.context.ContextDocument;
import com.aios.context.ContextSnippet;

interface ICommunicationContext {
    String getStoreInstanceId();
    ConversationIdentity resolveIdentity(String rawAddress, String countryIso);
    void upsert(in ContextDocument document);
    void deleteSource(String sourceType, String sourceId, long revision);
    long deleteSourceType(String sourceType, long revision);
    List<ContextSnippet> query(
            in ConversationIdentity identity, in String[] sourceTypes,
            String query, int limit, long nowEpochMillis);
    void purgeExpired(long nowEpochMillis);
}
