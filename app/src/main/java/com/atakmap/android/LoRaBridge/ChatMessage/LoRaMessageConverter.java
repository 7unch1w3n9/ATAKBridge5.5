package com.atakmap.android.LoRaBridge.ChatMessage;

import com.atakmap.android.LoRaBridge.Database.ChatMessageEntity;
import com.atakmap.android.LoRaBridge.phy.MessageConverter;
import com.atakmap.coremap.log.Log;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * LoRaMessageConverter
 *
 * Implements a lightweight custom encoding scheme for transporting
 * ChatMessageEntity objects over limited-bandwidth LoRa links.
 *
 * Characteristics:
 *   - Compact pipe-delimited text format: field|field|field...
 *   - Escaping rules ensure delimiter characters can appear inside fields
 *   - Timestamp flexibility: supports epoch milliseconds or ISO-8601
 *   - Bidirectional conversion: ChatMessageEntity ↔ byte[]
 *
 * Encoding format (9 fields):
 *   0: id
 *   1: senderUid
 *   2: senderCallsign
 *   3: receiverUid
 *   4: receiverCallsign
 *   5: messageBody
 *   6: timestamp (epoch ms or ISO)
 *   7: messageType
 *   8: origin
 *
 * The design keeps this message format fully independent from ATAK/CoT,
 * which makes it easy to replace the underlying radio transport.
 */
public class LoRaMessageConverter implements MessageConverter {

    private static final String TAG = "LoRaMessageConverter";

    /** Field separator for LoRa message format */
    private static final String FIELD_DELIMITER = "|";

    /** Escape prefix to allow literal | and \ in fields */
    private static final String FIELD_ESCAPE = "\\";

    /** ISO-8601 timestamp format (UTC) */
    private static final String TIMESTAMP_FORMAT = "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'";


    // -------------------------------------------------------------
    // Encode: ChatMessageEntity → byte[]
    // -------------------------------------------------------------

    @Override
    public byte[] encode(ChatMessageEntity message) {
        try {
            // Create lightweight version of the message
            ChatMessageEntity lite = createLiteMessage(message);

            // Escape fields to avoid delimiter conflicts
            String escapedMessage = escapeField(lite.getMessage());

            // Build delimited payload
            String formatted = String.join(FIELD_DELIMITER,
                    lite.getId(),
                    lite.getSenderUid(),
                    escapeField(lite.getSenderCallsign()),
                    lite.getReceiverUid(),
                    escapeField(lite.getReceiverCallsign()),
                    escapedMessage,
                    formatTimestamp(lite.getSentTime()),
                    "text",                              // messageType
                    lite.getOrigin() != null ? lite.getOrigin() : ""
            );

            return formatted.getBytes(StandardCharsets.UTF_8);

        } catch (Exception e) {
            Log.e(TAG, "Encoding failed for message: " + message.getId(), e);
            return new byte[0];
        }
    }


    // -------------------------------------------------------------
    // Decode: byte[] → ChatMessageEntity
    // -------------------------------------------------------------

    @Override
    public ChatMessageEntity decode(byte[] payload) {
        try {
            String raw = new String(payload, StandardCharsets.UTF_8);

            // Split while keeping escape semantics
            String[] parts = splitPreservingEscapes(raw);

            if (parts.length < 9) {
                throw new IllegalArgumentException(
                        "Invalid LoRa format. Parts=" + parts.length
                );
            }

            // Extract fields
            String id               = unescapeField(parts[0]);
            String senderUid        = unescapeField(parts[1]);
            String senderCallsign   = unescapeField(parts[2]);
            String receiverUid      = unescapeField(parts[3]);
            String receiverCallsign = unescapeField(parts[4]);
            String messageBody      = unescapeField(parts[5]);
            String tsStr            = unescapeField(parts[6]);
            String msgType          = unescapeField(parts[7]);
            String origin           = unescapeField(parts[8]);

            // Normalize timestamp to ISO UTC
            String isoTimestamp = parseTimestamp(tsStr);

            return new ChatMessageEntity(
                    id,
                    senderUid,
                    senderCallsign,
                    receiverUid,
                    receiverCallsign,
                    messageBody,
                    isoTimestamp,
                    msgType,
                    origin,
                    null  // no raw CoT
            );

        } catch (Exception e) {
            Log.e(TAG,
                    "Decoding failed for payload: " +
                            new String(payload, StandardCharsets.UTF_8),
                    e
            );
            return null;
        }
    }


