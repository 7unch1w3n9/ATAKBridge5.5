package com.atakmap.android.LoRaBridge.phy;

import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * UdpManager
 *
 * Manages a unified UDP channel for the plugin.
 *
 * Responsibilities:
 * - Maintain a single RX port and single TX socket
 * - Dispatch inbound packets to handlers based on message header:
 *      Chat messages   -> "LORA|"
 *      CoT messages    -> "LORA_COTX|"
 * - Provide async send API for chat and CoT messages
 * - Lifecycle is controlled externally via start()/stop()
 *
 * Threading:
 * - A dedicated thread performs blocking receive
 * - A thread pool routes messages and handles async send
 *
 * This class is implemented as a thread safe singleton.
 */
public class UdpManager {

    /** Functional interface for handlers receiving raw byte payloads */
    public interface ByteHandler { void accept(byte[] data); }

    /** Chat packet header prefix */
    public static final String HDR_CHAT = "LORA|";

    /** CoT packet header prefix */
    public static final String HDR_COT  = "LORA_COTX|";

    private static final String TAG = "UdpManager";

    /** Singleton instance (thread safe double check) */
    private static volatile UdpManager INSTANCE;

    /** Get singleton instance */
    public static UdpManager getInstance() {
        if (INSTANCE == null) {
            synchronized (UdpManager.class) {
                if (INSTANCE == null) INSTANCE = new UdpManager();
            }
        }
        return INSTANCE;
    }

    /** Plugin receives UDP from LoRa flowgraph via this port */
    private final int RX_PORT = 1383;

    /** Plugin sends UDP to the flowgraph via this port */
    private final int TX_PORT = 1382;

    /** Receiving and transmitting sockets */
    private DatagramSocket rxSocket;
    private DatagramSocket txSocket;

    /** Background thread running rxLoop() */
    private Thread receiveThread;

    /** Running flag for receive loop */
    private volatile boolean running = false;

    /** Optional message handlers */
    private volatile ByteHandler chatHandler;
    private volatile ByteHandler cotHandler;

    /** Optional host to mirror outgoing packets; currently unused */
    private volatile String mirrorHost = "192.168.0.213";

    /**
     * Thread pool used for:
     * - Routing inbound messages
     * - Async UDP sending
     */
    private final ExecutorService exec = new ThreadPoolExecutor(
            2, 4, 30, TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(100),
            new ThreadPoolExecutor.DiscardOldestPolicy()
    );

    private UdpManager() {}

    /** Register chat handler */
    public void setChatHandler(ByteHandler handler) { this.chatHandler = handler; }

    /** Register CoT handler */
    public void setCotHandler(ByteHandler handler)  { this.cotHandler = handler; }


    // -------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------

    /**
     * Start UDP subsystem:
     * - Bind receive socket
     * - Create TX socket
     * - Launch background receive thread
     */
    public synchronized void start() {
        if (running) return;
        try {
            rxSocket = new DatagramSocket(RX_PORT);
            txSocket = new DatagramSocket();
            running = true;

            receiveThread = new Thread(this::rxLoop, "Udp-Rx");
            receiveThread.start();

            Log.d(TAG, "UDP started on " + RX_PORT);

        } catch (IOException e) {
            Log.e(TAG, "UDP start failed", e);
        }
    }

    /**
     * Stop UDP subsystem:
     * - Stop loop
     * - Close sockets
     * - Interrupt thread
     * - Shutdown executor
     */
    public synchronized void stop() {
        running = false;

        if (rxSocket != null) {
            rxSocket.close();
            rxSocket = null;
        }

        if (txSocket != null) {
            txSocket.close();
            txSocket = null;
        }

        if (receiveThread != null) {
            receiveThread.interrupt();
            try { receiveThread.join(500); } catch (InterruptedException ignored) {}
            receiveThread = null;
        }

        exec.shutdownNow();
        Log.d(TAG, "UDP stopped");
    }


    // -------------------------------------------------------------
    // Public send API
    // -------------------------------------------------------------

