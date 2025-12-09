package com.atakmap.android.LoRaBridge.Database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * GenericCotDao
 *
 * Room DAO providing CRUD access to the generic_cot table, which stores
 * raw CoT events that are not GeoChat messages (e.g., sensor data, markers,
 * alerts, and all other ATAK CoT types).
 *
 * Intended usage:
 *   • Persist LoRa decoded CoT events
 *   • Cache ATAK-origin CoT events for debugging or replay
 *   • Provide observable LiveData streams for UI or analysis tools
 *
 * Notes:
 *   - INSERT uses IGNORE to avoid duplicate entries
 *   - Time-based ordering always uses ISO8601 timestamps stored in timeIso
 */
@Dao
public interface GenericCotDao {

    /**
     * Insert a new CoT entity.
     *
     * @param e GenericCotEntity representing a decoded or received CoT message
     * @return Row ID of the inserted entry, or -1 if ignored due to conflict
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    long insert(GenericCotEntity e);

    /**
     * Check whether a CoT message with the given ID already exists.
     *
     * @param id Unique CoT message identifier
     * @return Number of matching rows (0 or 1)
     */
    @Query("SELECT COUNT(*) FROM generic_cot WHERE id = :id")
    int existsById(String id);

    /**
     * Query all CoT messages produced by a specific UID.
     *
     * Sorted chronologically by ISO8601 timestamp.
     *
     * @param uid Sender UID inside CoT <link uid="">
     * @return LiveData list of all matching entries
     */
    @Query("SELECT * FROM generic_cot WHERE uid = :uid ORDER BY timeIso ASC")
    LiveData<List<GenericCotEntity>> getByUid(String uid);

    /**
     * Returns the most recent CoT event in the database.
     *
     * Uses descending ordering on timeIso.
     */
    @Query("SELECT * FROM generic_cot ORDER BY timeIso DESC LIMIT 1")
    LiveData<GenericCotEntity> latest();

    /**
     * Delete all stored generic CoT events.
     * Used when resetting the plugin or clearing historical logs.
     */
    @Query("DELETE FROM generic_cot")
    void deleteAll();
}
