package com.atakmap.android.LoRaBridge.ChatMessage;

import android.os.Bundle;
import android.util.Log;

import com.atakmap.android.LoRaBridge.Database.ChatMessageEntity;
import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.NetConnectString;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.cot.event.CotPoint;
import com.atakmap.coremap.maps.conversion.EGM96;
import com.atakmap.coremap.maps.coords.GeoPoint;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.util.Objects;

/**
 * IncomingPluginManager
 *
 * Manages conversion and transmission of messages originating from the plugin.
 *
 * Responsibilities:
 *  - Convert ChatMessageEntity instances into CoT events compatible with GeoChat
 *  - Dispatch converted CoT messages into ATAK via CotMapComponent
 *
 * Direction:
 *  Plugin message -> CoT event -> GeoChat / ATAK network
 *
 * Future work:
 *  - Add LoRa specific encoding or compression on top of the CoT representation
 */
public class IncomingPluginManager {

    private static final String TAG = "IncomingPluginManager";

    public IncomingPluginManager() {
    }

    /**
     * Entry point for sending a CoT event into GeoChat.
     * This method assumes that the event is already fully constructed.
     *
     * Strategy:
     *  - For incoming messages (where I am the receiver), create the sender's contact
     *  - For outgoing messages (where I am the sender), assume receiver contact already exists
     *  - Dispatch the event through ATAK's internal CoT pipeline
     *
     * @param event CoT event that should be dispatched through the ATAK CoT pipeline
     *
     */
    public void sendToGeoChat(CotEvent event) {
        try {
            String myUid = MapView.getDeviceUid().trim();

            CotDetail chat = event.getDetail().getFirstChildByName(0, "__chat");
            if (chat == null) {
                Log.w(TAG, "No __chat detail found in CoT event");
                return;
            }

            // Trim all UIDs to avoid whitespace issues
            String senderUid = chat.getAttribute("sender");
            String senderCallsign = chat.getAttribute("senderCallsign");
            String receiverUid = chat.getAttribute("id");

            if (senderUid != null) senderUid = senderUid.trim();
            if (receiverUid != null) receiverUid = receiverUid.trim();
            if (senderCallsign != null) senderCallsign = senderCallsign.trim();

            Log.d(TAG, "========== sendToGeoChat ==========");
            Log.d(TAG, "Sender UID: '" + senderUid + "' (len=" + (senderUid != null ? senderUid.length() : 0) + ")");
            Log.d(TAG, "Receiver UID: '" + receiverUid + "' (len=" + (receiverUid != null ? receiverUid.length() : 0) + ")");
            Log.d(TAG, "My UID: '" + myUid + "' (len=" + myUid.length() + ")");

            // Determine message direction
            boolean isIncoming;

            /* Case 1: Normal incoming (receiver is me) */
            if (receiverUid != null && receiverUid.equals(myUid)) {
                isIncoming = true;
            }

            /* Case 2: External test device (e.g., HeltecV3)
             Used when the message is not addressed to me, but clearly comes from the LoRa device */
            else if (senderUid != null && senderUid.equalsIgnoreCase("HeltecV3")) {
                Log.d(TAG, "Detected HeltecV3 as sender -> treat as INCOMING test message");
                isIncoming = true;
            }

            /* Case 3: Outgoing (normal ATAK behavior) */
            else {
                isIncoming = false;
            }

            if (isIncoming) {
                ensureContactExists(senderUid, senderCallsign);
                Log.d(TAG, "✓ INCOMING message from: " + senderCallsign + " (" + senderUid + ")");
            } else {
                String chatroom = chat.getAttribute("chatroom");
                Log.d(TAG, "✓ OUTGOING message to: " + chatroom + " (" + receiverUid + ")");
            }

            // Dispatch to ATAK's CoT system
            CotMapComponent.getInternalDispatcher().dispatch(event);
            Log.d(TAG, "✓ Message dispatched to GeoChat");
            Log.d(TAG, "===================================");

        } catch (Exception e) {
            Log.e(TAG, "Error in sendToGeoChat", e);
            e.printStackTrace();
        }
    }

