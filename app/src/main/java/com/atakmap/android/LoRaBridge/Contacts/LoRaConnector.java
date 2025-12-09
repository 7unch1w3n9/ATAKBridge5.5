package com.atakmap.android.LoRaBridge.Contacts;

import com.atakmap.android.LoRaBridge.plugin.R;
import com.atakmap.android.contact.Connector;
import com.atakmap.android.maps.MapView;
import com.atakmap.coremap.filesystem.FileSystemUtils;

public class LoRaConnector extends Connector {

    private final String connString;
    private final String label;

    public static final String TYPE = "connector.lora";

    public LoRaConnector(String connString, String label) {
        this.connString = connString;
        this.label = label != null ? label : "LoRa";
    }

    @Override
    public String getConnectionString() {
        return connString;
    }

    @Override
    public String getConnectionType() {
        return TYPE;
    }

    @Override
    public String getConnectionLabel() {
        return label;
    }

    @Override
    public boolean isUserConnector() {
        return true;
    }

    @Override
    public String getIconUri() {
        int resId = R.drawable.lora_icon;
        return "android.resource://"
                + MapView.getMapView().getContext().getPackageName()
                + "/" + resId;
    }

    @Override
    public int getPriority() {
        return 10;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof LoRaConnector)) return false;
        LoRaConnector other = (LoRaConnector) o;
        return FileSystemUtils.isEquals(getConnectionString(), other.getConnectionString());
    }

    @Override
    public int hashCode() {
        return getConnectionString().hashCode();
    }
}