    /** Send chat payload with chat header */
    public void sendChat(byte[] body) {
        if (body == null) return;
        byte[] payload = withHeader(HDR_CHAT, body);
        sendAsync(payload);
    }

    /** Send CoT payload with CoT header */
    public void sendCot(byte[] body) {
        if (body == null) return;
        byte[] payload = withHeader(HDR_COT, body);
        sendAsync(payload);
    }

    public void setMirrorHost(String hostOrNull) {
        this.mirrorHost = hostOrNull;
    }


    // -------------------------------------------------------------
    // Receive loop
    // -------------------------------------------------------------

    /**
     * Blocking receive loop that runs in a dedicated thread.
     * For each received packet:
     * - Copy payload to avoid array reuse
     * - Submit routing to executor
     */
    private void rxLoop() {
        byte[] buf = new byte[4096];

        while (running) {
            try {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                rxSocket.receive(p);

                byte[] data = Arrays.copyOf(p.getData(), p.getLength());
                exec.execute(() -> route(data));

            } catch (IOException e) {
                if (running)
                    Log.e(TAG, "UDP recv error", e);
            }
        }
    }


    // -------------------------------------------------------------
    // Routing logic
    // -------------------------------------------------------------

    /**
     * Routes each payload by checking header:
     * - If COT header -> cotHandler
     * - If CHAT header -> chatHandler
     * - Else -> unknown
     */
    private void route(byte[] data) {
        try {
            String prefix = new String(
                    data,
                    0,
                    Math.min(data.length, 24),
                    StandardCharsets.UTF_8
            );

            if (prefix.startsWith(HDR_COT)) {
                if (cotHandler != null) {
                    cotHandler.accept(data);
                } else {
                    Log.w(TAG, "Cot payload but cotHandler == null, drop");
                }
                return;
            }

            if (prefix.startsWith(HDR_CHAT)) {
                if (chatHandler != null) {
                    chatHandler.accept(stripHeader(data, HDR_CHAT.length()));
                } else {
                    Log.w(TAG, "Chat payload but chatHandler == null, drop");
                }
                return;
            }

            Log.w(TAG, "Unknown UDP payload head, drop. head=" + prefix);

        } catch (Throwable t) {
            Log.e(TAG, "route failed", t);
        }
    }


    // -------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------

    /** Strip header prefix from incoming chat packets */
    private static byte[] stripHeader(byte[] data, int headerLen) {
        int n = Math.max(0, data.length - headerLen);
        byte[] out = new byte[n];
        System.arraycopy(data, headerLen, out, 0, n);
        return out;
    }

    /** Submit UDP send to executor */
    private void sendAsync(byte[] payload) {
        exec.execute(() -> send(payload));
    }

    /**
     * Send payload to localhost:TX_PORT.
     * (Flowgraph listens there and forwards over LoRa.)
     */
    private void send(byte[] payload) {
        Log.d(TAG, "send() enter: running=" + running
                + " socket=" + txSocket
                + " closed=" + (rxSocket != null && rxSocket.isClosed())
                + " thread=" + Thread.currentThread().getName());

        if (payload == null || payload.length == 0)
            return;

        DatagramSocket sock = txSocket;

        if (sock == null || sock.isClosed()) {
            Log.w(TAG, "txSocket unavailable");
            return;
        }

        try {
            InetAddress localhost = InetAddress.getByName("127.0.0.1");
            sock.send(new DatagramPacket(payload, payload.length, localhost, TX_PORT));
            Log.d(TAG, "➡ local " + payload.length);
        } catch (Exception e) {
            Log.e(TAG, "UDP send failed", e);
        }
    }

    /** Ensures txSocket exists (not currently used externally) */
    private void ensureTxSocket() {
        if (txSocket != null) return;
        try {
            txSocket = new DatagramSocket();
        } catch (SocketException e) {
            Log.e(TAG, "create tx socket failed", e);
        }
    }

    /** Prepend header string to payload */
    private static byte[] withHeader(String header, byte[] body) {
        byte[] h = header.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[h.length + body.length];
        System.arraycopy(h, 0, out, 0, h.length);
        System.arraycopy(body, 0, out, h.length, body.length);
        return out;
    }
}
