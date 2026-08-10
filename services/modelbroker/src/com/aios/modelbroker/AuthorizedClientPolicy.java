package com.aios.modelbroker;

import android.content.Context;
import android.content.pm.PackageManager;

import com.aios.model.ModelRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Package/capability quotas layered on top of the signature permission. */
final class AuthorizedClientPolicy {
    static final class Rule {
        final String packageName;
        final Set<String> capabilities;
        final Set<String> workloads;
        final int maxSessions;
        final int maxOutputTokens;
        final boolean canControlCallState;

        Rule(
                String packageName,
                Set<String> capabilities,
                Set<String> workloads,
                int maxSessions,
                int maxOutputTokens,
                boolean canControlCallState) {
            this.packageName = packageName;
            this.capabilities = Set.copyOf(capabilities);
            this.workloads = Set.copyOf(workloads);
            this.maxSessions = maxSessions;
            this.maxOutputTokens = maxOutputTokens;
            this.canControlCallState = canControlCallState;
        }

        boolean allows(ModelRequest request) {
            return request != null
                    && capabilities.contains(request.capability)
                    && workloads.contains(request.workload)
                    && request.maxOutputTokens >= 0
                    && request.maxOutputTokens <= maxOutputTokens;
        }
    }

    private final PackageManager packageManager;
    private final Map<String, Rule> rules;

    private AuthorizedClientPolicy(PackageManager packageManager, Map<String, Rule> rules) {
        this.packageManager = packageManager;
        this.rules = Collections.unmodifiableMap(rules);
    }

    static AuthorizedClientPolicy denyAll(Context context) {
        return new AuthorizedClientPolicy(
                context.getPackageManager(), Collections.emptyMap());
    }

    static AuthorizedClientPolicy load(Context context, File policy) throws IOException {
        try {
            JSONObject root = new JSONObject(PolicyFileReader.readUtf8(policy));
            if (root.getInt("schema_version") != 1) {
                throw new IOException("unsupported authorized-client schema");
            }
            JSONArray clients = root.getJSONArray("clients");
            Map<String, Rule> rules = new HashMap<>();
            for (int index = 0; index < clients.length(); index++) {
                JSONObject client = clients.getJSONObject(index);
                String packageName = client.getString("package");
                Rule rule = new Rule(
                        packageName,
                        strings(client.getJSONArray("capabilities")),
                        strings(client.getJSONArray("workloads")),
                        client.getInt("max_sessions"),
                        client.getInt("max_output_tokens"),
                        client.getBoolean("can_control_call_state"));
                if (rule.maxSessions <= 0 || rule.maxOutputTokens <= 0
                        || rules.put(packageName, rule) != null) {
                    throw new IOException("invalid or duplicate client rule: " + packageName);
                }
            }
            return new AuthorizedClientPolicy(context.getPackageManager(), rules);
        } catch (JSONException error) {
            throw new IOException("cannot parse authorized-client policy", error);
        }
    }

    Rule resolveUid(int uid) {
        String[] packages = packageManager.getPackagesForUid(uid);
        if (packages == null || packages.length != 1) {
            return null;
        }
        return rules.get(packages[0]);
    }

    private static Set<String> strings(JSONArray values) throws JSONException {
        Set<String> result = new HashSet<>();
        for (int index = 0; index < values.length(); index++) {
            if (!result.add(values.getString(index))) {
                throw new JSONException("duplicate string value");
            }
        }
        return result;
    }
}
