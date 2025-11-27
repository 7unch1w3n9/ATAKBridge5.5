package com.atakmap.android.LoRaBridge.Database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * ChatMessageDao
 *
 * Data access object for chat_messages table.
 *
 * Responsibilities:
 *   - Insert new messages (ignore on conflict for deduplication)
 *   - Query messages for a specific contact
 *   - Query all messages
 *   - Check message existence by messageId
 *   - Fetch the latest message chronologically
 *   - Delete messages globally or by contact
 *
 * All returned collections that represent ongoing UI state use LiveData,
 * allowing automatic UI updates when the database changes.
 */
@Dao
public interface ChatMessageDao {

    /**
     * Insert a new chat message.
     *
     * IGNORE strategy prevents overwriting or crashing when a message with
     * the same primary key already exists. This is used to avoid duplicates
     * when messages re enter the system through CoT or LoRa.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    void insert(ChatMessageEntity message);

    /**
     * Query all messages involving the given contact.
     *
     * SELECTed messages must satisfy:
     *   senderUid   = contactUid
     *   OR
     *   receiverUid = contactUid
     *
     * Results are ordered chronologically.
     */
    @Query("SELECT * FROM chat_messages WHERE senderUid = :contactUid OR receiverUid = :contactUid ORDER BY sentTime ASC")
    LiveData<List<ChatMessageEntity>> getMessagesForContact(String contactUid);

    /**
     * Retrieve all chat messages in the database, sorted by timestamp.
     */
    @Query("SELECT * FROM chat_messages ORDER BY sentTime ASC")
    LiveData<List<ChatMessageEntity>> getAllMessages();

    /**
     * Check if a message with the given messageId exists.
     *
     * Used by MessageSyncService to avoid duplicates during sync.
     */
    @Query("SELECT COUNT(*) FROM chat_messages WHERE messageId = :messageId")
    int existsByMessageId(String messageId);

    /**
     * Return the most recent message (by sentTime).
     * Useful for UI display such as chat previews.
     */
    @Query("SELECT * FROM chat_messages ORDER BY sentTime DESC LIMIT 1")
    LiveData<ChatMessageEntity> getLatestMessage();

    /**
     * Delete all chat messages globally.
     * Used mainly during debugging or plugin reset.
     */
    @Query("DELETE FROM chat_messages")
    void deleteAllMessages();

    /**
     * Delete all messages associated with a specific contact.
     * Called when the user removes a plugin specific contact.
     */
    @Query("DELETE FROM chat_messages WHERE senderUid = :contactUid OR receiverUid = :contactUid")
    void deleteMessagesByContact(String contactUid);
}
