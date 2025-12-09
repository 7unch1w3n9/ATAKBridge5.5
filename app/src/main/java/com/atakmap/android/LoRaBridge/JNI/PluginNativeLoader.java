package com.atakmap.android.LoRaBridge.JNI;

import android.content.Context;
import android.system.Os;
import android.system.ErrnoException;
import android.util.Log;

import java.io.File;

/**
 * PluginNativeLoader
 *
 * Responsible for loading all native libraries required by the LoRaBridge plugin.
 * This class configures environment variables such as SOAPY_SDR_PLUGIN_PATH
 * and then loads a fixed set of native shared objects (.so files).
 *
 * Characteristics:
 * - Called once during plugin startup (Lifecycle.onStart)
 * - Ensures native libs are loaded only a single time
 * - Handles repeated loads gracefully by ignoring “already opened by ClassLoader”
 * - Exposes no public constructor (utility class)
 *
 * Libraries loaded:
 *   - usb1.0          → libusb for Android
 *   - hackrf          → official HackRF driver
 *   - SoapySDR        → generic SDR framework
 *   - HackRFSupport   → SoapySDR hardware module for HackRF
 *   - atak            → The Rust JNI bridge compiled by the plugin
 *
 * These names correspond to System.loadLibrary("name").
 */
public final class PluginNativeLoader {

    private static final String TAG = "PluginNativeLoader";

    /** Tracks whether libraries have been loaded */
    private static boolean sLoaded = false;
    private static String ndl = null;

    /**
     * Initialize and load all native libraries.
     * Safe to call multiple times; actual loading occurs only once.
     *
     * @param ctx Application context used to obtain nativeLibraryDir
     */
    public static synchronized void init(Context ctx) {
        if (sLoaded) return;

        try {
            ndl = ctx.getPackageManager()
                    .getApplicationInfo(ctx.getPackageName(), 0)
                    .nativeLibraryDir;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "native library loading will fail, unable to grab the nativeLibraryDir from the package name");
        }

        try {
            Os.setenv(
                    "SOAPY_SDR_PLUGIN_PATH",
                    ctx.getApplicationInfo().nativeLibraryDir,
                    true
            );
        } catch (ErrnoException ignore) {}

        try {
            Os.setenv("SOAPY_SDR_LOG_LEVEL", "DEBUG", true);
        } catch (ErrnoException ignore) {}

        sLoaded = true;
    }

    /**
     * Load a library but ignore the harmless "already opened" error.
     *
     * @param lib Native library base name (as used in System.loadLibrary)
     */
    private static void safeLoad(String lib) {
        try {
            if (ndl != null) {
                String path = ndl + File.separator + System.mapLibraryName(lib);
                Log.d(TAG, "Loading native lib from " + path);
                System.load(path);
            } else {
                System.loadLibrary(lib);
            }
        } catch (UnsatisfiedLinkError e) {
            String msg = String.valueOf(e.getMessage());
            if (msg.contains("already opened by ClassLoader")) {
                Log.w(TAG, lib + " already loaded, ignore");
            } else {
                throw e;
            }
        }
    }

    public static synchronized void loadAll() {
        safeLoad("usb1.0");
        safeLoad("hackrf");
        safeLoad("SoapySDR");
        safeLoad("HackRFSupport");
        safeLoad("atak");
    }
}
