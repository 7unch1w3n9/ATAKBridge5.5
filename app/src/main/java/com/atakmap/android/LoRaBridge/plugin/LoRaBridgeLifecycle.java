package com.atakmap.android.LoRaBridge.plugin;

import com.atak.plugins.impl.AbstractPlugin;
import com.atak.plugins.impl.PluginContextProvider;
import com.atakmap.android.LoRaBridge.LoRaBridgeMapComponent;
import gov.tak.api.plugin.IServiceController;
/**
 * LoRaBridgeLifecycle
 *
 * Main lifecycle entry point for the LoRaBridge ATAK plugin.
 * This class integrates the plugin with ATAK's Lifecycle API and manages
 * initialization, teardown, USB handling, native engine startup, map components,
 * and synchronization services.
 *
 * Responsibilities:
 *  - Validate that the host provides an ATAK MapView
 *  - Create and manage the LoRaBridgeMapComponent overlay
 *  - Initialize shared stores (ContactStore, ParamsStore)
 *  - Initialize and start UdpManager, FlowgraphEngine and native libraries
 *  - Bind USB HackRF handling (UsbHackrfManager)
 *  - Start message synchronization services (chat and CoT)
 *  - Register outgoing CoT interceptors
 */
public class LoRaBridgeLifecycle extends AbstractPlugin {

    public LoRaBridgeLifecycle(IServiceController serviceController) {
        super(
                serviceController,
                new LoRaBridgeTool(serviceController.getService(PluginContextProvider.class).getPluginContext()),
                new LoRaBridgeMapComponent()
        );
    }

    @Override
    public void onStart() {
        // register toolbar and mapcomponent handled by super
        super.onStart();
        // start backend independent of map

    }

    @Override
    public void onStop() {
        // stop backend first (or after) depending desired behavior
        super.onStop();
    }

}


