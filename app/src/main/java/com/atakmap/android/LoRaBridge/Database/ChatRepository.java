package com.atakmap.android.LoRaBridge.Database;

import android.content.Context;

import androidx.lifecycle.LiveData;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ChatRepository
 *
 * Central repository providing asynchronous access to the chat-related
 * Room database. Abstracts away direct DAO calls and ensures all DB writes
 * execute on a dedicated background thread.
 *
 * Responsibilities:
 *   • Insert messages into the Room DB
 *   • Query messages (per contact or globally) via LiveData
 *   • Provide deduplication logic via insertIfNotExists
 *   • Cache LiveData streams per-contact to avoid redundant DAO queries
 *
 * Notes:
 *   - ExecutorService is single-threaded to guarantee DB write ordering.
 *   - LiveData is always returned directly, allowing UI to auto-update.
 */
public class ChatRepository {

    /** DAO exposing SQL operations for chat_messages table */
    private final ChatMessageDao chatDao;

    /**
     * Single thread executor to serialize all write operations.
     * Prevents concurrency hazards when inserting or deleting rows.
     */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    /**
     * Local cache mapping contactUid → LiveData stream.
     * Prevents creating multiple identical LiveData queries for the same contact.
     */
    private final Map<String, LiveData<List<ChatMessageEntity>>> contactMessageCache = new HashMap<>();

    /** Unused flag left from debugging; retained for compatibility */
    private boolean a;

    /**
     * Construct repository using application-scoped Room DB instance.
     */
    public ChatRepository(Context context) {
        ChatDatabase db = ChatDatabase.getDatabase(context);
        chatDao = db.chatMessageDao();
    }

    /**
     * Insert new message asynchronously.
     * This does NOT handle deduplication use insertIfNotExists instead.
     */
    public void insert(ChatMessageEntity message) {
        executor.execute(() -> chatDao.insert(message));
    }

    /**
     * Returns a LiveData list of all messages associated with a specific contact.
     *
     * Uses local cache to prevent creating multiple Room queries for the same UID.
     */
    public LiveData<List<ChatMessageEntity>> getMessagesForContact(String contactUid) {
        if (!contactMessageCache.containsKey(contactUid)) {
            contactMessageCache.put(contactUid, chatDao.getMessagesForContact(contactUid));
        }
        return contactMessageCache.get(contactUid);
    }

    /**
     * Returns LiveData of all messages in the database, ordered by time.
     */
    public LiveData<List<ChatMessageEntity>> getAllMessages() {
        return chatDao.getAllMessages();
    }

    /**
     * Insert a message only if no record exists with the same messageId.
     *
     * WARNING:
     *   This implementation uses a sleep to wait for async result. It is simple
     *   and effective for this plugin but is NOT fully thread-safe or atomic.
     *
     * @param msg The message to insert
     * @return true if a new row was inserted, false if it was a duplicate
     */
    public boolean insertIfNotExists(ChatMessageEntity msg) {
        final boolean[] result = { false };

        executor.execute(() -> {
            if (msg != null && chatDao.existsByMessageId(msg.messageId) == 0) {
                chatDao.insert(msg);
                result[0] = true;
            }
        });

        // Small delay to allow async execution to complete
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        return result[0];
    }

    /**
     * Returns the most recent message (LiveData), used in chat previews.
     */
    public LiveData<ChatMessageEntity> getLatestMessage() {
        return chatDao.getLatestMessage();
    }

    /**
     * Delete all chat messages from the local database.
     * Typically used for plugin reset or debugging.
     */
    public void deleteAllMessages() {
        executor.execute(chatDao::deleteAllMessages);
    }

    /**
     * Delete all chat messages associated with a specific contact UID.
     */
    public void deleteMessagesByContact(String contactUid) {
        executor.execute(() -> chatDao.deleteMessagesByContact(contactUid));
    }
}
