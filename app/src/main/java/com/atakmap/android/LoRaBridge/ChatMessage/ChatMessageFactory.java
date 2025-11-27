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
 * Factory for converting between Cursor on Target (CoT) events and the
 * plugin's database entities.
 *
 * Phase 1:
 *   - Bidirectional mapping between GeoChat messages and ChatMessageEntity
 *   - Used for receiving GeoChat and presenting it in the plugin chat UI
 *   - Used for creating new messages from user input
 *
 * Phase 2 (planned):
 *   - LoRa specific message identifiers and metadata
 */
public class ChatMessageFactory {

    /**
     * Create a new ChatMessageEntity from user input.
     *
     * Direction: Plugin UI → Database
     *
     * @param senderUid        UID of the sender device
     * @param senderCallsign   Callsign of the sender
     * @param receiverUid      UID of the receiver (for example a contact or room)
     * @param receiverCallsign Display name of the receiver
     * @param message          Text content of the message
     * @param messageType      Message type (for example "text", "alert")
     * @return A fully initialized ChatMessageEntity with random id and current UTC timestamp
     */
    public static ChatMessageEntity fromUserInput(
            String senderUid,
            String senderCallsign,
            String receiverUid,
            String receiverCallsign,
            String message,
            String messageType
    ) {
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
     * Direction: GeoChat / CoT → Plugin DB
     *
     * Special handling:
     *  - If the event contains a "__lora" detail with origin="Plugin"
     *    it is treated as a loopback of a message that the plugin
     *    already created, and is ignored (returns null).
     *  - If "__lora.originalId" is present, it is used as the message id
     *    to support deduplication across LoRa hops.
     *
     * @param event  Incoming CoT event (GeoChat or other chat like message)
     * @param toUIDs Optional array of target UIDs resolved by caller
     * @return A ChatMessageEntity, or null if parsing fails or should be skipped
     */
    public static ChatMessageEntity fromCotEvent(CotEvent event, String[] toUIDs) {
        if (event == null) return null;

        try {
            CotDetail detail = event.getDetail();

            String origin = null;
            CotDetail chatNode = detail.getFirstChildByName(0, "__chat");
            CotDetail loraNode = detail.getFirstChildByName(0, "__lora");

            // Determine origin
            if (loraNode != null) {
                origin = loraNode.getAttribute("origin");
                // Ignore messages that the plugin originally generated
                if ("Plugin".equals(origin)) {
                    return null;
                }
            } else {
                origin = "GeoChat";
            }

            String senderCallsign = null;
            String senderUid = null;
            String message = null;

            // Prefer __chat node if present
            if (chatNode != null) {
                senderCallsign = chatNode.getAttribute("senderCallsign");

                // sender UID: link.uid if available, otherwise senderCallsign
                CotDetail linkNode = detail.getFirstChildByName(0, "link");
                senderUid = (linkNode != null && linkNode.getAttribute("uid") != null)
                        ? linkNode.getAttribute("uid")
                        : chatNode.getAttribute("sender");
                message = chatNode.getAttribute("message");
            }

            // Fallback: use remarks text as message body
            if (message == null) {
                CotDetail remarksNode = detail.getFirstChildByName(0, "remarks");
                if (remarksNode != null) {
                    message = remarksNode.getInnerText();
                }
            }

            // Determine receiver UID
            String receiverUid;
            if (toUIDs != null && toUIDs.length > 0) {
                receiverUid = toUIDs[0];
            } else {
                // Fallback: extract "to" from remarks
                assert chatNode != null;
                CotDetail remarksNode = detail.getFirstChildByName(0, "remarks");
                receiverUid = remarksNode.getAttribute("to");
            }

            // Default receiver callsign to UID, then try to resolve via ATAK contacts
            String receiverCallsign = receiverUid;
            if (receiverUid != null) {
                com.atakmap.android.contact.Contact contact =
                        com.atakmap.android.contact.Contacts
                                .getInstance()
                                .getContactByUuid(receiverUid);
                if (contact != null && contact.getName() != null) {
                    receiverCallsign = contact.getName();
                }
            }

            // Extract original message id if present (__lora.originalId)
            String originalId = null;
            if (loraNode != null) {
                originalId = loraNode.getAttribute("originalId");
            }

            String messageId = (originalId != null)
                    ? originalId
                    : event.getUID();

            String messageType = (chatNode != null)
                    ? chatNode.getAttribute("messageType")
                    : "text";

            // Use event time if present, otherwise current time
            SimpleDateFormat sdf =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));

            String timestamp = sdf.format(
                    event.getTime() != null
                            ? new Date(event.getTime().getMilliseconds())
                            : new Date()
            );

            return new ChatMessageEntity(
                    messageId,
                    senderUid,
                    senderCallsign != null ? senderCallsign : senderUid,
                    receiverUid,
                    receiverCallsign,
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
     * Convert a Bundle (from GeoChat) into ChatMessageEntity
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
