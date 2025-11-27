package com.atakmap.android.LoRaBridge;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;

import com.atakmap.android.dropdown.DropDownMapComponent;
import com.atakmap.android.ipc.AtakBroadcast.DocumentedIntentFilter;
import com.atakmap.android.maps.MapView;

import com.atakmap.android.LoRaBridge.ChatMessage.IncomingGeoChatListener;
import com.atakmap.android.LoRaBridge.ChatMessage.MessageDatenbankObserver;
import com.atakmap.android.LoRaBridge.ChatMessage.MessageSyncService;
import com.atakmap.android.LoRaBridge.Contacts.ContactStore;
import com.atakmap.android.LoRaBridge.FlowgraphSetting.ParamsStore;
import com.atakmap.android.LoRaBridge.JNI.UsbHackrfManager;

import com.atakmap.android.LoRaBridge.plugin.R;
import com.atakmap.coremap.log.Log;

public class LoRaBridgeMapComponent extends DropDownMapComponent {

    private static final String TAG = "LoRaBridgeMapComponent";
    private BackendManager backendManager;
    private Context pluginContext;
    private MapView mapView;
    private Activity hostActivity;

    private LoRaBridgeDropDownReceiver ddr;

    private IncomingGeoChatListener incomingGeoChatListener;
    private MessageDatenbankObserver messageDatenbankObserver;
    private MessageSyncService syncService;
    private UsbHackrfManager usbMgr;

    @Override
    public void onCreate(final Context context, Intent intent, final MapView view) {

        context.setTheme(R.style.ATAKPluginTheme);
        super.onCreate(context, intent, view);

        this.pluginContext = context;
        this.mapView = view;

        if (view.getContext() instanceof Activity) {
            this.hostActivity = (Activity) view.getContext();
        }

        ddr = new LoRaBridgeDropDownReceiver(view, context, hostActivity);

        Log.d(TAG, "registering the plugin filter");
        DocumentedIntentFilter ddFilter = new DocumentedIntentFilter();
        ddFilter.addAction(LoRaBridgeDropDownReceiver.SHOW_PLUGIN);
        registerDropDownReceiver(ddr, ddFilter);

        backendManager = new BackendManager(MapView.getMapView().getContext(),hostActivity);
        ContactStore.init(mapView);
        ParamsStore.init(mapView);
        backendManager.start();
        Log.d(TAG, "LoRaBridgeMapComponent.onCreate - UI initialized");

    }

    @Override
    protected void onDestroyImpl(Context context, MapView view) {
        backendManager.stop();
        backendManager.destroy();
        super.onDestroyImpl(context, view);
        Log.d(TAG, "LoRaBridgeMapComponent.onDestroyImpl");
    }

    @Override
    public void onStart(Context context, MapView view) {
        super.onStart(context, view);

    }

    @Override
    public void onStop(Context context, MapView view) {
        super.onStop(context, view);
    }

    @Override
    public void onPause(Context context, MapView view) {
        super.onPause(context, view);
    }

    @Override
    public void onResume(Context context, MapView view) {
        super.onResume(context, view);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
    }

    @Override
    public boolean onCreateOptionsMenu(Context context, android.view.Menu menu) {
        return false;
    }

    @Override
    public boolean onPrepareOptionsMenu(Context context, android.view.Menu menu) {
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(Context context, android.view.MenuItem item) {
        return false;
    }

    @Override
    public void onOptionsMenuClosed(Context context, android.view.Menu menu) {
    }
}
