package com.atakmap.android.LoRaBridge.JNI;

/**
 * UsbHackrfManagerHolder
 *
 * Simple static holder for a global UsbHackrfManager instance.
 * This is used to make the USB manager accessible from different parts
 * of the plugin without passing it through every constructor.
 *
 * Notes:
 *  - The instance field is marked volatile to make reads and writes
 *    visible across threads.
 *  - No synchronization is performed, so callers should treat this as
 *    a simple shared reference and avoid complex concurrent mutation.
 */
public final class UsbHackrfManagerHolder {

    /** Globally accessible UsbHackrfManager instance */
    private static volatile UsbHackrfManager INSTANCE;

    /** Private constructor to prevent instantiation */
    private UsbHackrfManagerHolder() {}

    /**
     * Register the current UsbHackrfManager.
     *
     * Typically called once from plugin lifecycle code after creating
     * the manager in onStart.
     *
     * @param manager UsbHackrfManager to store globally
     */
    public static void set(UsbHackrfManager manager) {
        INSTANCE = manager;
    }

    /**
     * Get the globally stored UsbHackrfManager instance.
     *
     * @return currently registered UsbHackrfManager, or null if none was set
     */
    public static UsbHackrfManager get() {
        return INSTANCE;
    }
}
