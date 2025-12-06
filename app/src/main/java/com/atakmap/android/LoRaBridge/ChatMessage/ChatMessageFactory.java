package com.atakmap.android.LoRaBridge.ChatMessage;

import android.os.Bundle;
import android.util.Log;

import com.atakmap.android.LoRaBridge.Database.ChatMessageEntity;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotDetail;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.UUID;

/**
 * ChatMessageFactory
 *
 * Factory responsible for converting between Cursor-on-Target (CoT) chat events
 * and the plugin's internal ChatMessageEntity format.
 *
 * Purpose:
 *   Phase 1:
 *     • Provide bidirectional mapping between GeoChat messages and ChatMessageEntity
 *     • Used for displaying incoming GeoChat messages in the plugin UI
 *     • Used for creating new messages from user input inside the plugin
 *
 *   Phase 2 (planned):
 *     • Introduce LoRa-specific message identifiers and metadata extensions
 */
public class ChatMessageFactory {

    /**
     * Create a new ChatMessageEntity from user input inside the plugin.
     *
     * Direction: Plugin UI → Local Database
     *
     * @param senderUid        UID of the sender device
     * @param senderCallsign   Callsign of the sender
     * @param receiverUid      UID of the receiver (contact or chatroom)
     * @param receiverCallsign Display name of the receiver
     * @param message          The text content of the message
     * @param messageType      Message type such as "text" or "alert"
     * @return                 Fully populated ChatMessageEntity with a random UUID
     *                         and timestamp in UTC
     */
    public static ChatMessageEntity fromUserInput(
            String senderUid,
            String senderCallsign,
            String receiverUid,
            String receiverCallsign,
            String message,
            String messageType
    ) {
        senderUid = senderUid != null ? senderUid.trim() : null;
        senderCallsign = senderCallsign != null ? senderCallsign.trim() : null;
        receiverUid = receiverUid != null ? receiverUid.trim() : null;
        receiverCallsign = receiverCallsign != null ? receiverCallsign.trim() : null;
        message = message != null ? message.trim() : null;
        messageType = messageType != null ? messageType.trim() : "text";

        SimpleDateFormat sdf =
                new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

        String timestamp = sdf.format(new Date());
        String id = UUID.randomUUID().toString();

        return new ChatMessageEntity(
                id,
                senderUid,
                senderCallsign,
                receiverUid,
                receiverCallsign,
                message,
                timestamp,
                messageType,
                "Plugin",
                null
        );
    }

