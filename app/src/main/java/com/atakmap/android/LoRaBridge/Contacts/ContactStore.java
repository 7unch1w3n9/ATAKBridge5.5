package com.atakmap.android.LoRaBridge.Contacts;

import android.content.Context;
import android.content.SharedPreferences;

import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * ContactStore
 *
 * Persistent storage for plugin contacts stored in SharedPreferences.
 * These contacts represent LoRaBridge specific address book entries
 * that are independent from ATAK’s internal contact system.
 *
 * Characteristics:
 *   - Stores contacts as JSON strings under keys contact_<id>
 *   - Maintains a persistent set of all contact IDs
 *   - Provides CRUD operations (create, read, delete, clear)
 *   - Does not synchronize with ATAK Contacts unless done externally
 *
 * Storage location:
 *   <app-data-dir>/shared_prefs/plugin_contacts_store.xml
 *
 * Initialization:
 *   ContactStore.init(mapView) must be called once early in plugin lifecycle.
 */
public class ContactStore {

    private static final String TAG = "ContactStore";

    /** Name of SharedPreferences file */
    private static final String PREFS_NAME = "plugin_contacts_store";

    /** Key storing the full set of contact IDs */
    private static final String CONTACT_IDS_KEY = "contact_ids";

    /** Prefix for serialized contact entries */
    private static final String CONTACT_PREFIX = "contact_";

    /** Cached MapView context reference for initialization */
    private static MapView mapView;

    /** Backing SharedPreferences instance */
    private static SharedPreferences sPrefs;


    // -------------------------------------------------------------
    // Initialization
    // -------------------------------------------------------------

    /**
     * Initialize the ContactStore with the current MapView.
     * Must be called once before using any ContactStore actions.
     *
     * @param mapView1 active ATAK MapView instance
     */
    public static void init(MapView mapView1) {
        mapView = mapView1;

        sPrefs = mapView.getContext()
                .getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        Log.d(TAG, "Prefs file = "
                + mapView.getContext().getApplicationContext().getApplicationInfo().dataDir
                + "/shared_prefs/" + PREFS_NAME + ".xml");
    }

    /**
     * Get backing SharedPreferences or throw if not initialized.
     */
    private static SharedPreferences prefs() {
        if (sPrefs == null)
            throw new IllegalStateException("ContactStore not initialized");
        return sPrefs;
    }


    // -------------------------------------------------------------
    // Create / Update
    // -------------------------------------------------------------

    /**
     * Save a contact to persistent storage.
     *
     * Behavior:
     *   - Generates a unique ID if missing
     *   - Stores contact JSON under contact_<id>
     *   - Updates the master set of contact IDs
     */
    public static void saveContact(PluginContact contact) {
        SharedPreferences p = prefs();
        SharedPreferences.Editor editor = p.edit();

        if (contact.getId() == null || contact.getId().isEmpty()) {
            contact.setId(generateUniqueId());
        }

        String contactJson = contact.toJson();
        editor.putString(CONTACT_PREFIX + contact.getId(), contactJson);

        Set<String> contactIds =
                new HashSet<>(Objects.requireNonNull(
                        p.getStringSet(CONTACT_IDS_KEY, new HashSet<>())
                ));

        contactIds.add(contact.getId());
        editor.putStringSet(CONTACT_IDS_KEY, contactIds);

        editor.apply();
        Log.d(TAG, "Saved contact: " + contact.getCallsign());
    }


    // -------------------------------------------------------------
    // Read
    // -------------------------------------------------------------

    /**
     * Retrieve all contacts stored in SharedPreferences.
     *
     * @return list of PluginContact objects
     */
    public static List<PluginContact> getAllContacts() {
        SharedPreferences p = prefs();
        Set<String> contactIds = p.getStringSet(CONTACT_IDS_KEY, new HashSet<>());

        List<PluginContact> contacts = new ArrayList<>();
        assert contactIds != null;

        for (String id : contactIds) {
            String json = p.getString(CONTACT_PREFIX + id, null);
            if (json != null) {
                PluginContact contact = PluginContact.fromJson(json);
                if (contact != null) {
                    contacts.add(contact);
                }
            }
        }

        return contacts;
    }

    /**
     * Lookup a contact by ID.
     */
    public static PluginContact getContactById(String id) {
        SharedPreferences p = prefs();
        String json = p.getString(CONTACT_PREFIX + id, null);
        return json != null ? PluginContact.fromJson(json) : null;
    }

    /**
     * Lookup a contact by callsign.
     *
     * @return the first match or null
     */
    public static PluginContact getContactByCallsign(String callsign) {
        for (PluginContact contact : getAllContacts()) {
            if (contact.getCallsign().equals(callsign)) {
                return contact;
            }
        }
        return null;
    }


    // -------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------

    /**
     * Delete a single contact and update contact ID set.
     */
    public static void deleteContact(String id) {
        SharedPreferences p = prefs();
        SharedPreferences.Editor e = p.edit();

        Set<String> ids =
                new HashSet<>(Objects.requireNonNull(
                        p.getStringSet(CONTACT_IDS_KEY, new HashSet<>())
                ));

        ids.remove(id);
        e.putStringSet(CONTACT_IDS_KEY, ids);
        e.remove(CONTACT_PREFIX + id);

        boolean ok = e.commit();
        Log.d(TAG, "Deleted " + id + " saved=" + ok);
    }

    /**
     * Wipe all stored contacts.
     */
    public static void clearAllContacts() {
        SharedPreferences p = prefs();
        p.edit().clear().apply();
        Log.d(TAG, "Cleared all contacts");
    }


    // -------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------

    /**
     * Generates a unique ID for local-only contacts.
     * No collision protection beyond timestamp and random suffix.
     */
    public static String generateUniqueId() {
        return "LOCAL_" + System.currentTimeMillis() + "_"
                + (int) (Math.random() * 1000);
    }

    /**
     * Retrieve the raw contact ID set from SharedPreferences.
     */
    private static Set<String> getContactIdSet(SharedPreferences prefs) {
        return new HashSet<>(prefs.getStringSet(CONTACT_IDS_KEY, new HashSet<>()));
    }
}
