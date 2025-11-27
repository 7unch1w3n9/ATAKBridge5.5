package com.atakmap.android.LoRaBridge.GenericMessage;

import com.atakmap.android.LoRaBridge.Database.GenericCotEntity;
import com.atakmap.android.LoRaBridge.phy.GenericCotConverter;

/**
 * LoRaGenericCotConverter
 *
 * Encodes and decodes generic CoT events for transmission through the LoRa
 * physical layer. The converter uses a lightweight framing format:
 *
 *   id | uid | type | timeIso | origin | <base64 EXI-encoded XML>
 *
 * The XML portion is compressed into EXI to reduce payload size. This is
 * essential for low-bandwidth environments such as LoRa.
 *
 * EXI encoding and decoding is performed by {@link ExiUtils}, which internally
 * uses a SAX-based pipeline. This processing strategy is similar to the one
 * used in the Meshtastic project, where a SAX parser emits events that are
 * fed into an EXIResult, and the EXIFactory encodes these into compact EXI
 * binary.
 *
 * No application-level semantics (markers, tasks, routes etc.) are interpreted
 * here; the converter only handles binary transformation between:
 *
 *   GenericCotEntity ⇄ byte[]
 */
public class LoRaGenericCotConverter implements GenericCotConverter {

    private static final String TAG = "LoRaGenericCotConverter";
    private static final String DELIM = "|";

    /**
     * Encode GenericCotEntity → LoRa payload.
     *
     * Steps:
     *   1. Convert raw XML to EXI bytes (SAX → EXIResult → EXIFactory)
     *   2. Base64-encode the EXI block
     *   3. Construct header fields separated by a delimiter
     *   4. Append EXI block and output UTF-8 bytes
     */
    @Override
    public byte[] encode(GenericCotEntity e) {
        try {
            // Convert XML → EXI using EXIResult & SAX parser
            byte[] exi = ExiUtils.toExi(e.cotRawXml);

            // Header metadata (5 fields)
            String head = String.join(
                    DELIM,
                    e.id,
                    e.uid,
                    e.type,
                    e.timeIso,
                    e.origin != null ? e.origin : ""
            );

            // EXI block encoded as Base64 (no newlines)
            String b64 = android.util.Base64.encodeToString(
                    exi,
                    android.util.Base64.NO_WRAP
            );

            // Final format: <header>|<base64 EXI>
            return (head + DELIM + b64)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);

        } catch (Exception ex) {
            com.atakmap.coremap.log.Log.e(TAG, "encodeCot failed", ex);
            return new byte[0];
        }
    }

    /**
     * Decode LoRa payload → GenericCotEntity.
     *
     * Steps:
     *   1. Split fields by delimiter
     *   2. Extract metadata fields
     *   3. Base64-decode EXI block
     *   4. Convert EXI → XML using EXISource and SAX
     *   5. Construct GenericCotEntity
     *
     * Accepts payloads that optionally start with the "LORA_COTX|" prefix.
     */
    @Override
    public GenericCotEntity decode(byte[] payload) {
        try {
            String s = new String(payload, java.nio.charset.StandardCharsets.UTF_8);

            // Optional prefix used by UdpManager routing
            final String HDR = "LORA_COTX|";
            final boolean hasHead = s.startsWith(HDR);

            // Unrestricted split preserves empty fields
            String[] parts = s.split("\\|", -1);

            int off = hasHead ? 1 : 0;

            if (parts.length - off < 6) {
                android.util.Log.e(TAG, "decodeCot: not enough fields, raw=" + s);
                return null;
            }

            String id     = parts[off + 0];
            String uid    = parts[off + 1];
            String type   = parts[off + 2];
            String time   = parts[off + 3];
            String origin = parts[off + 4];
            String b64    = parts[off + 5];

            // Remove whitespace that may appear during transport
            String cleaned = b64.replaceAll("\\s+", "");

            // Warn about characters outside Base64 alphabet
            String bads = cleaned.replaceAll("[A-Za-z0-9+/=]", "");
            if (!bads.isEmpty()) {
                android.util.Log.w(TAG, "decodeCot: non-base64 chars found: [" + bads + "]");
            }

            // Base64 → EXI bytes
            byte[] exi = android.util.Base64.decode(cleaned, android.util.Base64.NO_WRAP);

            // EXI → XML
            String xml = ExiUtils.fromExi(exi);

            return new GenericCotEntity(
                    id,
                    uid,
                    type,
                    time,
                    origin,
                    xml,
                    exi
            );

        } catch (Exception ex) {
            android.util.Log.e(TAG, "decodeCot failed", ex);
            return null;
        }
    }
}