    // -------------------------------------------------------------
    // Lightweight message creator (keeps only necessary fields)
    // -------------------------------------------------------------

    private ChatMessageEntity createLiteMessage(ChatMessageEntity original) {
        return new ChatMessageEntity(
                original.getId(),
                original.getSenderUid(),
                original.getSenderCallsign(),
                original.getReceiverUid(),
                original.getReceiverCallsign(),
                original.getMessage(),
                original.getSentTime(),
                "text",
                "LoRaOut",      // marks this as LoRa-originating
                null            // clear raw CoT
        );
    }


    // -------------------------------------------------------------
    // Escape / Unescape
    // -------------------------------------------------------------

    /** Escape delimiter and escape characters */
    private String escapeField(String field) {
        if (field == null) return "";
        return field.replace(FIELD_ESCAPE, FIELD_ESCAPE + FIELD_ESCAPE)
                .replace(FIELD_DELIMITER, FIELD_ESCAPE + FIELD_DELIMITER);
    }

    /** Undo escape rules */
    private String unescapeField(String field) {
        if (field == null) return "";
        return field.replace(FIELD_ESCAPE + FIELD_DELIMITER, FIELD_DELIMITER)
                .replace(FIELD_ESCAPE + FIELD_ESCAPE, FIELD_ESCAPE);
    }


    // -------------------------------------------------------------
    // Timestamp formatting/parsing
    // -------------------------------------------------------------

    /** Ensures timestamp is usable; fallback to current time */
    private String formatTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return String.valueOf(System.currentTimeMillis());
        }
        return timestamp;
    }

    /** Normalize various timestamp formats to ISO-8601 UTC */
    private String parseTimestamp(String timestampStr) {

        try {
            SimpleDateFormat out =
                    new SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US);
            out.setTimeZone(TimeZone.getTimeZone("UTC"));

            if (timestampStr == null || timestampStr.isEmpty()) {
                return out.format(new Date());
            }

            // case 1: raw milliseconds
            if (timestampStr.matches("\\d+")) {
                long ms = Long.parseLong(timestampStr);
                return out.format(new Date(ms));
            }

            // case 2: ISO with Z or +hh:mm
            String s = timestampStr;
            if (s.endsWith("Z")) {
                s = s.replace("Z", "+0000");
            } else {
                s = s.replaceAll(":(?=[0-9]{2}$)", "");  // normalize +hh:mm → +hhmm
            }

            SimpleDateFormat in =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
            in.setTimeZone(TimeZone.getTimeZone("UTC"));

            Date d = in.parse(s);
            return d != null ? out.format(d) : out.format(new Date());

        } catch (Exception e) {
            Log.w(TAG, "parseTimestamp fallback, input=" + timestampStr);
            SimpleDateFormat out =
                    new SimpleDateFormat(TIMESTAMP_FORMAT, Locale.US);
            out.setTimeZone(TimeZone.getTimeZone("UTC"));
            return out.format(new Date());
        }
    }


    // -------------------------------------------------------------
    // Safe split with escape handling
    // -------------------------------------------------------------

    /**
     * Splits the string by '|' but preserves escaped delimiters.
     * Example:
     *     a\|b|c → ["a|b", "c"]
     */
    private String[] splitPreservingEscapes(String input) {

        List<String> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        char delimChar  = FIELD_DELIMITER.charAt(0);
        char escapeChar = FIELD_ESCAPE.charAt(0);

        boolean nextEscaped = false;

        for (int i = 0; i < input.length(); i++) {

            char c = input.charAt(i);

            if (nextEscaped) {
                current.append(c);
                nextEscaped = false;
                continue;
            }

            if (c == escapeChar) {
                nextEscaped = true;
                continue;
            }

            if (c == delimChar) {
                // unescaped delimiter: split here
                parts.add(current.toString());
                current.setLength(0);
                continue;
            }

            current.append(c);
        }

        // final part
        parts.add(current.toString());
        return parts.toArray(new String[0]);
    }
}
