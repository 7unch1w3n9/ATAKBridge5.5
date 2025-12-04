package com.atakmap.android.LoRaBridge.ChatMessage;

import android.content.Context;
import android.os.Bundle;

import com.atakmap.android.LoRaBridge.Database.ChatRepository;
import com.atakmap.android.LoRaBridge.GenericMessage.CotSyncService;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

/**
 * OutgoingCoTEventInterceptor
 *
 * A pre-send interceptor that captures CoT events leaving ATAK before
 * they are broadcast. It allows the plugin to:
 *
 *   1) Correct / enrich outgoing GeoChat messages (b-t-f)
 *   2) Synchronize outgoing messages with the plugin’s MessageSyncService
 *   3) Intercept non-chat CoT messages and pass them to CotSyncService
 *
 * This class ensures proper bidirectional sync between:
 *   ATAK → Plugin DB → LoRa → ATAK
 *
 * Notes:
 *   - Only events with type b-t-f are treated as chat messages
 *   - Other events (b-t-f-d, b-t-f-r, a-f-G-U-C) are explicitly ignored
 *   - All remaining CoT events are forwarded to CotSyncService
 */
public class OutgoingCoTEventInterceptor implements CommsMapComponent.PreSendProcessor {

    private static final String TAG = "OutgoingCoTEventInterceptor";

    /** Provides message DB access (not heavily used here) */
    private final ChatRepository chatRepository;

    /** Main synchronization engine for incoming/outgoing chat messages */
    private final MessageSyncService syncService;


    /**
     * Construct the interceptor and bind it to the global MessageSyncService.
     */
    public OutgoingCoTEventInterceptor(Context context) {
        this.chatRepository = new ChatRepository(MapView.getMapView().getContext());
        this.syncService = MessageSyncService.getInstance(context);
    }


    /**
     * Intercepts all outgoing CoT events prior to transmission.
     *
     * Chat handling:
     *   - For "b-t-f", this method sets the sender UID on the __chat element
     *   - Forwards the event to MessageSyncService as if it were received,
     *     enabling the plugin to keep a consistent local state
     *
     * Non-chat handling:
     *   - Filter out well-known system types (b-t-f-d, b-t-f-r, a-f-G-U-C)
     *   - Forward all remaining events to CotSyncService
     *
     * @param event   The CoT event about to be sent
     * @param toUIDs  Resolved target device UIDs
     */
    @Override
    public void processCotEvent(CotEvent event, String[] toUIDs) {

        // -------------------------------------
        // Chat messages ("b-t-f")
        // -------------------------------------
        if ("b-t-f".equals(event.getType())) {
            Log.w(TAG, "PreSend ------------------------------------");

            // Inject sender UID from local device for consistency
            event.getDetail()
                    .getFirstChildByName(0, "__chat")
                    .setAttribute("sender", MapView.getDeviceUid());

            // Process message through the sync service as if it were incoming
            Bundle b = new Bundle();
            b.putString("receiverUid", toUIDs[0]);
            syncService.processIncomingCotEventFromGeoChat(event, b);
            return;
        }

        // -------------------------------------
        // Filter out system or control messages
        // -------------------------------------
        if ("b-t-f-d".equals(event.getType())
                || "b-t-f-r".equals(event.getType())
                || "a-f-G-U-C".equals(event.getType())) {

            Log.d(TAG, "Skip non a-h-g CoT: " + event.getType());
            return;
        }

        // -------------------------------------
        // Remaining events are delegated to CotSyncService
        // -------------------------------------
        CotSyncService.getInstance(MapView.getMapView().getContext())
                .processIncomingCotFromAtak(event);
    }
}
