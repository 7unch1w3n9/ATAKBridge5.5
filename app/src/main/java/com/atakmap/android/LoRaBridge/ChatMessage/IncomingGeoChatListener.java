package com.atakmap.android.LoRaBridge.ChatMessage;

import android.content.Context;
import android.os.Bundle;

import com.atakmap.android.LoRaBridge.Database.ChatRepository;
import com.atakmap.comms.CotServiceRemote;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

/**
 * IncomingGeoChatListener
 *
 * Listener receiving CoT events that are delivered to this device via
 * the CotServiceRemote IPC mechanism.
 *
 * Originally, this class directly parsed GeoChat events and inserted
 * them into the plugin’s local message database. In the current design,
 * it simply forwards all received CoT messages to the MessageSyncService,
 * which centralizes CoT-related synchronization, deduplication and routing.
 *
 * Responsibilities:
 *   - Maintain a CotServiceRemote connection
 *   - Receive CoT events via onCotEvent()
 *   - Forward events to the central MessageSyncService
 *
 * Notes:
 *   - Outdated direct parsing is intentionally removed
 *   - This component is still required because CotServiceRemote requires
 *     a listener instance for message callbacks
 */
public class IncomingGeoChatListener implements CotServiceRemote.CotEventListener {

    /** Remote CoT service entrypoint */
    private final CotServiceRemote cotServiceRemote;

    private final String TAG = "IncomingGeoChatListener";

    /** Legacy repository (still constructed but no longer used directly) */
    private final ChatRepository chatRepository;

    /** Central synchronization service */
    private final MessageSyncService syncService;


    /**
     * Create and connect to CotServiceRemote.
     * The listener starts receiving CoT events once connected.
     */
    public IncomingGeoChatListener(Context context) {

        // Repository currently unused, but kept for backward compatibility
        this.chatRepository = new ChatRepository(context);

        // MessageSyncService handles modern CoT parsing
        this.syncService = MessageSyncService.getInstance(context);

        cotServiceRemote = new CotServiceRemote();
        cotServiceRemote.setCotEventListener(this);

        cotServiceRemote.connect(new CotServiceRemote.ConnectionListener() {
            @Override
            public void onCotServiceConnected(Bundle bundle) {
                Log.d("ChatCotBridge", "CotService connected");
            }

            @Override
            public void onCotServiceDisconnected() {
                Log.d("ChatCotBridge", "CotService disconnected");
            }
        });
    }


    /**
     * Callback for incoming CoT events.
     * This is the entrypoint for all remote GeoChat / messaging CoT updates.
     *
     * @param cotEvent The received CoT event
     * @param bundle   Additional metadata from CotServiceRemote
     */
    @Override
    public void onCotEvent(CotEvent cotEvent, Bundle bundle) {

        Log.d(TAG, "Remote CoT event received ------------------------------------");
        // Forward to centralized synchronization logic
        syncService.processIncomingCotEventFromGeoChat(cotEvent, bundle);
    }


    /**
     * Disconnect from CotServiceRemote.
     * Called when shutting down plugin lifecycle.
     */
    public void shutdown() {
        if (cotServiceRemote != null) {
            cotServiceRemote.disconnect();
            Log.d(TAG, "CotServiceRemote disconnected");
        }
    }
}