    /**
     * Convert a CoT event into a ChatMessageEntity.
     *
     * Direction: GeoChat / ATAK CoT → Plugin Database
     *
     * Behaviour:
     *   • If the event contains a "__plugin" detail with origin="Plugin",
     *     it is classified as a loopback message created by the plugin itself.
     *     Such messages are ignored for display (return null).
     *
     *   • If "__plugin.originalId" exists, it is used as the messageId
     *     to support deduplication when messages propagate through LoRa hops.
     *
     * @param event The CoT event to convert
     * @param meta  Optional metadata bundle (used by ATAK GeoChat)
     * @return      A ChatMessageEntity or null if the event should be skipped
     */
    public static ChatMessageEntity fromCotEvent(CotEvent event, Bundle meta) {
        if (event == null) return null;

        try {
            String myUid = MapView.getDeviceUid(); // local device UID

            CotDetail detail = event.getDetail();
            CotDetail chatNode = detail.getFirstChildByName(0, "__chat");
            CotDetail loraNode = detail.getFirstChildByName(0, "__plugin");

            // Ignore loopback messages originally created by the plugin
            if (loraNode != null && "Plugin".equals(loraNode.getAttribute("origin"))) {
                return null;
            }

            // ---------- Extract sender and receiver from CoT ----------
            String cotSenderUid = chatNode.getAttribute("sender");
            String cotReceiverUid = chatNode.getAttribute("id");
            String senderCallsign = chatNode.getAttribute("senderCallsign");

            // Fallback extraction from <link> if sender is missing
            if (cotSenderUid == null) {
                CotDetail linkNode = detail.getFirstChildByName(0, "link");
                if (linkNode != null) {
                    cotSenderUid = linkNode.getAttribute("uid");
                }
            }

            // Determine direction: outgoing if I am the sender
            boolean isOutgoing =
                    cotSenderUid != null && cotSenderUid.equals(myUid);

            String finalSenderUid;
            String finalSenderCallsign;
            String finalReceiverUid;
            String finalReceiverCallsign;

            if (isOutgoing) {
                // I sent this message
                finalSenderUid = myUid;
                finalSenderCallsign = senderCallsign != null
                        ? senderCallsign
                        : MapView.getMapView().getSelfMarker().getMetaString("callsign", myUid);

                finalReceiverUid = cotReceiverUid;
                finalReceiverCallsign = chatNode.getAttribute("chatroom");

            } else {
                // Received from someone else
                finalSenderUid = cotSenderUid;
                finalSenderCallsign = senderCallsign != null ? senderCallsign : cotSenderUid;

                finalReceiverUid = myUid;
                finalReceiverCallsign =
                        MapView.getMapView().getSelfMarker().getMetaString("callsign", myUid);

                // GeoChat sometimes sends explicit override for receiver
                if (meta.getString("receiverUid") != null) {
                    finalReceiverUid = meta.getString("receiverUid");
                }
            }

            // Try resolving receiver callsign through ATAK Contacts
            com.atakmap.android.contact.Contact contact =
                    com.atakmap.android.contact.Contacts.getInstance()
                            .getContactByUuid(finalReceiverUid);
            if (contact != null && contact.getName() != null) {
                finalReceiverCallsign = contact.getName();
            }

            // Extract message text from <remarks>
            String message = null;
            CotDetail remarksNode = detail.getFirstChildByName(0, "remarks");
            if (remarksNode != null) {
                message = remarksNode.getInnerText();
            }

            // Extract message ID
            String messageId = chatNode.getAttribute("messageId");
            if (messageId == null && loraNode != null) {
                messageId = loraNode.getAttribute("originalId");
            }
            if (messageId == null) {
                messageId = event.getUID();
            }

            // Format timestamp in UTC
            SimpleDateFormat sdf =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String timestamp = sdf.format(
                    event.getTime() != null
                            ? new Date(event.getTime().getMilliseconds())
                            : new Date()
            );

            // Determine message type
            String messageType = chatNode.getAttribute("messageType");
            if (messageType == null) messageType = "text";

            // Determine origin
            String origin = "GeoChat";
            if (loraNode != null) {
                String loraOrigin = loraNode.getAttribute("origin");
                if (loraOrigin != null) origin = loraOrigin;
            }

            // Debug logging
            Log.d("ChatMessageFactory", "========== fromCotEvent ==========");
            Log.d("ChatMessageFactory", "My UID: " + myUid);
            Log.d("ChatMessageFactory", "CoT sender: " + cotSenderUid);
            Log.d("ChatMessageFactory", "CoT receiver: " + cotReceiverUid);
            Log.d("ChatMessageFactory", "Is outgoing: " + isOutgoing);
            Log.d("ChatMessageFactory", "Final sender: " + finalSenderUid + " (" + finalSenderCallsign + ")");
            Log.d("ChatMessageFactory", "Final receiver: " + finalReceiverUid + " (" + finalReceiverCallsign + ")");
            Log.d("ChatMessageFactory", "Message: " + message);
            Log.d("ChatMessageFactory", "===================================");

            return new ChatMessageEntity(
                    messageId,
                    finalSenderUid,
                    finalSenderCallsign,
                    finalReceiverUid,
                    finalReceiverCallsign,
                    message,
                    timestamp,
                    messageType,
                    origin,
                    event.toString()
            );

        } catch (Exception e) {
            Log.e("ChatMessageFactory", "Error parsing CoT event", e);
            return null;
        }
    }

    /**
     * Convert a GeoChat Bundle into ChatMessageEntity.
     *
     * Used when GeoChat delivers chat updates via an Android Bundle rather
     * than a raw CoT event.
     *
     * @param bundle The incoming GeoChat message bundle
     * @return       Converted ChatMessageEntity or null on failure
     */
    public static ChatMessageEntity fromBundle(Bundle bundle) {
        try {
            String id = bundle.getString("messageId");
            String senderUid = bundle.getString("senderUid");
            String senderCallsign = bundle.getString("senderCallsign");
            String receiverUid = bundle.getString("conversationId");
            String receiverCallsign = bundle.getString("conversationName");
            String message = bundle.getString("message");
            long sentTime = bundle.getLong("sentTime", System.currentTimeMillis());

            SimpleDateFormat sdf = new SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
                    Locale.US
            );
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String timestamp = sdf.format(new Date(sentTime));

            String origin = "GeoChat";
            if (senderUid != null && senderUid.equals(MapView.getDeviceUid())) {
                origin = "Plugin";
            }

            return new ChatMessageEntity(
                    id != null ? id : UUID.randomUUID().toString(),
                    senderUid,
                    senderCallsign,
                    receiverUid,
                    receiverCallsign,
                    message,
                    timestamp,
                    "text",
                    origin,
                    null
            );
        } catch (Exception e) {
            Log.e("ChatMessageFactory", "Error converting Bundle", e);
            return null;
        }
    }
}
