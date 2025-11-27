package com.atakmap.android.LoRaBridge.Database;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import java.util.List;

/**
 * ChatViewModel
 *
 * ViewModel layer in the MVVM architecture used by the plugin.
 * Provides lifecycle-aware access to chat-related data and delegates
 * all persistence tasks to ChatRepository.
 *
 * Responsibilities:
 *   • Expose LiveData streams to UI components (RecyclerViews, dropdowns)
 *   • Act as the stable interface between UI and Room database
 *   • Provide asynchronous insert/delete operations
 *
 * Notes:
 *   - Extends AndroidViewModel to obtain an application context,
 *     required for constructing the Room-backed repository.
 *   - UI classes must never access Room or DAO directly.
 */
public class ChatViewModel extends AndroidViewModel {

    /** Repository wrapping all Room database operations */
    private final ChatRepository repository;

    /**
     * Construct ViewModel and initialize repository with application context.
     */
    public ChatViewModel(@NonNull Application application) {
        super(application);
        repository = new ChatRepository(application);
    }

    /**
     * Insert a message asynchronously.
     * @param message The message to insert into the database
     */
    public void insert(ChatMessageEntity message) {
        repository.insert(message);
    }

    /**
     * Provides a LiveData stream of messages associated with a specific contact.
     *
     * Used by ChatDropDown to automatically update UI when DB changes.
     *
     * @param contactId Unique identifier of the contact (ATAK UID or plugin UID)
     * @return LiveData<List<ChatMessageEntity>>
     */
    public LiveData<List<ChatMessageEntity>> getMessagesForContact(String contactId) {
        return repository.getMessagesForContact(contactId);
    }

    /**
     * Returns a stream of all stored messages.
     * Mainly used for debugging or global views.
     */
    public LiveData<List<ChatMessageEntity>> getAllMessages() {
        return repository.getAllMessages();
    }

    /**
     * Returns the most recently timestamped message.
     * Useful for UI triggers or message observers.
     */
    public LiveData<ChatMessageEntity> getLatestMessage() {
        return repository.getLatestMessage();
    }

    /**
     * Deletes all messages in the database.
     * Typically used during plugin reset or debugging.
     */
    public void deleteAllMessages() {
        repository.deleteAllMessages();
    }

    /**
     * Insert message only if no duplicate with same messageId exists.
     * @param msg ChatMessageEntity to insert
     */
    public void insertIfNotExists(ChatMessageEntity msg) {
        repository.insertIfNotExists(msg);
    }

    /**
     * Deletes all messages associated with a specific contact.
     * Used when user removes a local plugin contact.
     */
    public void deleteMessagesForContact(String contactId) {
        repository.deleteMessagesByContact(contactId);
    }
}