    /**
     * Ensure a contact exists in ATAK's contact list.
     *
     * Strategy:
     *  1. First try to find by UID
     *  2. If not found by UID, try to find by callsign (avoid duplicates)
     *  3. If found by callsign but UID mismatch, use existing contact
     *  4. If not found at all, create new contact
     *  5. Always ensure GeoChatConnector is present
     *
     * Note: Contact is created without MapItem (chat-only functionality).
     *       If you need the contact to appear on the map, you'll need to
     *       send position CoT events separately.
     *
     * @param uid Contact's unique identifier
     * @param callsign Contact's display name
     */
    private void ensureContactExists(String uid, String callsign) {
        if (uid == null || uid.isEmpty()) {
            Log.w(TAG, "⚠ Cannot create contact: UID is null or empty");
            return;
        }

        // Trim inputs
        uid = uid.trim();
        if (callsign != null) callsign = callsign.trim();

        Log.d(TAG, "========== ensureContactExists ==========");
        Log.d(TAG, "Requested UID: '" + uid + "'");
        Log.d(TAG, "Requested Callsign: '" + callsign + "'");

        Contacts contacts = Contacts.getInstance();
        Contact existing = null;
        boolean foundByCallsign = false;

        // Step 1: Try to find by UID
        existing = contacts.getContactByUuid(uid);

        // Step 2: If not found by UID, try to find by callsign
        if (existing == null && callsign != null && !callsign.isEmpty()) {
            Log.d(TAG, "Contact not found by UID, trying callsign...");
            existing = contacts.getFirstContactWithCallsign(callsign);

            if (existing != null) {
                foundByCallsign = true;
                Log.w(TAG, "⚠ Found contact by callsign but UID mismatch!");
                Log.w(TAG, "  Requested UID: " + uid);
                Log.w(TAG, "  Existing UID: " + existing.getUID());
                Log.w(TAG, "  → Using existing contact to avoid duplicates");
            }
        }

        // Step 3: If contact found (either by UID or callsign)
        if (existing != null) {
            if (existing instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) existing;

                Log.d(TAG, "✓ Contact already exists:");
                Log.d(TAG, "  Name: " + ic.getName());
                Log.d(TAG, "  UID: " + ic.getUID());

                // Ensure GeoChatConnector is present
                if (ic.getConnector("connector.geochat") == null) {
                    NetConnectString ncs = new NetConnectString("udp", "224.10.10.1", 17012);
                    ic.addConnector(new GeoChatConnector(ncs));
                    Log.d(TAG, "✓ Added GeoChatConnector to existing contact");
                } else {
                    Log.d(TAG, "✓ GeoChatConnector already exists");
                }
            } else {
                Log.w(TAG, "⚠ Contact exists but is not IndividualContact: " + existing.getClass().getName());
            }

            Log.d(TAG, "=========================================");
            return;
        }

        // Step 4: Contact not found - create new one
        Log.d(TAG, "Contact does not exist, creating new one...");

        if (callsign == null || callsign.isEmpty()) {
            callsign = uid;
        }

        // Create contact without MapItem (sufficient for chat functionality)
        IndividualContact newContact = new IndividualContact(callsign, uid);

        // Add GeoChatConnector
        NetConnectString ncs = new NetConnectString("udp", "224.10.10.1", 17012);
        newContact.addConnector(new GeoChatConnector(ncs));

        // Add to root contact group
        contacts.addContact(contacts.getRootGroup(), newContact);

