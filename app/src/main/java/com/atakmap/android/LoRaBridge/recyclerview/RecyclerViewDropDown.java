package com.atakmap.android.LoRaBridge.recyclerview;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.atakmap.android.LoRaBridge.Contacts.ContactStore;
import com.atakmap.android.LoRaBridge.Contacts.PluginContact;
import com.atakmap.android.LoRaBridge.Database.ChatRepository;
import com.atakmap.android.LoRaBridge.Database.ChatViewModel;
import com.atakmap.android.dropdown.DropDownReceiver;
import com.atakmap.android.LoRaBridge.plugin.R;
import com.atakmap.android.maps.MapEvent;
import com.atakmap.android.maps.MapEventDispatcher;
import com.atakmap.android.maps.MapView;
import com.atakmap.android.util.time.TimeListener;
import com.atakmap.android.util.time.TimeViewUpdater;
import com.atakmap.coremap.maps.time.CoordinatedTime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RecyclerViewDropDown
 *
 * Dropdown window that shows the list of LoRaBridge contacts using a RecyclerView.
 * It is responsible for:
 *  - Displaying all known PluginContact entries
 *  - Opening per contact chat windows (ChatDropDown)
 *  - Deleting contacts and their associated messages
 *  - Opening the settings panel
 *  - Subscribing to map events and time updates (for future extensions)
 */
