package com.atakmap.android.LoRaBridge.ChatMessage;

import android.content.Context;
import android.os.Bundle;

import com.atakmap.android.LoRaBridge.Database.ChatMessageEntity;
import com.atakmap.android.LoRaBridge.Database.ChatRepository;
import com.atakmap.android.LoRaBridge.phy.MessageConverter;
import com.atakmap.android.LoRaBridge.phy.UdpManager;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * MessageSyncService
 *
 * Core synchronization component that bridges between:
 *   1) GeoChat / CoT events and the local plugin database
 *   2) Local plugin messages and GeoChat
 *   3) Local plugin messages and the LoRa flowgraph
 *
 * Responsibilities:
 *  - Parse incoming CoT events, convert to ChatMessageEntity, store in DB
 *  - Take locally created messages, convert to CoT, and send to GeoChat
 *  - Encode messages for LoRa, send via UDP to the flowgraph
 *  - Decode messages from the flowgraph and reinject into GeoChat
 *
 * It also performs duplicate suppression so that messages are not processed
 * multiple times when they travel through different channels.
 */
public class MessageSyncService {

    private static final String TAG = "MessageSyncService";

    /** Singleton instance */
    private static MessageSyncService instance;

    /** Repository for persistent chat storage */
    private final ChatRepository chatRepository;

    /** Converts ChatMessageEntity <-> CoT event */
    private final IncomingPluginManager incomingPluginManager;

    /** Tracks plugin originated messages to avoid reprocessing */
    private final MessageTracker messageTracker = new MessageTracker();

    /** Tracks PHY originated messages to avoid duplicate handling */
    private final Set<String> processedMessageIds = new HashSet<>();

    /** Converter for encoding/decoding LoRa payloads */
    private final MessageConverter messageConverter;

    /** Shared UDP manager used as physical transport to the flowgraph */
    private final UdpManager udp = UdpManager.getInstance();


    /**
     * Private constructor. Use getInstance() to obtain the singleton.
     */
    private MessageSyncService(Context context) {
        this.chatRepository = new ChatRepository(context);
        this.incomingPluginManager = new IncomingPluginManager();
        this.messageConverter = new LoRaMessageConverter();

        // Register handler for incoming UDP chat payloads from the flowgraph
        udp.setChatHandler(this::handleFlowgraphMessage);
    }

    /**
     * Singleton accessor.
     */
    public static synchronized MessageSyncService getInstance(Context context) {
        if (instance == null) {
            instance = new MessageSyncService(context.getApplicationContext());
        }
        return instance;
    }


    // -------------------------------------------------------------
    // Incoming from GeoChat (CoT events) -> DB
    // -------------------------------------------------------------

    /**
     * Entry point for CoT events that originate from GeoChat.
     *
     * Security and filtering:
     *  - Rejects invalid CoT events.
     *  - Skips messages that are already marked as plugin processed (__plugin).
     *  - Distinguishes between self messages from GeoChat and other sources.
     *
     * @param event  raw CoT event
     * @param meta array of recipient UIDs resolved by the caller
     */
    public void processIncomingCotEventFromGeoChat(CotEvent event, Bundle meta) {
        if (!isValidGeoChatEvent(event)) return;

        CotDetail lora = event.getDetail().getFirstChildByName(0, "__plugin");
        if (lora != null) {
            Log.d(TAG, "Skipping plugin processed message to avoid loop");
            return;
        }

        ChatMessageEntity entity = ChatMessageFactory.fromCotEvent(event, meta);
        if (entity == null) {
            Log.w(TAG, "Failed to create entity from CoT event");
            return;
        }

        if (isSelfMessage(entity)) {
            if ("GeoChat".equals(entity.getOrigin())) {
                Log.d(TAG, "Processing self-originated GeoChat message");
                saveMessageIfNew(entity);
            } else {
                Log.d(TAG, "Ignoring self-originated non-GeoChat message");
            }
        } else {
            saveMessageIfNew(entity);
        }
    }


    // -------------------------------------------------------------
    // Outgoing from DB -> GeoChat (+ optional LoRa)
    // -------------------------------------------------------------

    /**
     * Process a message that was generated locally and stored in the database.
     *
     * Direction:
     *   Plugin (DB) → GeoChat (CoT) and possibly LoRa flowgraph.
     *
     * This only handles messages whose origin is "Plugin".
     */
    public void processOutgoingMessage(ChatMessageEntity message) {
        if ("Plugin".equals(message.getOrigin())) {

            if (messageTracker.isProcessed(message.getId())) {
                Log.d(TAG, "Message already processed: " + message.getId());
                return;
            }

            messageTracker.markProcessed(message.getId());

            // Convert to CoT and forward to GeoChat
            CotEvent cotEvent = incomingPluginManager.convertChatMessageToCotEvent(message);
            if (cotEvent != null) {
                if (cotEvent.getDetail() != null) {

                    incomingPluginManager.sendToGeoChat(cotEvent);
                }
            }

            // Optionally send a copy over LoRa
            if (shouldSendToLoRa(message)) {
                sendToFlowgraph(message);
            }
        }
    }

    /**
     * Encode a message and send it to the LoRa flowgraph over UDP.
     */
    private void sendToFlowgraph(ChatMessageEntity message) {
        try {
            byte[] body = messageConverter.encodeMessage(message);
            udp.sendChat(body);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send to Flowgraph", e);
        }
    }


