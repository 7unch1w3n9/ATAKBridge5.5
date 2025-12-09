package com.atakmap.android.LoRaBridge.recyclerview;

import android.annotation.SuppressLint;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.atakmap.android.LoRaBridge.Database.ChatMessageEntity;
import com.atakmap.android.LoRaBridge.plugin.R;
import com.atakmap.android.maps.MapView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * MessageAdapter
 *
 * RecyclerView adapter that renders a list of ChatMessageEntity objects
 * inside the chat dropdown.
 *
 * Responsibilities:
 *  - Bind message text, timestamp, and sender metadata
 *  - Align bubbles left or right depending on whether the message is from self
 *  - Display a date header when the calendar day changes
 */
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.ViewHolder> {

    /** Backing list of messages to display in the chat */
    private final List<ChatMessageEntity> messages;

    /**
     * Construct a new adapter with an initial message list.
     * The list is copied so that external modifications do not affect the adapter.
     */
    public MessageAdapter(List<ChatMessageEntity> initialMessages) {
        this.messages = new ArrayList<>(initialMessages);
    }

    /**
     * ViewHolder representing a single chat row with:
     *  - a bubble container
     *  - meta line (time and callsign)
     *  - message body text
     *  - date header shown at day boundaries
     */
    public static class ViewHolder extends RecyclerView.ViewHolder {
        public final TextView meta;
        public final TextView message;
        public final TextView date;
        public final View dateRow;
        public final ViewGroup bubble;
        public final ViewGroup container;

        public ViewHolder(View view) {
            super(view);
            container = view.findViewById(R.id.message_container);
            bubble = view.findViewById(R.id.message_bubble);
            meta = view.findViewById(R.id.message_meta);
            message = view.findViewById(R.id.message_text);
            date = view.findViewById(R.id.message_date);
            // dateRow currently uses the same view id as the date TextView
            dateRow = view.findViewById(R.id.message_date);
        }
    }

    // -------------------------------------------------------------
    // RecyclerView.Adapter methods
    // -------------------------------------------------------------

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(
            @NonNull ViewGroup parent,
            int viewType
    ) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_message, parent, false);
        return new ViewHolder(view);
    }

    /**
     * Parse a timestamp string into milliseconds since epoch.
     *
     * Supported formats:
     *  - Plain millisecond timestamp (digits only)
     *  - ISO 8601 with trailing Z, for example "2025-01-01T12:34:56.789Z"
     *  - ISO 8601 with numeric offset, for example "+01:00"
     */
    private long parseToMillis(String timeField) {
        try {
            if (timeField.matches("\\d+")) {
                // Already in milliseconds
                return Long.parseLong(timeField);
            }

            // Normalize ISO 8601 style timezone suffix
            if (timeField.endsWith("Z")) {
                timeField = timeField.replace("Z", "+0000");
            } else {
                // Convert "+01:00" into "+0100" for SimpleDateFormat
                timeField = timeField.replaceAll(":(?=[0-9]{2}$)", "");
            }

            SimpleDateFormat sdf =
                    new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US);
            Date parsed = sdf.parse(timeField);
            return parsed != null ? parsed.getTime() : -1L;

        } catch (Exception e) {
            e.printStackTrace();
            return -1L;
        }
    }

    @Override
    public void onBindViewHolder(
            @NonNull ViewHolder holder,
            int position
    ) {
        ChatMessageEntity messageEntity = messages.get(position);

        // Message body
        holder.message.setText(messageEntity.getMessage());

        // Format time for meta line
        long sentMillis = parseToMillis(messageEntity.getSentTime());
        String timeStr = "[--:--:--]";
        if (sentMillis > 0) {
            SimpleDateFormat timeFormat =
                    new SimpleDateFormat("HH:mm:ss", Locale.getDefault());
            timeStr = "[" + timeFormat.format(new Date(sentMillis)) + "]";
        }

        // Determine if this message was sent by the local device
        String senderUid = messageEntity.getSenderUid();
        String deviceUid = MapView.getDeviceUid();
        boolean isSelf = senderUid != null && senderUid.equals(deviceUid);

        String callsign = isSelf ? "Me" : messageEntity.getSenderCallsign();
        String metaLine = timeStr + " " + callsign + ":";
        holder.meta.setText(metaLine);

        // Align chat bubble according to sender
        LinearLayout.LayoutParams params =
                (LinearLayout.LayoutParams) holder.bubble.getLayoutParams();
        params.gravity = isSelf ? Gravity.END : Gravity.START;
        holder.bubble.setLayoutParams(params);
        holder.bubble.setBackgroundResource(R.drawable.chat_bubble_background);

        // Decide if a date header needs to be shown above this message
        boolean showDate = false;
        if (position == 0) {
            showDate = true;
        } else {
            ChatMessageEntity previous = messages.get(position - 1);
            if (previous != null) {
                long prevMillis = parseToMillis(previous.getSentTime());

                Calendar c1 = Calendar.getInstance();
                Calendar c2 = Calendar.getInstance();
                c1.setTimeInMillis(prevMillis);
                c2.setTimeInMillis(sentMillis);

                boolean sameDay =
                        c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR)
                                && c1.get(Calendar.MONTH) == c2.get(Calendar.MONTH)
                                && c1.get(Calendar.DAY_OF_MONTH) == c2.get(Calendar.DAY_OF_MONTH);

                showDate = !sameDay;
            }
        }

        if (showDate) {
            holder.dateRow.setVisibility(View.VISIBLE);
            try {
                SimpleDateFormat dateFormat =
                        new SimpleDateFormat("MM/dd/yyyy", Locale.getDefault());
                holder.date.setText(dateFormat.format(new Date(sentMillis)));
            } catch (Exception e) {
                holder.date.setText("--/--/----");
            }
        } else {
            holder.dateRow.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    // -------------------------------------------------------------
    // Data update API
    // -------------------------------------------------------------

    /**
     * Replace the entire message list with a new one and refresh the UI.
     */
    @SuppressLint("NotifyDataSetChanged")
    public void setMessages(List<ChatMessageEntity> newMessages) {
        messages.clear();
        messages.addAll(newMessages);
        notifyDataSetChanged();
    }
}
