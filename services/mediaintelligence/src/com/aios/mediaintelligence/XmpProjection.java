package com.aios.mediaintelligence;

import java.time.Instant;
import java.util.List;

/** Builds the portable subset; this class never writes or replaces media. */
final class XmpProjection {
    private static final String NAMESPACE = "https://aios.dev/ns/media/1.0/";

    private XmpProjection() {}

    static String build(
            String caption,
            List<String> tags,
            String language,
            String modelId,
            String modelDigest,
            long inferredAtEpochMillis,
            float confidence) {
        StringBuilder tagXml = new StringBuilder();
        for (String tag : tags) {
            tagXml.append("<rdf:li>").append(escape(tag)).append("</rdf:li>");
        }
        return "<?xpacket begin='\uFEFF' id='W5M0MpCehiHzreSzNTczkc9d'?>"
                + "<x:xmpmeta xmlns:x='adobe:ns:meta/'>"
                + "<rdf:RDF xmlns:rdf='http://www.w3.org/1999/02/22-rdf-syntax-ns#'>"
                + "<rdf:Description rdf:about='' xmlns:aios='" + NAMESPACE + "'"
                + " aios:schemaVersion='1'"
                + " aios:language='" + escape(language) + "'"
                + " aios:modelId='" + escape(modelId) + "'"
                + " aios:modelDigest='" + escape(modelDigest) + "'"
                + " aios:inferredAt='" + escape(Instant.ofEpochMilli(
                        inferredAtEpochMillis).toString()) + "'"
                + " aios:confidence='" + confidence + "'>"
                + "<aios:caption>" + escape(caption) + "</aios:caption>"
                + "<aios:tags><rdf:Bag>" + tagXml + "</rdf:Bag></aios:tags>"
                + "</rdf:Description></rdf:RDF></x:xmpmeta>"
                + "<?xpacket end='w'?>";
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