    // -------------------------------------------------------------
    // Incoming from PHY (LoRa / flowgraph) -> DB -> GeoChat
    // -------------------------------------------------------------

    /**
     * Handler registered with UdpManager to process incoming UDP chat payloads
     * from the flowgraph. It decodes the payload into a ChatMessageEntity and
     * forwards it to processIncomingPhyMessage.
     */
    public void handleFlowgraphMessage(byte[] payload) {
        try {
            Log.d(TAG, "Received Flowgraph payload (" + payload.length + " bytes)");

            ChatMessageEntity message = messageConverter.decodeMessage(payload);

            if (message == null) {
                Log.w(TAG, "Failed to decode Flowgraph payload");
                return;
            }

            processIncomingPhyMessage(message);

        } catch (Exception e) {
            Log.e(TAG, "Error handling Flowgraph message", e);
        }
    }

    /**
     * Check if a given message id has already been processed by the plugin.
     */
    public boolean isMessageProcessed(String messageId) {
        return messageTracker.isProcessed(messageId);
    }


    // -------------------------------------------------------------
    // Validation and helpers
    // -------------------------------------------------------------

    /** Validate GeoChat CoT event shape. */
    private boolean isValidGeoChatEvent(CotEvent event) {
        return event != null
                && event.isValid()
                && "b-t-f".equals(event.getType())
                && event.getDetail() != null;
    }

    /** Check if message was sent by this device (self message). */
    private boolean isSelfMessage(ChatMessageEntity entity) {
        return entity.getSenderUid() != null
                && entity.getSenderUid().equals(MapView.getDeviceUid());
    }

    /**
     * Insert a message only if it does not already exist in the database.
     */
    private void saveMessageIfNew(ChatMessageEntity entity) {
        boolean inserted = chatRepository.insertIfNotExists(entity);
        if (inserted) {
            Log.d(TAG, "New message saved: " + entity.getMessage());
        } else {
            Log.d(TAG, "Duplicate message ignored");
        }
    }


    // -------------------------------------------------------------
    // Local message tracker (for Plugin origin)
    // -------------------------------------------------------------

    /**
     * Message tracker to prevent duplicate processing of locally created
     * Plugin messages (for example when they are looped back).
     */
    private static class MessageTracker {
        private final Set<String> processedIds = new HashSet<>();

        /**
         * Check if a message id was already processed.
         * The internal set is periodically cleared to avoid unbounded growth.
         */
        public synchronized boolean isProcessed(String id) {
            return processedIds.contains(id);
        }

        public synchronized void markProcessed(String id) {
            if (processedIds.size() > 100) {
                processedIds.clear();
            }
            processedIds.add(id);
        }
    }

    /**
     * Determine whether a given message should also be forwarded to LoRa.
     * Currently always true. Can be refined to exclude broadcast rooms etc.
     */
    private boolean shouldSendToLoRa(ChatMessageEntity message) {
        // Example of potential filter:
        // return !"All Chat Rooms".equals(message.getReceiverUid());
        Log.d(TAG, "Sending " + message.getId() + " to Flowgraph");
        return true;
    }


    // -------------------------------------------------------------
    // Incoming PHY messages integration
    // -------------------------------------------------------------

    /**
     * Process messages that originate from the physical layer (LoRa).
     *
     * Steps:
     *  - Deduplicate by message id
     *  - Tag origin as "PHY"
     *  - Rebuild raw CoT representation (placeholder)
     *  - Insert into database if not present
     *  - Convert to CoT and forward into GeoChat
     */
    public void processIncomingPhyMessage(ChatMessageEntity message) {
        if (message == null) return;

        Log.d(TAG, "Receive " + message.getId() + " from Flowgraph");

        if (processedMessageIds.contains(message.getId())) {
            Log.d(TAG, "Duplicate Flowgraph message ignored: " + message.getId());
            return;
        }

        processedMessageIds.add(message.getId());
        if (processedMessageIds.size() > 500) {
            // Simple safeguard against unbounded memory usage
            processedMessageIds.clear();
        }

        message.setOrigin("PHY");

        // Rebuild raw CoT representation if missing
        rebuildRawCot(message);

        boolean inserted = chatRepository.insertIfNotExists(message);

        if (inserted) {
            Log.d(TAG, "New PHY message saved: " + message.getMessage());
            // Convert to CoT and send to GeoChat
            Log.d(TAG, "TEST2-------------------------- ");
            CotEvent event = incomingPluginManager.convertChatMessageToCotEvent(message);

            if (event != null) {
                incomingPluginManager.sendToGeoChat(event);
            }
        } else {
            Log.d(TAG, "Duplicate PHY message ignored");
        }
    }

    /**
     * Rebuilds a placeholder raw CoT string if it is missing.
     * Currently this uses message.toString() as a stand in.
     */
    private void rebuildRawCot(ChatMessageEntity message) {
        if (message.getCotRawXml() != null && !message.getCotRawXml().isEmpty()) {
            return;
        }
        message.setCotRawXml(message.toString());
    }


    // -------------------------------------------------------------
    // Shutdown
    // -------------------------------------------------------------

    /**
     * Clear internal state and release resources.
     */
    public void shutdown() {
        processedMessageIds.clear();
        Log.d(TAG, "SyncSerivce cleaned up");
    }
}