        Log.d(TAG, "✓ Created new contact:");
        Log.d(TAG, "  Name: " + callsign);
        Log.d(TAG, "  UID: " + uid);
        Log.d(TAG, "  Note: Contact is created without map marker (chat-only)");
        Log.d(TAG, "=========================================");
    }

    /**
     * Convert a ChatMessageEntity into a GeoChat compatible CoT event.
     *
     * Mapping summary:
     *  - event.uid          = "GeoChat.<senderUid>.<receiverUid>.<messageId>"
     *  - event.type         = "b-t-f" (generic text message)
     *  - event.how          = "h-g-i-g-o"
     *  - event.time/start   = now
     *  - event.stale        = now + 5 minutes
     *  - point              = current own position from MapView self marker
     *
     * Detail children:
     *  - <__chat>
     *      sender           = sender UID
     *      id               = receiver UID
     *      chatroom         = conversation partner's callsign (changes based on direction)
     *      messageId        = message id
     *      senderCallsign   = sender callsign
     *  - <chatgrp>
     *      uid0             = sender UID
     *      uid1             = receiver UID
     *      id               = receiver UID
     *  - <__plugin>
     *      originalId       = message id
     *      origin           = "Plugin"
     *  - <link>
     *      uid              = sender UID
     *  - <remarks>
     *      to               = receiver UID
     *      time             = current CoordinatedTime string
     *      inner text       = message content
     *
     * Note on chatroom field:
     *  - If outgoing (I sent it): chatroom = receiver's callsign
     *  - If incoming (I received it): chatroom = sender's callsign
     *  - This ensures chatroom always points to the conversation partner
     *
     * @param message ChatMessageEntity to convert
     * @return Fully populated CoT event representing this message
     */
    public CotEvent convertChatMessageToCotEvent(ChatMessageEntity message) {
        CotEvent event = new CotEvent();
        CoordinatedTime now = new CoordinatedTime();

        String myUid = MapView.getDeviceUid().trim();

        // Trim all fields from the message entity
        String senderUid = message.getSenderUid();
        String senderCallsign = message.getSenderCallsign();
        String receiverUid = message.getReceiverUid();
        String receiverCallsign = message.getReceiverCallsign();
        String messageText = message.getMessage();
        String messageId = message.getId();

        if (senderUid != null) senderUid = senderUid.trim();
        if (senderCallsign != null) senderCallsign = senderCallsign.trim();
        if (receiverUid != null) receiverUid = receiverUid.trim();
        if (receiverCallsign != null) receiverCallsign = receiverCallsign.trim();
        if (messageText != null) messageText = messageText.trim();
        if (messageId != null) messageId = messageId.trim();

        // Determine message direction
        boolean isOutgoing = senderUid.equals(myUid);

        // chatroom = conversation partner's callsign (not myself)
        String conversationPartner = isOutgoing
                ? receiverCallsign  // I sent it → partner is receiver
                : senderCallsign;    // I received it → partner is sender

        // Event metadata
        event.setUID("GeoChat." + senderUid + "." + receiverUid + "." + messageId);
        event.setType("b-t-f");
        event.setHow("h-g-i-g-o");
        event.setTime(now);
        event.setStart(now);
        event.setStale(now.addMinutes(5));

        // Point: use device self marker location from the current map
        GeoPoint gp = MapView.getMapView().getSelfMarker().getPoint();
        double hae = gp.isAltitudeValid() ? EGM96.getHAE(gp) : 0.0;
        double ce = (Double.isNaN(gp.getCE()) || gp.getCE() == CotPoint.UNKNOWN) ? 10.0 : gp.getCE();
        double le = (Double.isNaN(gp.getLE()) || gp.getLE() == CotPoint.UNKNOWN) ? 10.0 : gp.getLE();
        CotPoint point = new CotPoint(gp.getLatitude(), gp.getLongitude(), hae, ce, le);
        event.setPoint(point);

        // Root <detail> node
        CotDetail detail = new CotDetail("detail");

        // __chat: high level chat metadata
        CotDetail chat = new CotDetail("__chat");
        chat.setAttribute("parent", "RootContactGroup");
        chat.setAttribute("groupOwner", "false");
        chat.setAttribute("sender", senderUid);
        chat.setAttribute("messageId", messageId);
        chat.setAttribute("chatroom", conversationPartner);
        chat.setAttribute("id", receiverUid);
        chat.setAttribute("senderCallsign", senderCallsign);
        detail.addChild(chat);

        // chatgrp: sender and receiver UIDs
        CotDetail chatgrp = new CotDetail("chatgrp");
        chatgrp.setAttribute("uid0", senderUid);
        chatgrp.setAttribute("uid1", receiverUid);
        chatgrp.setAttribute("id", receiverUid);
        chat.addChild(chatgrp);

        // __lora: plugin specific metadata for deduplication and origin tracking
        CotDetail loraDetail = new CotDetail("__plugin");
        loraDetail.setAttribute("originalId", messageId);
        loraDetail.setAttribute("origin", "Plugin");
        detail.addChild(loraDetail);

        // link: link between CoT entity and sender UID
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", senderUid);
        link.setAttribute("type", "a-f-G-U-C");
        link.setAttribute("relation", "p-p");
        detail.addChild(link);

        // remarks: actual text content and meta fields
        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "BAO.F.ATAK." + senderUid);
        remarks.setAttribute("to", receiverUid);
        remarks.setAttribute("time", now.toString());
        remarks.setInnerText(messageText);
        detail.addChild(remarks);

        event.setDetail(detail);

        // Debug output
        Log.d(TAG, "========== CoT Conversion ==========");
        Log.d(TAG, "Direction: " + (isOutgoing ? "OUTGOING" : "INCOMING"));
        Log.d(TAG, "Sender: '" + senderCallsign + "' ('" + senderUid + "')");
        Log.d(TAG, "Receiver: '" + receiverCallsign + "' ('" + receiverUid + "')");
        Log.d(TAG, "Chatroom (conversation partner): '" + conversationPartner + "'");
        Log.d(TAG, "Message: '" + messageText + "'");
        Log.d(TAG, "====================================");

        return event;
    }
}
