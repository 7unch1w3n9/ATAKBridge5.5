package com.atakmap.android.LoRaBridge.ChatMessage;

import android.os.Bundle;
import android.util.Log;

import com.atakmap.android.LoRaBridge.Database.ChatMessageEntity;
import com.atakmap.android.chat.GeoChatConnector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.GroupContact;
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
     * @param event CoT event that should be dispatched through the ATAK CoT pipeline
     */
    public void sendToGeoChat(CotEvent event, Bundle extras) {
        try {
            CotDetail chat = event.getDetail().getFirstChildByName(0, "__chat");
            if (chat == null) {
                Log.w(TAG, "No __chat detail found");
                return;
            }

            String receiverUid = chat.getAttribute("id");
            String receiverCallsign = chat.getAttribute("chatroom");

            if (receiverUid == null || receiverUid.isEmpty()) {
                Log.w(TAG, "No receiver UID found");
                return;
            }

            Contacts contacts = Contacts.getInstance();
            Contact existingContact = contacts.getContactByUuid(receiverUid);

            if (existingContact == null) {
                if (receiverCallsign == null || receiverCallsign.isEmpty()) {
                    receiverCallsign = receiverUid;
                }

                IndividualContact newContact = new IndividualContact(
                        receiverCallsign,
                        receiverUid
                );

                // 直接用 GeoChatConnector!
                NetConnectString ncs = new NetConnectString(
                        "udp",                    // protocol
                        "224.10.10.1",           // 假地址,系统能接受
                        17012                     // 端口
                );
                GeoChatConnector geochatConnector = new GeoChatConnector(ncs);
                newContact.addConnector(geochatConnector);

                GroupContact root = contacts.getRootGroup();
                contacts.addContact(root, newContact);

                Log.d(TAG, "Created new contact with GeoChatConnector: " +
                        receiverCallsign + " (" + receiverUid + ")");
            } else if (existingContact instanceof IndividualContact) {
                IndividualContact ic = (IndividualContact) existingContact;

                if (ic.getConnector("connector.geochat") == null) {
                    NetConnectString ncs = new NetConnectString(
                            "udp",
                            "224.10.10.1",
                            17012
                    );
                    GeoChatConnector geochatConnector = new GeoChatConnector(ncs);
                    ic.addConnector(geochatConnector);
                    Log.d(TAG, "Added GeoChatConnector to existing contact: " + receiverUid);
                }
            }

            CotMapComponent.getInternalDispatcher().dispatch(event, extras);
            Log.d(TAG, "Message dispatched to GeoChat");

        } catch (Exception e) {
            Log.e(TAG, "Error in sendToGeoChat", e);
        }
    }

    /**
     * Convert a ChatMessageEntity into a GeoChat compatible CoT event.
     *
     * Mapping summary:
     *  - event.uid          = "PluginMsg.<senderCallsign>.<receiverUid>.<messageId>"
     *  - event.type         = "b-t-f" (generic text message)
     *  - event.how          = "h-g-i-g-o"
     *  - event.time/start   = now
     *  - event.stale        = now + 5 minutes
     *  - point              = current own position from MapView self marker
     *
     * Detail children:
     *  - <__chat>
     *      id               = receiver UID
     *      chatroom         = receiver callsign
     *      messageId        = message id
     *      senderCallsign   = sender callsign
     *  - <chatgrp>
     *      uid0             = sender UID
     *      uid1             = receiver UID
     *      id               = receiver UID
     *  - <__lora>
     *      originalId       = message id
     *      origin           = "Plugin"
     *  - <link>
     *      uid              = sender UID
     *  - <remarks>
     *      to               = receiver UID
     *      time             = current CoordinatedTime string
     *      inner text       = message content
     *
     * @param message ChatMessageEntity to convert
     * @return Fully populated CoT event representing this message
     */
    public CotEvent convertChatMessageToCotEvent(ChatMessageEntity message) {

        CotEvent event = new CotEvent();
        CoordinatedTime now = new CoordinatedTime();

        // CoT UID: includes sender callsign, receiver UID and message id
        event.setUID("PluginMsg." + message.getSenderUid()
                + "." + message.getReceiverUid()
                + "." + message.getId());
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
        chat.setAttribute("messageId", message.getId());
        chat.setAttribute("chatroom", message.getReceiverCallsign());
        chat.setAttribute("id", message.getReceiverUid());
        chat.setAttribute("senderCallsign", message.getSenderCallsign());
        chat.setAttribute("sender", message.getSenderUid());
        detail.addChild(chat);

        // chatgrp: sender and receiver UIDs
        CotDetail chatgrp = new CotDetail("chatgrp");
        chatgrp.setAttribute("uid0", message.getSenderUid());   // sender UID
        chatgrp.setAttribute("uid1", message.getReceiverUid()); // receiver UID
        chatgrp.setAttribute("id", message.getReceiverUid());   // kept consistent with __chat@id
        chat.addChild(chatgrp);

        // __lora: plugin specific metadata for deduplication and origin tracking
        CotDetail loraDetail = new CotDetail("__lora");
        loraDetail.setAttribute("originalId", message.getId());
        loraDetail.setAttribute("origin", "Plugin");
        detail.addChild(loraDetail);

        // link: link between CoT entity and sender UID
        CotDetail link = new CotDetail("link");
        link.setAttribute("uid", message.getSenderUid());
        link.setAttribute("type", "a-f-G-U-C");
        link.setAttribute("relation", "p-p");
        detail.addChild(link);

        // remarks: actual text content and meta fields
        CotDetail remarks = new CotDetail("remarks");
        remarks.setAttribute("source", "BAO.F.ATAK." + message.getSenderUid());
        remarks.setAttribute("to", message.getReceiverUid());
        remarks.setAttribute("time", now.toString());
        remarks.setInnerText(message.getMessage());
        detail.addChild(remarks);

        event.setDetail(detail);

        // Debug output
        com.atakmap.coremap.log.Log.d("LoRaBridge", "Convert message to CoT Event:");
        com.atakmap.coremap.log.Log.d("LoRaBridge", "  UID: " + event.getUID());
        com.atakmap.coremap.log.Log.d("LoRaBridge", "  Type: " + event.getType());
        com.atakmap.coremap.log.Log.d("LoRaBridge", "  Sender: " +
                event.getDetail().getFirstChildByName(0, "__chat").getAttribute("sender"));
        com.atakmap.coremap.log.Log.d("LoRaBridge", "  Message: " + message.getMessage());
        com.atakmap.coremap.log.Log.d("LoRaBridge", "  Detail XML: " + detail);

        return event;
    }
}
