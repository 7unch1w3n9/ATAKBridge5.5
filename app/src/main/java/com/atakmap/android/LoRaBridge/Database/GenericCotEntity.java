package com.atakmap.android.LoRaBridge.Database;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;
import androidx.room.ColumnInfo;

/**
 * GenericCotEntity
 *
 * Room database entity representing a generic CoT (Cursor on Target) message.
 * Unlike ChatMessageEntity which stores only GeoChat-style messages (b-t-f),
 * this entity is used for *all other* CoT types:
 *
 *   - Position reports
 *   - Sensor telemetry
 *   - Alerts and markers
 *   - Any custom CoT payload sent through ATAK
 *   - LoRa-decoded CoT packets (Phase 2 feature)
 *
 * Notable features:
 *   • Stores both textual CoT XML (cotRawXml) and binary EXI representation (exiBytes)
 *   • Uses ISO8601 timestamps for consistent sorting
 *   • Tracks origin to differentiate ATAK → LoRa vs LoRa → ATAK
 */
@Entity(tableName = "generic_cot")
public class GenericCotEntity {

    /**
     * Globally unique identifier for the CoT event.
     *
     * This typically corresponds to:
     *   <event uid="...">
     *
     * It is used as the primary key to guarantee deduplication across
     * radio transmission, ATAK broadcasts, and database caching.
     */
    @PrimaryKey
    @NonNull
    @ColumnInfo(name = "id")
    public String id;

    /**
     * Sender UID extracted from the <link uid=""/> element.
     * Allows grouping CoT messages per originating device.
     */
    @ColumnInfo(name = "uid")
    public String uid;

    /**
     * CoT type field (event.getType()).
     *
     * Example values:
     *   a-f-G-U-C   ← friendly unit
     *   b-t-r       ← route
     *   b-t-f       ← chat (rarely stored here)
     *   u-d-f       ← sensor detection
     */
    @ColumnInfo(name = "type")
    public String type;

    /**
     * Timestamp of the event in ISO8601 string form.
     * Ensures chronological ordering in queries.
     */
    @ColumnInfo(name = "timeIso")
    public String timeIso;

    /**
     * Marks the source:
     *
     *   "ATAK"      → Received from internal ATAK network
     *   "LoRa"      → Decoded from physical LoRa payload
     *   "Plugin"    → Locally generated for testing
     *
     * Useful for filtering sync loops or identifying radio-origin messages.
     */
    @ColumnInfo(name = "origin")
    public String origin;

    /**
     * Full XML representation of the CoT event, as received or generated.
     * May be null when only EXI form exists.
     */
    @ColumnInfo(name = "cotRawXml")
    public String cotRawXml;

    /**
     * EXI-encoded CoT (Efficient XML Interchange binary format).
     *
     * Binary CoT is significantly smaller than XML and may be transmitted
     * efficiently over LoRa or other constrained radios.
     */
    @ColumnInfo(name = "exiBytes", typeAffinity = ColumnInfo.BLOB)
    public byte[] exiBytes;

    /**
     * Standard constructor called by Room and repository logic.
     */
    public GenericCotEntity(@NonNull String id,
                            String uid,
                            String type,
                            String timeIso,
                            String origin,
                            String cotRawXml,
                            byte[] exiBytes) {
        this.id = id;
        this.uid = uid;
        this.type = type;
        this.timeIso = timeIso;
        this.origin = origin;
        this.cotRawXml = cotRawXml;
        this.exiBytes = exiBytes;
    }

    /** Simple getter for the CoT type field. */
    public String getType() {
        return type;
    }
}
