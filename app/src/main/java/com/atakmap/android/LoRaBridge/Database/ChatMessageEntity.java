package com.atakmap.android.LoRaBridge.Database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * ChatMessageEntity
 *
 * Room database model representing a single chat message inside the plugin.
 *
 * Each entry may originate from different sources:
 *   - "Plugin" : Local UI generated messages
 *   - "GeoChat" : Messages received from ATAK's CoT chat pipeline
 *   - "PHY" : Messages decoded from the LoRa physical layer
 *   - "LoRaOut" : Internal temporary marker before LoRa transmission
 *
 * Fields:
 *   messageId           Stable unique identifier used for deduplication
 *   senderUid           ATAK UID of the sender device
 *   senderCallsign      Readable callsign used in chat UI
 *   receiverUid         ATAK UID of the message recipient
 *   receiverCallsign    Callsign or display name of recipient
 *   message             Plain text message body
 *   sentTime            Timestamp in ISO8601 or millisecond format
 *   messageType         Future expansion (text, alert, file, etc.)
 *   origin              Source category used to avoid sync loops
 *   cotRawXml           Raw CoT XML when available (GeoChat integration)
 */
@Entity(tableName = "chat_messages")
public class ChatMessageEntity {

    /** Primary key used for deduplication across plugins, CoT, and LoRa */
    @PrimaryKey @NonNull
    public String messageId;

    /** UID of the sender device */
    public String senderUid;

    /** Callsign of the sender device */
    public String senderCallsign;

    /** UID of the receiver */
    public String receiverUid;

    /** Display name or callsign of the receiver */
    public String receiverCallsign;

    /** Actual text content of the message */
    public String message;

    /** Timestamp of message creation, either ISO8601 or milliseconds */
    public String sentTime;

    /** Message category; defaults to "text" in the current implementation */
    public String messageType;

    /** Message origin: Plugin, GeoChat, PHY, LoRaOut, etc. */
    public String origin;

    /**
     * Raw CoT XML extracted from incoming ATAK messages.
     * For LoRa or Plugin generated messages this field may be null.
     */
    public String cotRawXml;

    /**
     * Complete constructor used throughout the plugin.
     */
    public ChatMessageEntity(
            @NonNull String messageId,
            String senderUid,
            String senderCallsign,
            String receiverUid,
            String receiverCallsign,
            String message,
            String sentTime,
            String messageType,
            String origin,
            String cotRawXml) {

        this.messageId = messageId;
        this.senderUid = senderUid;
        this.senderCallsign = senderCallsign;
        this.receiverUid = receiverUid;
        this.receiverCallsign = receiverCallsign;
        this.message = message;
        this.sentTime = sentTime;
        this.messageType = messageType;
        this.origin = origin;
        this.cotRawXml = cotRawXml;
    }

    // ----------- Getters / Setters -----------

    public String getId() { return messageId; }
    public void setId(String messageId) { this.messageId = messageId; }

    public String getSenderUid() { return senderUid; }
    public void setSenderUid(String senderUid) { this.senderUid = senderUid; }

    public String getSenderCallsign() { return senderCallsign; }
    public void setSenderCallsign(String senderCallsign) { this.senderCallsign = senderCallsign; }

    public String getReceiverUid() { return receiverUid; }
    public void setReceiverUid(String receiverUid) { this.receiverUid = receiverUid; }

    public String getReceiverCallsign() { return receiverCallsign; }
    public void setReceiverCallsign(String receiverCallsign) { this.receiverCallsign = receiverCallsign; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }

    public String getSentTime() { return sentTime; }
    public void setSentTime(String sentTime) { this.sentTime = sentTime; }

    public String getOrigin() { return origin; }
    public void setOrigin(String origin) { this.origin = origin; }

    public String getCotRawXml() { return cotRawXml; }
    public void setCotRawXml(String rawXml) { this.cotRawXml = rawXml; }

    /**
     * Debug-friendly string representation.
     * Useful when logging LoRa decoded messages or CoT conversions.
     */
    @NonNull
    public String toString() {
        return "ChatMessageEntity{" +
                "messageId='" + messageId + '\'' +
                ", senderUid='" + senderUid + '\'' +
                ", senderCallsign='" + senderCallsign + '\'' +
                ", receiverUid='" + receiverUid + '\'' +
                ", receiverName='" + receiverCallsign + '\'' +
                ", message='" + message + '\'' +
                ", timestamp='" + sentTime + '\'' +
                ", messageType='" + messageType + '\'' +
                ", origin='" + origin + '\'' +
                ", cotRawXml.length=" + (cotRawXml != null ? cotRawXml.length() : 0) +
                '}';
    }
}
