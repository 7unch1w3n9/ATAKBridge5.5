package com.atakmap.android.LoRaBridge.GenericMessage;

import com.atakmap.android.LoRaBridge.Database.GenericCotEntity;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.security.MessageDigest;

/**
 * GenericCotFactory
 *
 * Converts an ATAK CotEvent into a GenericCotEntity, which is the internal
 * representation used for persistence, EXI encoding, and LoRa transmission.
 *
 * Unlike GeoChat messages (b-t-f), generic CoT messages may represent:
 *   • map markers
 *   • tasking and routes
 *   • unit positions
 *   • sensor detections
 *   • any arbitrary ATAK event type
 *
 * The factory extracts event metadata, normalizes timestamps, minifies CoT XML,
 * and generates a stable SHA1-based identifier for deduplication.
 */
public final class GenericCotFactory {

    /** Utility class: prevent instantiation */
    private GenericCotFactory() {}

    /**
     * Convert a CotEvent into a GenericCotEntity.
     *
     * Processing steps:
     *   1. Extract metadata: UID, type, timestamp
     *   2. Minify XML to remove whitespace-only nodes
     *   3. Construct a deterministic SHA1 ID based on UID, type, time, and XML length
     *   4. Construct entity using origin (Plugin / PHY / Geo)
     *
     * @param event  Raw CoT event extracted from ATAK framework
     * @param origin Source label used for loop detection and debugging
     * @return GenericCotEntity ready for persistence and/or EXI encoding
     */
    public static GenericCotEntity fromCot(CotEvent event, String origin) {
        try {
            String uid = event.getUID();
            String type = event.getType();

            // Extract timestamp or fall back to "now"
            String timeIso = event.getTime() != null
                    ? event.getTime().toString()
                    : new CoordinatedTime().toString();

            // Canonicalized XML for storage and hashing
            String xml = minify(event.toString());

            // Deterministic CoT identifier
            String id = sha1(uid + "|" + type + "|" + timeIso + "|" + xml.length());

            return new GenericCotEntity(
                    id,
                    uid,
                    type,
                    timeIso,
                    origin,
                    xml,
                    null    // EXI bytes added later during LoRa transmission
            );

        } catch (Throwable t) {
            t.printStackTrace();
            return null;
        }
    }

    // ---------------- Helper Methods ----------------

    /**
     * Compute SHA1 of a string.
     * Used to create stable identifiers for deduplication.
     */
    private static String sha1(String s) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        byte[] b = md.digest(s.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    /**
     * Remove unnecessary whitespace between XML elements.
     * Reduces size and ensures consistent hashing.
     */
    private static String minify(String xml) {
        if (xml == null) return "";
        return xml.replaceAll(">\\s+<", "><").trim();
    }
}
