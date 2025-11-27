package com.atakmap.android.LoRaBridge;

import android.app.Activity;
import android.content.Context;
import android.hardware.usb.UsbDeviceConnection;

import com.atakmap.android.LoRaBridge.ChatMessage.IncomingGeoChatListener;
import com.atakmap.android.LoRaBridge.ChatMessage.MessageDatenbankObserver;
import com.atakmap.android.LoRaBridge.ChatMessage.OutgoingCoTEventInterceptor;
import com.atakmap.android.LoRaBridge.JNI.FlowgraphEngine;
import com.atakmap.android.LoRaBridge.JNI.PluginNativeLoader;
import com.atakmap.android.LoRaBridge.JNI.UsbHackrfManager;
import com.atakmap.android.LoRaBridge.JNI.UsbHackrfManagerHolder;
import com.atakmap.android.LoRaBridge.phy.UdpManager;
import com.atakmap.android.LoRaBridge.ChatMessage.MessageSyncService;
import com.atakmap.android.LoRaBridge.GenericMessage.CotSyncService;
import com.atakmap.android.maps.MapView;
import com.atakmap.comms.CommsMapComponent;
import com.atakmap.coremap.log.Log;

public class BackendManager {
    private static final String TAG = "BackendManager";
    private final Context appContext;
    private final Activity hostActivity;
    private MessageSyncService syncService;
    private UsbHackrfManager usbMgr;
    private volatile boolean started = false;
    private IncomingGeoChatListener incomingGeoChatListener;
    private MessageDatenbankObserver messageDatenbankObserver;
    public BackendManager(Context appContext, Activity hostActivity) {
        this.appContext = appContext.getApplicationContext();
        this.hostActivity = hostActivity;
    }

    public synchronized void start() {
        if (started) return;
        Log.d(TAG, "Starting backend manager...");
        Log.d(TAG, "BackendManager ctx ist " + appContext);



        // Init USB manager
        if (usbMgr == null) {
            usbMgr = new UsbHackrfManager(appContext, "com.atakmap.android.LoRaBridge.USB_PERMISSION");
            UsbHackrfManagerHolder.set(usbMgr);
            usbMgr.setListener(new UsbHackrfManager.Listener() {
                @Override public void onHackrfReady(UsbDeviceConnection conn) {
                    if (usbMgr != null && !FlowgraphEngine.get().isBusy()) FlowgraphEngine.get().startWithConnection(conn);
                }
                @Override public void onHackrfDetached() {
                    FlowgraphEngine.get().stop();
                }
                @Override public void onPermissionDenied() {
                    Log.w(TAG, "USB permission denied");
                }
            });
        }
        usbMgr.start();
        if (incomingGeoChatListener == null) {
            incomingGeoChatListener = new IncomingGeoChatListener(MapView.getMapView().getContext());
        }

        if (messageDatenbankObserver == null) {
            messageDatenbankObserver = new MessageDatenbankObserver(appContext, hostActivity);
        }

        // Start FlowgraphEngine only when USB gives connection (see listener)
        // Start UDP
        UdpManager.getInstance().start();

        // Start sync services
        syncService = MessageSyncService.getInstance(appContext);
        CotSyncService.getInstance(appContext);

        CommsMapComponent.getInstance().registerPreSendProcessor(
                new OutgoingCoTEventInterceptor(appContext)
        );
        Log.d(TAG, "Backend manager started.");
        started = true;
    }

    public synchronized void stop() {
        if (!started) return;
        Log.d(TAG, "Stopping backend manager...");

        try { FlowgraphEngine.get().stop(); } catch (Throwable ignored) {}
        try { UdpManager.getInstance().stop(); } catch (Throwable ignored) {}
        try {
            if (usbMgr != null) {
                usbMgr.stop();
            }
        } catch (Throwable ignored) {}

        if (incomingGeoChatListener != null) {
            incomingGeoChatListener.shutdown();
            incomingGeoChatListener = null;
        }
        // Optionally stop sync services if they expose stop API
        if (syncService != null) {
            syncService.shutdown();
            syncService = null;
        }

        started = false;
        Log.d(TAG, "Backend manager stopped.");
    }

    public synchronized void destroy() {
        UsbHackrfManagerHolder.set(null);
    }

    public boolean isStarted() {
        return started;
    }


    public void probeUsbNow() {
        if (usbMgr != null) usbMgr.probeNow();
    }
}