public class RecyclerViewDropDown extends DropDownReceiver implements
        MapEventDispatcher.MapEventDispatchListener,
        View.OnClickListener,
        TimeListener {

    private static final String TAG = "ContactsDropDown";

    /** ATAK MapView instance used for event registration and layout inflation */
    private final MapView mapView;

    /** Plugin context used for inflating views and showing toasts */
    private final Context pluginContext;

    /** Host Activity that owns the ViewModelStore */
    private final Activity hostActivity;

    /** Helper that drives periodic time updates on the UI */
    private final TimeViewUpdater timeUpdater;

    /** Root view of the contacts dropdown layout */
    private final View rootView;

    /** RecyclerView that displays the list of contacts */
    private final RecyclerView contactsRecycler;

    /**
     * Cached chat windows, one per contact id.
     * This avoids creating multiple ChatDropDown instances for the same contact.
     */
    private final Map<String, ChatDropDown> chatWindows = new HashMap<>();

    /** Adapter that binds PluginContact objects to RecyclerView rows */
    private final ContactAdapter contactsAdapter;

    /** Settings dropdown for LoRaBridge configuration */
    private SettingDropDown settingDropDown;

    /** ViewModel for chat message operations and queries */
    private ChatViewModel viewModel;

    /** Executor for potential background I/O tasks (reserved for extensions) */
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    /**
     * Create a new RecyclerView based dropdown that shows all known contacts.
     *
     * @param mapView   Current ATAK MapView
     * @param context   Plugin context
     * @param activity  Host activity used as ViewModelStoreOwner
     */
    public RecyclerViewDropDown(MapView mapView, Context context, Activity activity) {
        super(mapView);

        this.mapView = mapView;
        this.pluginContext = context;
        this.hostActivity = activity;

        // Obtain shared ChatViewModel from the activity scope
        viewModel = new ViewModelProvider((ViewModelStoreOwner) activity)
                .get(ChatViewModel.class);

        // Inflate the contacts panel layout
        rootView = LayoutInflater.from(pluginContext)
                .inflate(R.layout.pane_contacts, mapView, false);

        // Configure RecyclerView layout
        contactsRecycler = rootView.findViewById(R.id.rView);
        contactsRecycler.setLayoutManager(
                new LinearLayoutManager(pluginContext, LinearLayoutManager.VERTICAL, false)
        );

        // Configure adapter with callbacks for open chat and delete contact
        contactsAdapter = new ContactAdapter(
                pluginContext,
                this::openChat,
                contact -> {
                    // Delete all messages for this contact from the database
                    viewModel.deleteMessagesForContact(contact.getId());
                    // Remove the contact from the shared store
                    ContactStore.deleteContact(contact.getId());

                    Toast.makeText(
                            pluginContext,
                            "Deleted: " + contact.getCallsign(),
                            Toast.LENGTH_SHORT
                    ).show();
                },
                activity
        );
        contactsRecycler.setAdapter(contactsAdapter);

        // Wire up UI buttons
        rootView.findViewById(R.id.btnAddContact)
                .setOnClickListener(v -> addNewContact());

        rootView.findViewById(R.id.btnRefresh)
                .setOnClickListener(v -> refreshContacts());

        View btnSetting = rootView.findViewById(R.id.btnSetting);
        if (btnSetting != null) {
            btnSetting.setOnClickListener(v -> openSettings());
        } else {
            Log.w(TAG, "btnSetting not found in pane_contacts layout");
        }

        // Register for map events so that contacts can react to ATAK items if needed
        mapView.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_ADDED, this);
        mapView.getMapEventDispatcher().addMapEventListener(MapEvent.ITEM_REMOVED, this);

        // Periodic update for "time ago" style timestamps on contacts
        timeUpdater = new TimeViewUpdater(mapView, 1000);
        timeUpdater.register(this);
    }

    /**
     * Open or create the chat window for the given contact.
     *
     * @param contact Contact that should be opened in a ChatDropDown
     */
    private void openChat(PluginContact contact) {
        String uid = contact.getId();
        ChatDropDown win = chatWindows.get(uid);
        if (win == null) {
            win = new ChatDropDown(mapView, pluginContext, contact, hostActivity);
            chatWindows.put(uid, win);
        }
        Objects.requireNonNull(win).show();
    }

    /**
     * Refresh the visible contact list from the underlying store.
     */
    private void refreshContacts() {
        contactsAdapter.refreshContacts();
        Log.d("ContactRefresh", "Contacts refreshed");
    }

    /**
     * Create a new local contact with a generated device id and add it to the list.
     * This is primarily used as a simple demo and test entry.
     */
    private void addNewContact() {
        PluginContact newContact = new PluginContact(
                null,
                "New Contact",
                "DEV-" + System.currentTimeMillis()
        );
        newContact.setLocal(true);
        contactsAdapter.addContact(newContact);
        contactsAdapter.refreshContacts();
        Log.d("ContactAdd", "Added new contact: " + newContact.getCallsign());
    }

    /**
     * Open the settings dropdown window for plugin parameters.
     */
    private void openSettings() {
        if (settingDropDown == null) {
            settingDropDown = new SettingDropDown(mapView, pluginContext, hostActivity);
        }
        settingDropDown.show();
    }

    /**
     * Called when the dropdown is disposed by ATAK.
     * Unregisters listeners and stops the time updater.
     */
    @Override
    public void disposeImpl() {
        // Remove map listeners
        mapView.getMapEventDispatcher().removeMapEventListener(
                MapEvent.ITEM_ADDED, this);
        mapView.getMapEventDispatcher().removeMapEventListener(
                MapEvent.ITEM_REMOVED, this);

        // Stop time updates
        timeUpdater.unregister(this);
    }

    /**
     * TimeListener callback for global time changes.
     * Currently unused but available for future UI updates.
     */
    @Override
    public void onTimeChanged(CoordinatedTime ot, CoordinatedTime nt) {
        // Can be used to update relative timestamps on the contact list
    }

    /**
     * DropDownReceiver intent entry point.
     * Not used for this dropdown, which is opened programmatically.
     */
    @Override
    public void onReceive(Context ctx, Intent intent) {
        // No broadcast handling needed here
    }

    /**
     * Click handler for additional UI elements if needed.
     * Currently unused.
     */
    @Override
    public void onClick(View v) {
        // Could be used to switch between list layouts
    }

    /**
     * Map event callback.
     * Reserved for future integration between map items and contacts.
     */
    @Override
    public void onMapEvent(MapEvent event) {
        // No map event logic implemented yet
    }

    /**
     * Show the contacts dropdown and perform a full sync of the contact list
     * before it becomes visible.
     */
    public void show() {
        contactsAdapter.fullSyncAndRefresh();
        showDropDown(rootView, THREE_EIGHTHS_WIDTH, FULL_HEIGHT, FULL_WIDTH, THIRD_HEIGHT);
    }
}
