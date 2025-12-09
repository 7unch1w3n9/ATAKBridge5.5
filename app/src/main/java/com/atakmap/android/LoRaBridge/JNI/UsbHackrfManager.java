package com.atakmap.android.LoRaBridge.JNI;

import android.app.PendingIntent;
import android.content.*;
import android.hardware.usb.*;
import android.os.Build;

import com.atakmap.coremap.log.Log;

/**
 * UsbHackrfManager
 *
 * Handles HackRF USB discovery, permission requests, connection lifecycle,
 * and automatic triggering of the FlowgraphEngine once a HackRF device
 * becomes available.
 *
 * Responsibilities:
 * - Register BroadcastReceiver to monitor USB attach/detach events
 * - Request permission for HackRF devices
 * - Open USB connections and notify a listener
 * - Track the currently active HackRF device by its device name
 *
 * This class does NOT own or manage the FlowgraphEngine directly,
 * but enforces the rule:
 *   “Do not open or re-open HackRF while the engine is busy.”
 */
public final class UsbHackrfManager {

    /** Callback interface for HackRF lifecycle events */
    public interface Listener {
        void onHackrfReady(UsbDeviceConnection conn);
        void onHackrfDetached();
        void onPermissionDenied();
    }

    /** Application context for system service access */
    private final Context appCtx;

    /** Android UsbManager */
    private final UsbManager usbManager;

    /** Custom action string used for Permission PendingIntent */
    private final String permissionAction;

    /** PendingIntent used for requestPermission */
    private PendingIntent permissionPI;

    /** Shared BroadcastReceiver for attach, detach and permission events */
    private BroadcastReceiver receiver;

    /** Listener for HackRF events */
    private Listener listener;

    /** Tracks the active HackRF device by name */
    private volatile String activeHackrfName;


    /**
     * Construct a new UsbHackrfManager.
     *
     * @param appCtx Application context
     * @param permissionAction Broadcast action for USB permission result
     */
    public UsbHackrfManager(Context appCtx, String permissionAction) {
        this.appCtx = appCtx.getApplicationContext();
        this.usbManager = (UsbManager) appCtx.getSystemService(Context.USB_SERVICE);
        this.permissionAction = permissionAction;
    }

    /** Set listener for HackRF events */
    public void setListener(Listener l) {
        this.listener = l;
    }


    // -------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------

    /**
     * Start monitoring USB events:
     * - USB attached
     * - USB detached
     * - Permission result
     *
     * Also sets up the PendingIntent and immediately probes for devices.
     */
    public void start() {

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        filter.addAction(permissionAction);

        receiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent intent) {

                String action = intent.getAction();
                android.util.Log.i("UsbHackrfManager",
                        "onReceive action=" + action + " thread=" + Thread.currentThread().getName());

                if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                    if (device != null)
                        android.util.Log.i("UsbHackrfManager", "ATTACHED: " + devStr(device));

                    probeNow();

                } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                    UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                    if (device == null) {
                        Log.w("UsbHackrfManager",
                                "DETACHED: null device, clearing activeHackrfName");
                        if (listener != null) listener.onHackrfDetached();
                        activeHackrfName = null;
                        return;
                    }

                    Log.w("UsbHackrfManager", "DETACHED: " + devStr(device));

