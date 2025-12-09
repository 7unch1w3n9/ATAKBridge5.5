package com.atakmap.android.LoRaBridge.FlowgraphSetting;

import androidx.annotation.Nullable;

/**
 * ParamBridge
 *
 * Lightweight helper class that exposes a safe accessor for parameters
 * stored inside ParamsStore. This acts as a minimal abstraction layer
 * between UI/configuration components and the underlying persistent
 * storage of flowgraph parameters.
 *
 * Responsibilities:
 *   • Provide a sanitized lookup helper for flowgraph runtime parameters
 *   • Normalize empty or missing values into null
 *
 * Notes:
 *   - Wrapper is intentionally minimal because the ParamsStore API
 *     may expand in future development (Phase 2: per device profiles,
 *     dynamic LoRa parameter updates, etc.)
 */
public final class ParamBridge {

    /** Private constructor prevents instantiation (static utility class) */
    private ParamBridge() {}

    /**
     * Attempt to retrieve a parameter value by key.
     *
     * @param key Parameter identifier stored in ParamsStore
     * @return String value, or null if not found or empty
     */
    @Nullable
    public static String tryGet(String key) {
        String v = ParamsStore.tryGetRaw(key);
        return (v == null || v.isEmpty()) ? null : v;
    }
}
