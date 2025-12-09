package com.atakmap.android.LoRaBridge.recyclerview;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.atakmap.android.LoRaBridge.Contacts.ContactStore;
import com.atakmap.android.LoRaBridge.Contacts.PluginContact;
import com.atakmap.android.contact.Connector;
import com.atakmap.android.contact.Contact;
import com.atakmap.android.contact.Contacts;
import com.atakmap.android.contact.IndividualContact;
import com.atakmap.android.LoRaBridge.plugin.R;
import com.atakmap.coremap.log.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ContactAdapter
 *
 * RecyclerView adapter that displays a list of PluginContact objects.
 * It supports:
 * - Displaying ATAK contacts and local plugin contacts
 * - Opening chat windows for each contact
 * - Long press contextual menu for deleting local contacts
 * - Syncing ATAK contacts into the plugin's local database
 *
 * Local vs ATAK contact rules:
 * - ATAK contacts appear automatically and cannot be deleted in the plugin
 * - Local contacts (created in plugin) can be deleted
 */
public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ViewHolder> {

    private final Context context;

    /** Callback for opening a chat window */
    public interface OnContactClickListener {
        void onContactClick(PluginContact contact);
    }

    /** Callback for deleting a contact */
    public interface OnContactDeleteListener {
        void onDeleteContact(PluginContact contact);
    }

    /** List of contacts shown in the RecyclerView */
    private final List<PluginContact> contactList = new ArrayList<>();

    private final OnContactClickListener clickListener;
    private final OnContactDeleteListener deleteListener;
    private final Activity activity;

    /**
     * Create a new ContactAdapter.
     *
     * @param context     plugin context
     * @param listener    callback for contact clicks
     * @param deleteListener callback for deleting contacts
     * @param activity    host activity for dialogs
     */
    public ContactAdapter(Context context,
                          OnContactClickListener listener,
                          OnContactDeleteListener deleteListener,
                          Activity activity) {
        this.context = context;
        this.clickListener = listener;
        this.deleteListener = deleteListener;
        this.activity = activity;

        refreshContacts();
    }

    /**
     * ViewHolder for displaying a single contact row.
     */
    public static class ViewHolder extends RecyclerView.ViewHolder {
        private final TextView textView;

        public ViewHolder(View view) {
            super(view);
            textView = view.findViewById(R.id.contact_name);
        }

        public TextView getTextView() {
            return textView;
        }
    }

    // -------------------------------------------------------------
    // RecyclerView Adapter Logic
    // -------------------------------------------------------------

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_contact, parent, false);
        return new ViewHolder(view);
    }

    /**
     * Bind a PluginContact to a row.
     */
    @Override
    public void onBindViewHolder(
            @NonNull ViewHolder holder,
            int position
    ) {
        PluginContact contact = contactList.get(position);
        holder.getTextView().setText(contact.getCallsign());

        // Open chat on click
        holder.itemView.setOnClickListener(v -> clickListener.onContactClick(contact));

        // Long press = show delete menu (only for local contacts)
        holder.itemView.setOnLongClickListener(v -> {
            PopupMenu menu = new PopupMenu(context, v);
            if (contact.isLocal()) {
                menu.getMenu().add("Delete Contact");
            } else {
                menu.getMenu().add("(ATAK contact — cannot delete)");
            }

            menu.setOnMenuItemClickListener(mi -> {
                String title = mi.getTitle().toString();
                if ("Delete Contact".equals(title)) {
                    Log.w("How", context.toString());
                    new AlertDialog.Builder(activity)
                            .setTitle("Confirm Deletion")
                            .setMessage("Delete contact \"" + safe(contact.getCallsign()) + "\"? This only affects local plugin contacts.")
                            .setPositiveButton("Delete", (d, w) -> {
                                int pos  = holder.getAdapterPosition();
                                if (pos != RecyclerView.NO_POSITION) {
                                    contactList.remove(pos);
                                    notifyItemRemoved(pos);
                                } else {
                                    refreshContacts();
                                }
                                Toast.makeText(context, "Deleted", Toast.LENGTH_SHORT).show();
                                if (deleteListener != null) {
                                    deleteListener.onDeleteContact(contact);
                                }
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    Toast.makeText(context, "ATAK contacts cannot be deleted in the plugin.", Toast.LENGTH_SHORT).show();
                }
                return true;
            });
            menu.show();
            return true;
        });
    }

    private static String safe(String s) { return s == null ? "" : s; }

    @Override
    public int getItemCount() {
        return contactList.size();
    }

    public List<PluginContact> getContactList() {
        return contactList;
    }

    // -------------------------------------------------------------
    // Sync Logic
    // -------------------------------------------------------------

    /**
     * Sync ATAK contacts into local store.
     * Creates PluginContact entries for ATAK contacts that are not yet stored.
     *
     * @return true if new contacts were added
     */
    public boolean syncContactsFromATAK() {

        List<Contact> atakContacts = Contacts.getInstance().getAllContacts();
        List<PluginContact> stored = ContactStore.getAllContacts();

        Set<String> storedIds = new HashSet<>();
        for (PluginContact c : stored) {
            storedIds.add(c.getId());
        }

        boolean changed = false;

        for (Contact c : atakContacts) {

            if (c instanceof IndividualContact &&
                    !c.getExtras().getBoolean("fakeGroup", false)) {

                String uid = c.getUID();

                if (!storedIds.contains(uid)) {
                    PluginContact converted = convertToLoRaContact((IndividualContact) c);
                    ContactStore.saveContact(converted);
                    changed = true;
                }
            }
        }

        return changed;
    }

    /**
     * Add a new local contact to both memory and persistent store.
     */
    public void addContact(PluginContact contact) {
        ContactStore.saveContact(contact);
        contactList.add(contact);
        notifyItemInserted(contactList.size() - 1);
    }

    /**
     * Refresh the entire contact list from persistent storage.
     * Also updates the "local" flag based on whether the contact exists in ATAK.
     */
    public void refreshContacts() {
        try {
            contactList.clear();
            contactList.addAll(ContactStore.getAllContacts());

            Set<String> atakUids = getATAKContactUids();
            for (PluginContact c : contactList) {
                c.setLocal(!atakUids.contains(c.getId()));
            }

        } catch (Exception e) {
            Log.e("ContactAdapter", "Failed to refresh: " + e.getMessage());
        } finally {
            notifyDataSetChanged();
        }
    }

    /**
     * Get all ATAK IndividualContact UIDs.
     */
    private Set<String> getATAKContactUids() {
        Set<String> uids = new HashSet<>();

        for (Contact c : Contacts.getInstance().getAllContacts()) {
            if (c instanceof IndividualContact &&
                    !c.getExtras().getBoolean("fakeGroup", false)) {

                uids.add(c.getUID());
            }
        }
        return uids;
    }

    /**
     * Fully sync from ATAK then refresh the adapter.
     */
    public void fullSyncAndRefresh() {
        boolean changed = syncContactsFromATAK();
        refreshContacts();

        if (changed) {
            Log.d("ContactSync", "Completed full sync with changes");
        } else {
            Log.d("ContactSync", "Completed full sync (no changes)");
        }
    }

    /**
     * Convert an ATAK IndividualContact to a PluginContact with IP info.
     */
    private PluginContact convertToLoRaContact(IndividualContact c) {

        PluginContact out = new PluginContact(
                c.getUID(),
                c.getName(),
                c.getName()
        );

        Connector ipConnector = c.getConnector("connector.ip");
        if (ipConnector != null) {

            String[] parts = ipConnector.getConnectionString().split(":");
            if (parts.length >= 2) {
                out.setIpAddress(parts[0]);

                try {
                    out.setPort(Integer.parseInt(parts[1]));
                } catch (NumberFormatException ex) {
                    Log.e("ContactConvert", "Invalid port: " + parts[1]);
                }
            }
        }

        return out;
    }
}
