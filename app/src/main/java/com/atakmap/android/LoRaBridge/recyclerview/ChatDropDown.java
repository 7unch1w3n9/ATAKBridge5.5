package com.atakmap.android.LoRaBridge.recyclerview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.atakmap.android.LoRaBridge.ChatMessage.ChatMessageFactory;
import com.atakmap.android.LoRaBridge.Contacts.PluginContact;
import com.atakmap.android.LoRaBridge.Database.ChatMessageEntity;
import com.atakmap.android.LoRaBridge.plugin.R;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.gui.EditText;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;
import com.atakmap.android.LoRaBridge.Database.ChatViewModel;

import java.util.ArrayList;
import java.util.List;

/**
 * ChatDropDown
 *
 * Dropdown window that displays a chat interface for a single PluginContact.
 * Responsibilities:
 *  - Render all messages for the selected contact
 *  - Observe the database and update messages in real time
 *  - Allow the user to write and send new messages
 *  - Clean up observers when the dropdown is closed
 */
public class ChatDropDown extends DropDownReceiver {

    /** Root view for the chat dropdown */
    private final View rootView;

    /** RecyclerView for chat messages */
    private final RecyclerView messageRecycler;

    /** Input field where user types messages */
    private final EditText messageInput;

    /** Send button */
    private final Button sendButton;

    /** Adapter for displaying messages */
    private final MessageAdapter messageAdapter;

    /** Database observer that updates UI when messages change */
    private Observer<List<ChatMessageEntity>> messageObserver;

    /** ViewModel for reading and writing chat messages */
    private ChatViewModel viewModel;

    /** Contact that this chat window is associated with */
    private final PluginContact contact;


    /**
     * Create a chat dropdown tied to a specific PluginContact.
     *
     * @param mapView MapView reference for DropDownReceiver
     * @param context UI context for inflating layouts
     * @param contact The contact that is being chatted with
     * @param activity Activity used as ViewModelStoreOwner
     */
    public ChatDropDown(MapView mapView,
                        Context context,
                        PluginContact contact,
                        Activity activity) {
        super(mapView);

        this.contact = contact;

        // Inflate chat UI
        rootView = LayoutInflater.from(context).inflate(R.layout.pane_chat, null);

        // Title
        TextView title = rootView.findViewById(R.id.chat_title);
        title.setText("Chat with " + contact.getCallsign());

        // RecyclerView setup
        messageRecycler = rootView.findViewById(R.id.chat_message_list);
        messageAdapter = new MessageAdapter(new ArrayList<>());
        messageRecycler.setAdapter(messageAdapter);
        messageRecycler.setLayoutManager(new LinearLayoutManager(context));

        // Obtain ViewModel
        viewModel = new ViewModelProvider((ViewModelStoreOwner) activity)
                .get(ChatViewModel.class);

        // Observe messages for this contact
        messageObserver = messages -> {
            messageAdapter.setMessages(messages);
            scrollToBottom();
        };
        LiveData<List<ChatMessageEntity>> liveMessages =
                viewModel.getMessagesForContact(contact.getId());
        liveMessages.observeForever(messageObserver);

        // Load initial messages if available
        List<ChatMessageEntity> initial = liveMessages.getValue();
        if (initial != null) {
            messageAdapter.setMessages(initial);
            scrollToBottom();
        }

        // Input and send button
        messageInput = rootView.findViewById(R.id.chat_input);
        sendButton = rootView.findViewById(R.id.chat_send_button);

        // Sending messages
        sendButton.setOnClickListener(v -> {
            String text = messageInput.getText().toString().trim();
            if (text.isEmpty()) {
                Log.e("Chat", "Invalid message: empty content");
                return;
            }

            // Create message entity
            ChatMessageEntity msg = ChatMessageFactory.fromUserInput(
                    MapView.getDeviceUid(),
                    MapView._mapView.getDeviceCallsign(),
                    contact.getId(),
                    contact.getCallsign(),
                    text,
                    "text"
            );

            // Insert if not duplicated
            viewModel.insertIfNotExists(msg);

            // Clear input field
            messageInput.setText("");
        });
    }


    /**
     * Show the dropdown window.
     */
    public void show() {
        showDropDown(rootView, HALF_WIDTH, FULL_HEIGHT, FULL_WIDTH, HALF_HEIGHT);
    }


    /**
     * Called by DropDownReceiver when UI is disposed.
     * Removes LiveData observers to avoid memory leaks.
     */
    @Override
    protected void disposeImpl() {
        if (messageObserver != null) {
            viewModel.getMessagesForContact(contact.getId())
                    .removeObserver(messageObserver);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        // Not used in this dropdown
    }

    /** Scroll the RecyclerView to the bottom (latest message). */
    private void scrollToBottom() {
        if (messageAdapter.getItemCount() > 0) {
            messageRecycler.scrollToPosition(messageAdapter.getItemCount() - 1);
        }
    }
}
