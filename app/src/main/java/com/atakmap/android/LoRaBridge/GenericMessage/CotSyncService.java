package com.atakmap.android.LoRaBridge.GenericMessage;

import android.content.Context;

import com.atakmap.android.LoRaBridge.Database.GenericCotEntity;
import com.atakmap.android.LoRaBridge.Database.GenericCotRepository;
import com.atakmap.android.LoRaBridge.phy.GenericCotConverter;
import com.atakmap.android.LoRaBridge.phy.UdpManager;
import com.atakmap.android.cot.CotMapComponent;
import com.atakmap.coremap.cot.event.CotDetail;
import com.atakmap.coremap.cot.event.CotEvent;
import com.atakmap.coremap.log.Log;

import java.util.HashSet;
import java.util.Set;

/**
 * CotSyncService
 *
 * Bidirectional synchronization pipeline for generic (non-chat) CoT messages
 * between ATAK core and the LoRa physical layer.
 *
 * Responsibilities (high-level):
 *
 *   (1) ATAK → PHY
 *       • Listen to all incoming CoT events except b-t-f (chat)
 *       • Prevent processing loops using __plugin tagging
 *       • Convert CoT XML into GenericCotEntity
 *       • Encode entity to EXI or custom binary format (GenericCotConverter)
 *       • Send encoded payload to Flowgraph (LoRa PHY)
 *
 *   (2) PHY → ATAK
 *       • Receive raw payloads from Flowgraph via UdpManager
 *       • Decode bytes → GenericCotEntity → CotEvent
 *       • Add __plugin tag so ATAK listeners avoid re-sending it
 *       • Dispatch into ATAK’s CotMapComponent to update markers, icons, etc.
 *
 * Persistence:
 *   Every entity is stored in Room (GenericCotRepository) with deduplication.
 *
 * Loop Avoidance:
 *   __plugin/origin + originalId ensures that events do not bounce
 *   indefinitely between ATAK and PHY.
 */
public class CotSyncService {
    private static final String TAG = "CotSyncService";
    private static CotSyncService instance;

    /** Database repository for storing all generic CoT messages */
    private final GenericCotRepository repo;

    /** Converter for translating CoT <-> EXI or custom byte format */
    private final GenericCotConverter cotConverter;

    /** UDP transport for PHY communication */
    private final UdpManager udp = UdpManager.getInstance();

    /** Tracks processed entity IDs to prevent duplicated CoT dispatch */
    private final GenericTracker tracker = new GenericTracker();

    /**
     * Private constructor.
     * Initializes repository and registers PHY → ATAK handler.
     */
    private CotSyncService(Context ctx) {
        this.repo = new GenericCotRepository(ctx);
        this.cotConverter = new LoRaGenericCotConverter();   // Default converter implementation
        udp.setCotHandler(this::handlePhyPayload);
    }

    /**
     * Global singleton access.
     */
    public static synchronized CotSyncService getInstance(Context ctx) {
        if (instance == null) {
            instance = new CotSyncService(ctx);
        }
        return instance;
    }

    /**
     * Entry point for ATAK-origin CoT events that are NOT GeoChat messages.
     *
     * Pipeline:
     *   - Ignore chat (b-t-f)
     *   - Skip events already tagged with __plugin
     *   - Convert to GenericCotEntity
     *   - Deduplicate
     *   - Persist to DB
     *   - Add __plugin tag
     *   - Encode and send over LoRa
     */
    public void processIncomingCotFromAtak(CotEvent event) {
        if (event == null || !event.isValid()) return;

        if ("b-t-f".equals(event.getType())) return;  // Chat messages handled elsewhere

        // Loop prevention: skip PHY-origin events
        if (hasLoopTag(event)) {
            Log.d(TAG, "Skip looped CoT (__plugin present)");
            return;
        }

        // Convert XML → entity
        GenericCotEntity e = GenericCotFactory.fromCot(event, "Plugin");
        if (e == null) return;

        // Deduplication
        if (tracker.seen(e.id)) return;
        tracker.mark(e.id);

        // Persist
        repo.insertIfAbsent(e);

        // Mark the event to avoid ATAK loopback
        CotEvent marked = addLoopTag(event, "Plugin", e.id);

        // Encode and send to LoRa PHY
        try {
            byte[] body = cotConverter.encodeCot(e);
            udp.sendCot(body);
        } catch (Exception ex) {
            Log.e(TAG, "sendToFlowgraph failed", ex);
        }
    }

    /**
     * PHY → ATAK path.
     *
     * Raw payload from Flowgraph is decoded into a GenericCotEntity,
     * converted to XML, parsed back to CotEvent, tagged, and finally
     * dispatched to ATAK core to update situational awareness layers.
     */
    private void handlePhyPayload(byte[] payload) {
        try {
            // Byte → entity
            GenericCotEntity e = cotConverter.decodeCot(payload);
            if (e == null) return;

            // Optional filtering; can be adjusted per mission needs
            if ("b-t-f-d".equals(e.getType()) ||
                    "b-t-f-r".equals(e.getType()) ||
                    "a-f-G-U-C".equals(e.getType())) {

                Log.d(TAG, "Skip non a-h-g CoT: " + e.getType());
                return;
            }

            // Deduplication
            if (tracker.seen(e.id)) return;
            tracker.mark(e.id);

            // Store in DB
            repo.insertIfAbsent(e);

            // XML → CotEvent
            CotEvent ev = parseXmlToCot(e.cotRawXml);
            if (ev == null) return;

            // Add loop tag to avoid PHY → ATAK → PHY cycling
            ev = addLoopTag(ev, "PHY", e.id);

            // Dispatch into ATAK (map markers, objects, layers updated)
            CotMapComponent.getInternalDispatcher().dispatch(ev);

        } catch (Throwable t) {
            Log.e(TAG, "handlePhyPayload decode/dispatch error", t);
        }
    }

    // ------------------ Utility methods ------------------

    /** Check if event already contains a __plugin loop-prevention tag */
    private static boolean hasLoopTag(CotEvent ev) {
        CotDetail d = ev.getDetail();
        return d != null && d.getFirstChildByName(0, "__plugin") != null;
    }

    /** Attach __plugin tag to mark origin and originalId */
    private static CotEvent addLoopTag(CotEvent ev, String origin, String id) {
        CotDetail d = ev.getDetail();
        if (d == null) {
            d = new CotDetail("detail");
            ev.setDetail(d);
        }
        CotDetail l = new CotDetail("__plugin");
        l.setAttribute("origin", origin);
        l.setAttribute("originalId", id);
        d.addChild(l);
        return ev;
    }

    /** Safe XML parser wrapper */
    private static CotEvent parseXmlToCot(String xml) {
        try {
            return CotEvent.parse(xml);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * GenericTracker
     *
     * In memory deduplication helper for CoT IDs.
     * Prevents redundant insertions and CoT rebroadcast loops.
     *
     * Automatically clears itself when size becomes large to bound memory usage.
     */
    public static class GenericTracker {
        private final Set<String> processed = new HashSet<>();

        public synchronized boolean seen(String id) {
            return processed.contains(id);
        }

        public synchronized void mark(String id) {
            if (processed.size() > 2000) processed.clear();
            processed.add(id);
        }
    }
}
