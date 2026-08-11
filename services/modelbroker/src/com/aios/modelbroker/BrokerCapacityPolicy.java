package com.aios.modelbroker;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/** Loads the AVB-protected product policy fields that govern broker capacity. */
final class BrokerCapacityPolicy {
    private BrokerCapacityPolicy() {}

    static SessionCapacityPolicy load(File path) throws IOException {
        try {
            JSONObject root = new JSONObject(PolicyFileReader.readUtf8(path));
            if (requiredInt(root, "schema_version") != 1) {
                throw new IOException("unsupported product-policy schema");
            }
            JSONObject broker = root.getJSONObject("broker");
            if (!"signature_permission".equals(requiredString(broker, "access"))
                    || !requiredBoolean(broker, "preempt_background_on_call")
                    || requiredBoolean(broker, "raw_model_file_access")
                    || !"evidence_bound_fail_closed".equals(
                            requiredString(broker, "release_model_admission"))
                    || !"known_device_research_candidates".equals(
                            requiredString(broker, "debug_model_admission"))) {
                throw new IOException("unsupported broker product policy");
            }
            try {
                return new SessionCapacityPolicy(
                        requiredInt(broker, "global_session_capacity"),
                        requiredInt(broker, "call_asr_stream_capacity"),
                        requiredInt(broker, "call_agent_capacity"));
            } catch (IllegalArgumentException error) {
                throw new IOException("invalid broker session capacities", error);
            }
        } catch (JSONException error) {
            throw new IOException("cannot parse broker product policy", error);
        }
    }

    private static int requiredInt(JSONObject object, String key)
            throws IOException, JSONException {
        Object value = object.get(key);
        if (!(value instanceof Integer)) {
            throw new IOException("broker product-policy integer has invalid type: " + key);
        }
        return (Integer) value;
    }

    private static boolean requiredBoolean(JSONObject object, String key)
            throws IOException, JSONException {
        Object value = object.get(key);
        if (!(value instanceof Boolean)) {
            throw new IOException("broker product-policy boolean has invalid type: " + key);
        }
        return (Boolean) value;
    }

    private static String requiredString(JSONObject object, String key)
            throws IOException, JSONException {
        Object value = object.get(key);
        if (!(value instanceof String)) {
            throw new IOException("broker product-policy string has invalid type: " + key);
        }
        return (String) value;
    }
}
