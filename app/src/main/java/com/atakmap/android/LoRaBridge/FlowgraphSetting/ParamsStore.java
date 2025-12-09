package com.atakmap.android.LoRaBridge.FlowgraphSetting;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * ParamsStore
 *
 * Centralized storage for all flowgraph runtime parameters.
 * Used to persist LoRa PHY configuration and UDP settings that must survive
 * across ATAK plugin restarts.
 *
 * Backed by SharedPreferences and initialized explicitly through MapView or
 * application Context.
 *
 * Designed responsibilities:
 *   • Provide typed access for flowgraph configuration values
 *   • Separate UI settings layer from JNI / flowgraph execution layer
 *   • Ensure that only parameters explicitly set by the user are stored
 *   • Allow exporting existing parameters for debugging or diagnostics
 *
 * Notes:
 *   - Values are stored as raw strings. Conversion to numeric types happens
 *     inside FlowgraphEngine or the Rust layer.
 *   - Keys are aligned with the Rust / FutureSDR flowgraph parameter names.
 */
public final class ParamsStore {

    private static final String TAG = "ParamsStore";
    private static final String PREFS_NAME = "plugin_params_store";

    // Keys fully aligned with LoRa flowgraph parameter names
    public static final String K_UDP_BIND        = "udp_bind";
    public static final String K_UDP_SEND_TO     = "udp_send_to";
    public static final String K_CODE_RATE       = "code_rate";
    public static final String K_SF              = "spreading_factor";
    public static final String K_OVERSAMPLING    = "oversampling";
    public static final String K_SYNC_WORD       = "sync_word";
    public static final String K_BANDWIDTH       = "bandwidth";
    public static final String K_SOFT_DECODING   = "soft_decoding";

    /** Backing SharedPreferences instance */
    private static SharedPreferences sPrefs;

    /** Utility class: construction is prohibited */
    private ParamsStore() {}

    /**
     * Initialization using MapView (recommended for plugin consistency).
     * Ensures preferences live in the same application context as ATAK.
     */
    public static void init(MapView mapView) {
        Context app = mapView.getContext().getApplicationContext();
        sPrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Log.d(TAG, "Prefs file = " +
                app.getApplicationInfo().dataDir +
                "/shared_prefs/" + PREFS_NAME + ".xml");
    }

    /**
     * Alternate initialization using any Context.
     * Useful for tests or standalone environments.
     */
    public static void init(Context context) {
        Context app = context.getApplicationContext();
        sPrefs = app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        Log.d(TAG, "Prefs file = " +
                app.getApplicationInfo().dataDir +
                "/shared_prefs/" + PREFS_NAME + ".xml");
    }

    /** Safely access SharedPreferences after initialization */
    private static SharedPreferences prefs() {
        if (sPrefs == null)
            throw new IllegalStateException("ParamsStore not initialized");
        return sPrefs;
    }

    /**
     * Check whether a parameter key has been explicitly set.
     *
     * Useful to differentiate:
     *   - "key not set"  (parameter should use flowgraph default)
     *   - "key set but empty" (user provided value)
     */
    public static boolean has(String key) {
        return prefs().contains(key);
    }

    /**
     * Read raw parameter or return null when not set.
     *
     * This is the preferred method for FlowgraphEngine and JNI code.
     */
    @Nullable
    public static String tryGetRaw(String key) {
        SharedPreferences p = prefs();
        if (!p.contains(key)) return null;
        return p.getString(key, null);
    }

    /**
     * Convenience getter for UI components.
     * Useful when binding text fields (avoid NPE).
     */
    public static String getOrEmpty(String key) {
        return prefs().getString(key, "");
    }

    /**
     * Store or overwrite parameter value.
     * Called by configuration UI when user applies settings.
     */
    public static void put(String key, String val) {
        prefs().edit().putString(key, val).apply();
    }

    /** Remove a specific parameter */
    public static void remove(String key) {
        prefs().edit().remove(key).apply();
    }

    /** Clear all parameters (does not restore defaults) */
    public static void clearAll() {
        prefs().edit().clear().apply();
    }

    /**
     * Return a map of only explicitly stored parameters.
     *
     * Useful for debugging, exporting current radio configuration,
     * or constructing parameter bundles for the flowgraph.
     */
    public static Map<String, String> getAllExisting() {
        SharedPreferences p = prefs();
        Map<String, String> out = new HashMap<>();

        Map<String, ?> all = p.getAll();
        if (all != null) {
            for (Map.Entry<String, ?> e : all.entrySet()) {
                Object v = e.getValue();
                if (v instanceof String) {
                    out.put(e.getKey(), (String) v);
                }
            }
        }
        return out;
    }
}
