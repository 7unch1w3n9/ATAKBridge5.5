package com.atakmap.android.LoRaBridge.JNI;

import com.atakmap.coremap.log.Log;

import android.hardware.usb.UsbDeviceConnection;
import android.os.ParcelFileDescriptor;

import java.io.IOException;
import java.util.concurrent.*;

public final class FlowgraphEngine {
    private  static final String TAG = "FlowgraphEngine";
    private static final FlowgraphEngine I = new FlowgraphEngine();
    public static FlowgraphEngine get() { return I; }

    private final Object lock = new Object();
    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FlowgraphThread");
        t.setDaemon(true);
        return t;
    });
    private Future<?> task;
    private enum State { STOPPED, STARTING, RUNNING, STOPPING }
    private volatile State state = State.STOPPED;
    private UsbDeviceConnection heldConn;

    public boolean isBusy() {
        synchronized (lock) {
            return state == State.STARTING || state == State.RUNNING || state == State.STOPPING;
        }
    }
    private volatile CountDownLatch runningGate = new CountDownLatch(0);
    private volatile CountDownLatch terminated = new CountDownLatch(0);


    public void startWithConnection(UsbDeviceConnection conn) {
        synchronized (lock) {
            if (isBusy()) {
                Log.w(TAG, "start ignored: state=" + state);
                if (conn != null) conn.close();  // 立即释放
                return;
            }
            heldConn = conn;
            state = State.STARTING;

            final int rawFd = safeFd(conn);
            Log.i(TAG, "start enter fd=" + rawFd);

            runningGate = new CountDownLatch(1);
            terminated = new CountDownLatch(1);

            task = exec.submit(() -> {
                int fdForNative = -1;
                ParcelFileDescriptor pfd = null;

                try {
                    synchronized (lock) { state = State.RUNNING; }

                    pfd = ParcelFileDescriptor.fromFd(rawFd);
                    fdForNative = pfd.getFd();

                    Log.i(TAG, "run_flowgraph_with_fd(" + fdForNative + ") begin");
                    int rc = FlowgraphNative.run_flowgraph_with_fd(fdForNative);
                    Log.i(TAG, "run_flowgraph_with_fd exit rc=" + rc);

                } catch (Throwable t) {
                    Log.e("FlowgraphEngine", "flowgraph thread crashed", t);
                } if (conn != null) {
                    conn.close();
                    Log.i("FlowgraphEngine", "closed UsbDeviceConnection immediately after dup FD");
                }
                heldConn = null;
            });
        }
    }
    public void stop() {
        Log.i(TAG, "stop() called, state=" + state);

        synchronized (lock) {
            if (state == State.STOPPED) {
                Log.i(TAG, "stop() ignored: already STOPPED");
                return;
            }
            state = State.STOPPING;
        }
        try {
            Log.i(TAG, "Calling FlowgraphNative.shutdown()");
            FlowgraphNative.shutdown();

            CountDownLatch gate = runningGate;
            if (gate != null) gate.countDown();

            CountDownLatch done = terminated;
            if (done != null) {
                Log.i(TAG, "Waiting for flowgraph to terminate...");
                boolean finished = done.await(2000, TimeUnit.MILLISECONDS);
                if (!finished) {
                    Log.w(TAG, "Flowgraph termination timeout");
                }
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.e(TAG, "stop() interrupted", e);
        } finally {
            Future<?> f;
            synchronized (lock) {
                f = task;
                task = null;
                state = State.STOPPED;
            }

            if (f != null && !f.isDone()) {
                f.cancel(true);
            }

            Log.i(TAG, "stop() complete");
        }
    }


    private void closeConnQuietly(long ms) {
        final UsbDeviceConnection c = heldConn;
        heldConn = null;
        if (c == null) return;
        exec.submit(() -> {
            int fdSnapshot = -1;
            try { fdSnapshot = c.getFileDescriptor(); } catch (Throwable ignore) {}
            try {
                Log.i("FlowgraphEngine", "close UsbDeviceConnection (delayed) fd=" + fdSnapshot);
                c.close();
            } catch (Throwable ignore) {}
        });
    }

    private static int safeFd(UsbDeviceConnection c) {
        if (c == null) return -1;
        try { return c.getFileDescriptor(); } catch (Throwable t) { return -2; }
    }
}