                    // Only detach if this is the active HackRF
                    if (isHackrf(device) && devNameMatchesActive(device)) {
                        if (listener != null) listener.onHackrfDetached();
                        activeHackrfName = null;
                    }

                } else if (permissionAction.equals(action)) {
                    handlePermissionResult(intent);
                }
            }
        };

        if (Build.VERSION.SDK_INT >= 34) {
            appCtx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        else {
            appCtx.registerReceiver(receiver, filter);
        }

        // Prepare PendingIntent for requestPermission
        Intent intent = new Intent(permissionAction)
                .setPackage(appCtx.getPackageName());

        permissionPI = PendingIntent.getBroadcast(
                appCtx,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE
        );

        // Immediately check for devices
        probeNow();
    }

    /**
     * Stop monitoring USB events.
     * Clears receiver, PendingIntent and active device state.
     */
    public void stop() {
        try {
            if (receiver != null)
                appCtx.unregisterReceiver(receiver);
        } catch (Throwable ignore) {}

        receiver = null;
        permissionPI = null;
        activeHackrfName = null;
    }


    // -------------------------------------------------------------
    // Probing / Permission Handling
    // -------------------------------------------------------------

    /**
     * Manually enumerate USB devices and attempt to open the first HackRF.
     * Skips probing if FlowgraphEngine is currently running.
     */
    public void probeNow() {
        Log.i("UsbHackrfManager", "probeNow()");

        if (FlowgraphEngine.get().isBusy()) {
            Log.i("UsbHackrfManager", "Engine busy; skip probe");
            return;
        }

        for (UsbDevice d : usbManager.getDeviceList().values()) {
            Log.i("UsbHackrfManager", "  check " + devDump(d));

            if (!isHackrf(d))
                continue;

            boolean hasPermission = usbManager.hasPermission(d);
            Log.i("UsbHackrfManager",
                    "  isHackrf=true hasPermission=" + hasPermission);

            if (hasPermission) {
                openAndNotify(d);
            } else {
                usbManager.requestPermission(d, permissionPI);
            }
            break; // Only open first HackRF found
        }
    }

    /**
     * Handle the broadcast result of requestPermission.
     */
    private void handlePermissionResult(Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        boolean granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false);

        Log.i("UsbHackrfManager",
                "permission result device=" + devStr(device) + " granted=" + granted);

        if (device == null)
            return;

        if (!granted) {
            if (listener != null)
                listener.onPermissionDenied();
            return;
        }

        openAndNotify(device);
    }


    // -------------------------------------------------------------
    // Open / Notify
    // -------------------------------------------------------------

    /**
     * Open the HackRF device and notify listener.
     * Fails silently if FlowgraphEngine is currently running.
     */
    private void openAndNotify(UsbDevice d) {

        if (FlowgraphEngine.get().isBusy()) {
            android.util.Log.w("UsbHackrfManager",
                    "Engine busy; skip open " + d.getDeviceName());
            return;
        }

        android.util.Log.i("UsbHackrfManager", "openAndNotify OPEN " + devStr(d));

        UsbDeviceConnection conn = usbManager.openDevice(d);
        if (conn == null) {
            android.util.Log.w("UsbHackrfManager", "openDevice returned null");
            return;
        }

        android.util.Log.i(
                "UsbHackrfManager",
                "openDevice OK, fd=" + conn.getFileDescriptor() + " conn=" + conn
        );

        activeHackrfName = d.getDeviceName();

        if (listener != null) {
            listener.onHackrfReady(conn);
        } else {
            conn.close();
        }
    }


    // -------------------------------------------------------------
    // Utility Helpers
    // -------------------------------------------------------------

    private boolean devNameMatchesActive(UsbDevice d) {
        String name = d.getDeviceName();
        return activeHackrfName != null && activeHackrfName.equals(name);
    }

    /**
     * Recognize HackRF devices by VID/PID:
     * - VID: 0x1D50
     * - PID: 0x6089, 0x604B, 0xCC15
     */
    private static boolean isHackrf(UsbDevice d) {
        return d != null &&
                d.getVendorId() == 0x1D50 &&
                (d.getProductId() == 0x6089 ||
                        d.getProductId() == 0x604B ||
                        d.getProductId() == 0xCC15);
    }

    private static String devStr(UsbDevice d) {
        if (d == null) return "null";
        return "name=" + d.getDeviceName() +
                " vid=0x" + toHex(d.getVendorId()) +
                " pid=0x" + toHex(d.getProductId());
    }

    /** Dump extended info about a USB device for debugging */
    private static String devDump(UsbDevice d) {
        if (d == null) return "null";

        StringBuilder sb = new StringBuilder();
        sb.append("dev=").append(devStr(d))
                .append(" class=").append(d.getDeviceClass())
                .append(" sub=").append(d.getDeviceSubclass())
                .append(" proto=").append(d.getDeviceProtocol())
                .append(" ifaceCount=").append(d.getInterfaceCount());

        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface inf = d.getInterface(i);
            sb.append(" [if#").append(inf.getId())
                    .append(" cls=").append(inf.getInterfaceClass())
                    .append(" sub=").append(inf.getInterfaceSubclass())
                    .append(" ep=").append(inf.getEndpointCount())
                    .append("]");
        }

        return sb.toString();
    }

    private static String toHex(int v) {
        return String.format(java.util.Locale.US,
                "%04X", v & 0xFFFF);
    }
}
