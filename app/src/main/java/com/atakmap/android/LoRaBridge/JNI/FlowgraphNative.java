package com.atakmap.android.LoRaBridge.JNI;

/**
 * FlowgraphNative
 *
 * JNI bridge exposing native Rust functions to the Java layer.
 * These methods correspond to the LoRa flowgraph implemented in Rust/FutureSDR,
 * compiled as a shared library and loaded by PluginNativeLoader.
 *
 * The FlowgraphEngine class calls these functions to:
 *  - Start the Rust SDR pipeline with an opened HackRF file descriptor
 *  - Start the Rust pipeline in default mode (no FD)
 *  - Trigger a graceful shutdown of the flowgraph runtime
 *
 * Notes:
 *  - All methods are static and native
 *  - The actual implementations live in the Rust JNI library ("atak")
 *  - run_flowgraph_with_fd(int fd):
 *        Used when the plugin opens the HackRF via UsbManager and
 *        hands the Linux file descriptor to Rust.
 *  - run_flowgraph():
 *        Used for debug or fallback startup where Rust opens the device itself.
 *  - shutdown():
 *        Asks the Rust runtime to stop all blocks and exit cleanly.
 */
public class FlowgraphNative {

    /**
     * Start the Rust flowgraph using an existing file descriptor.
     *
     * @param fd Unix/Linux file descriptor representing an open HackRF device
     * @return return code from Rust flowgraph execution
     */
    public static native int run_flowgraph_with_fd(int fd);

    /**
     * Start the Rust flowgraph without providing a file descriptor.
     * The Rust side will perform device discovery and open hardware itself.
     */
    public static native void run_flowgraph();

    /**
     * Request the Rust flowgraph to shut down gracefully.
     * This method is safe to call multiple times (idempotent).
     */
    public static native void shutdown();
}
