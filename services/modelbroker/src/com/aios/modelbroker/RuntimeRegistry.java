package com.aios.modelbroker;

import android.content.Context;
import android.os.Build;
import android.os.RemoteException;
import android.util.Log;

import com.aios.model.IModelCallback;
import com.aios.model.ModelRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Runtime activation boundary backed by allowlisted, crash-isolated providers. */
final class RuntimeRegistry implements AutoCloseable {
    private static final String TAG = "AiosRuntimeRegistry";

    private final Map<String, RuntimeAdapter> adapters;

    private RuntimeRegistry(Map<String, RuntimeAdapter> adapters) {
        this.adapters = Collections.unmodifiableMap(adapters);
    }

    static RuntimeRegistry modelFree() {
        return new RuntimeRegistry(Map.of());
    }

    static RuntimeRegistry load(Context context, File path) throws IOException {
        try {
            JSONObject root = new JSONObject(PolicyFileReader.readUtf8(path));
            if (root.getInt("schema_version") != 1) {
                throw new IOException("unsupported runtime catalog schema");
            }
            int apiVersion = root.getInt("provider_api_version");
            Map<String, Set<String>> allowed = selectBackends(
                    root.getJSONArray("device_profiles"),
                    Build.DEVICE,
                    BrokerProductProperties.isDebuggableBuild());
            Map<String, RuntimeAdapter> adapters = new HashMap<>();
            JSONArray providers = root.getJSONArray("providers");
            for (int index = 0; index < providers.length(); index++) {
                JSONObject value = providers.getJSONObject(index);
                String runtime = value.getString("runtime");
                Set<String> backends = allowed.getOrDefault(runtime, Set.of());
                if (backends.isEmpty()) {
                    continue;
                }
                RemoteRuntimeAdapter.Spec spec = new RemoteRuntimeAdapter.Spec(
                        apiVersion,
                        runtime,
                        value.getString("package"),
                        value.getString("service_class"),
                        value.getString("action"),
                        value.getString("implementation_version"),
                        backends);
                RuntimeAdapter previous = adapters.put(
                        runtime, new RemoteRuntimeAdapter(context, spec));
                if (previous != null) {
                    throw new IOException("duplicate runtime provider: " + runtime);
                }
            }
            RuntimeRegistry registry = new RuntimeRegistry(adapters);
            registry.start();
            return registry;
        } catch (JSONException error) {
            throw new IOException("cannot parse runtime catalog", error);
        }
    }

    boolean supports(VerifiedArtifact artifact) {
        if (artifact == null) {
            return false;
        }
        RuntimeAdapter adapter = adapters.get(artifact.runtime);
        return adapter != null && adapter.supportsBackend(artifact.backend);
    }

    RuntimeAdapter.Session open(
            VerifiedArtifact artifact,
            ModelRequest request,
            IModelCallback callback) throws IOException, RemoteException {
        RuntimeAdapter adapter = adapters.get(artifact.runtime);
        if (adapter == null || !adapter.supportsBackend(artifact.backend)) {
            throw new IOException("no ready runtime for " + artifact.runtime
                    + "/" + artifact.backend);
        }
        return adapter.open(artifact, request, callback);
    }

    @Override
    public void close() {
        for (RuntimeAdapter adapter : adapters.values()) {
            adapter.close();
        }
    }

    private void start() {
        for (RuntimeAdapter adapter : adapters.values()) {
            adapter.start();
        }
    }

    private static Map<String, Set<String>> selectBackends(
            JSONArray profiles, String device, boolean debuggable)
            throws JSONException, IOException {
        JSONObject fallback = null;
        JSONObject selected = null;
        for (int index = 0; index < profiles.length(); index++) {
            JSONObject profile = profiles.getJSONObject(index);
            List<String> devices = strings(profile.getJSONArray("devices"));
            if (devices.contains(device)) {
                selected = profile;
                break;
            }
            if (devices.contains("*")) {
                fallback = profile;
            }
        }
        if (selected == null) {
            selected = fallback;
        }
        if (selected == null
                || (selected.optBoolean("debuggable_only", false) && !debuggable)) {
            Log.i(TAG, "no enabled runtime profile for " + device);
            return Map.of();
        }
        JSONObject values = selected.getJSONObject("runtime_backends");
        Map<String, Set<String>> result = new HashMap<>();
        for (java.util.Iterator<String> keys = values.keys(); keys.hasNext();) {
            String runtime = keys.next();
            Set<String> backends = new HashSet<>(strings(values.getJSONArray(runtime)));
            if (backends.isEmpty()) {
                throw new IOException("runtime backend set is empty: " + runtime);
            }
            result.put(runtime, Set.copyOf(backends));
        }
        Log.i(TAG, "selected runtime profile " + selected.getString("id"));
        return Collections.unmodifiableMap(result);
    }

    private static List<String> strings(JSONArray values) throws JSONException {
        List<String> result = new ArrayList<>(values.length());
        for (int index = 0; index < values.length(); index++) {
            result.add(values.getString(index));
        }
        return result;
    }
}
