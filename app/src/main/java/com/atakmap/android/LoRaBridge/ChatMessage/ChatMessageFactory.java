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
     * Direction: GeoChat / CoT → Plugin DB
     *
     * Special handling:
     *  - If the event contains a "__plugin" detail with origin="Plugin"
     *    it is treated as a loopback of a message that the plugin
     *    already created, and is ignored (returns null).
     *  - If "__plugin.originalId" is present, it is used as the message id
     *    to support deduplication across LoRa hops.
     *
     * @param event  Incoming CoT event (GeoChat or other chat like message)
     * @param meta
     * @return A ChatMessageEntity, or null if parsing fails or should be skipped
     */
    public static ChatMessageEntity fromCotEvent(CotEvent event, Bundle meta) {
        if (event == null) return null;

        try {
            String myUid = MapView.getDeviceUid();  // 本机 UID

            CotDetail detail = event.getDetail();

            CotDetail chatNode = detail.getFirstChildByName(0, "__chat");
            CotDetail loraNode = detail.getFirstChildByName(0, "__plugin");


            // 检查 origin，忽略插件自己发送的回环消息
            // Determine origin
            if (loraNode != null && "Plugin".equals(loraNode.getAttribute("origin"))) {
                return null;
            }

            // ========== 关键修复：正确提取发送者和接收者 ==========

            // 1. 提取 CoT 中声明的发送者和接收者
            String cotSenderUid = chatNode.getAttribute("sender");
            String cotReceiverUid = chatNode.getAttribute("id");
            String senderCallsign = chatNode.getAttribute("senderCallsign");

            // 如果 sender 字段缺失，尝试从 link 获取
            if (cotSenderUid == null) {
                CotDetail linkNode = detail.getFirstChildByName(0, "link");
                if (linkNode != null) {
                    cotSenderUid = linkNode.getAttribute("uid");
                }
            }

            // 2. 判断消息方向
            boolean isOutgoing = cotSenderUid != null && cotSenderUid.equals(myUid);

            String finalSenderUid;
            String finalSenderCallsign;
            String finalReceiverUid;
            String finalReceiverCallsign;

            if (isOutgoing) {
                // 这是我发送的消息（可能是回环）
                finalSenderUid = myUid;
                finalSenderCallsign = senderCallsign != null
                        ? senderCallsign
                        : MapView.getMapView().getSelfMarker().getMetaString("callsign", myUid);

                // 接收者是对方
                finalReceiverUid = cotReceiverUid;
                finalReceiverCallsign = chatNode.getAttribute("chatroom");
            } else {
                // 这是对方发送给我的消息
                finalSenderUid = cotSenderUid;
                finalSenderCallsign = senderCallsign != null ? senderCallsign : cotSenderUid;

                // 接收者是我
                finalReceiverUid = myUid;
                finalReceiverCallsign = MapView.getMapView().getSelfMarker().getMetaString("callsign", myUid);

                if (meta.getString("receiverUid") != null) {
                    finalReceiverUid = meta.getString("receiverUid");
                }
            }
            // 尝试从 Contacts 解析接收者的真实 callsign
            com.atakmap.android.contact.Contact contact =
                    com.atakmap.android.contact.Contacts.getInstance()
                            .getContactByUuid(finalReceiverUid);
            if (contact != null && contact.getName() != null) {
                finalReceiverCallsign = contact.getName();
            }

            // 提取消息内容
            String message = null;
            CotDetail remarksNode = detail.getFirstChildByName(0, "remarks");
            if (remarksNode != null) {
                message = remarksNode.getInnerText();
            }

            // 提取消息 ID
            String messageId = chatNode.getAttribute("messageId");
            if (messageId == null) {
                if (loraNode != null) {
                    messageId = loraNode.getAttribute("originalId");
                }
            }
            if (messageId == null) {
                messageId = event.getUID();
            }

            // 提取时间戳
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
            sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            String timestamp = sdf.format(
                    event.getTime() != null
                            ? new Date(event.getTime().getMilliseconds())
                            : new Date()
            );

            // 提取消息类型
            String messageType = chatNode.getAttribute("messageType");
            if (messageType == null) {
                messageType = "text";
            }

            // 确定 origin
                String  origin = "GeoChat";
            if (loraNode != null) {
                String loraOrigin = loraNode.getAttribute("origin");
                if (loraOrigin != null) {
                    origin = loraOrigin;
                }
            }

            // 详细日志输出
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
