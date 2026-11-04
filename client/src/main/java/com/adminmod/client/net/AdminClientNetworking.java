package com.adminmod.client.net;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.adminmod.common.net.AdminProtocol;
import com.adminmod.common.net.Envelope;
import com.adminmod.common.net.HandshakeMessage;
import com.adminmod.common.net.HandshakeResultMessage;
import com.adminmod.common.net.Message;
import com.adminmod.common.net.OrderMessage;
import com.adminmod.common.net.OrderResultMessage;
import com.adminmod.common.net.OrderType;
import com.adminmod.common.security.Kdf;
import com.adminmod.common.security.PasswordVault;
import com.adminmod.common.security.SessionCipher;
import com.adminmod.common.security.SessionException;
import com.adminmod.common.security.SlidingWindowRateLimiter;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;

/**
 * Client half of the remote administration protocol.
 *
 * <p>State machine: DISCONNECTED, then HANDSHAKING once a password is supplied, then READY. The
 * password is never written to disk on the client: the operator types it into the authentication
 * panel, which calls {@link #connect(char[])}. When the session is not ready every outgoing
 * request fails immediately with a clear message instead of silently doing nothing.
 *
 * <p>Requests are serialised: one in flight at a time, with a timeout, so a lost answer cannot
 * leave the dashboard waiting forever. Every request carries a monotonically increasing id and a
 * fresh nonce, and the server rejects anything it has already seen.
 */
public final class AdminClientNetworking {

    public enum State {
        DISCONNECTED,
        HANDSHAKING,
        READY
    }

    private record Pending(int requestId, OrderType orderType, Consumer<OrderResultMessage> callback, long deadline) {
    }

    private static final AdminClientNetworking INSTANCE = new AdminClientNetworking();

    private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
    private final Deque<String> statusLog = new ArrayDeque<>();
    private final AtomicInteger nextRequestId = new AtomicInteger(1);
    private final SlidingWindowRateLimiter outgoingLimiter = new SlidingWindowRateLimiter(240, 60_000L);

    private volatile State state = State.DISCONNECTED;
    private volatile SessionCipher cipher;
    private volatile byte[] passwordKey;
    private volatile byte[] clientNonce;
    private volatile String serverVersion = "";
    private volatile String lastError = "";
    private volatile int lastStatus = -1;
    private volatile String revealedCredential = "";

    private AdminClientNetworking() {
    }

    public static AdminClientNetworking get() {
        return INSTANCE;
    }

    public State state() {
        return state;
    }

    public boolean isReady() {
        return state == State.READY;
    }

    public String serverVersion() {
        return serverVersion;
    }

    public String lastError() {
        return lastError;
    }

    public int lastStatus() {
        return lastStatus;
    }

    /**
     * The credential the server chose to disclose, if any.
     *
     * <p>Empty unless the server enabled {@code remoteAdmin.revealPasswordToOperators} and the
     * connecting player already held operator rights. It is held in memory for this session only.
     */
    public String lastRevealedCredential() {
        return revealedCredential;
    }

    public int pendingCount() {
        return pending.size();
    }

    public java.util.List<String> statusLog() {
        synchronized (statusLog) {
            return new java.util.ArrayList<>(statusLog);
        }
    }

    private void log(String message) {
        synchronized (statusLog) {
            statusLog.addFirst(message);
            while (statusLog.size() > 64) {
                statusLog.removeLast();
            }
        }
    }

    /** Begins the handshake with a freshly typed password. */
    public void connect(char[] password) {
        if (password == null || password.length == 0) {
            lastError = "Enter the operator password";
            return;
        }
        if (!ClientPlayNetworking.canSend(AdminPayload.ID)) {
            state = State.DISCONNECTED;
            lastError = "This server does not run the ADMIN Mod server jar";
            log("Handshake refused: channel unavailable");
            return;
        }
        byte[] nonce = PasswordVault.randomNonce(HandshakeMessage.NONCE_LENGTH);
        this.clientNonce = nonce;
        this.state = State.HANDSHAKING;
        this.lastError = "";
        this.lastStatus = -1;
        this.cipher = null;
        this.passwordKey = Kdf.derivePasswordKey(password);

        HandshakeMessage handshake = new HandshakeMessage(AdminProtocol.PROTOCOL_VERSION, "1.0.0", nonce);
        ClientPlayNetworking.send(new AdminPayload(
                new Envelope(0L, System.currentTimeMillis(), Message.encode(handshake)).encode()));
        log("Handshake sent (protocol " + AdminProtocol.PROTOCOL_VERSION + ")");
    }

    /** Called from the client payload receiver for every inbound frame. */
    public void onFrame(byte[] frame) {
        Envelope envelope;
        try {
            envelope = Envelope.decode(frame);
        } catch (RuntimeException e) {
            return;
        }

        Message message;
        try {
            message = Message.decode(envelope.body());
        } catch (RuntimeException e) {
            message = null;
        }

        if (message instanceof HandshakeResultMessage result) {
            onHandshakeResult(result);
            return;
        }

        SessionCipher active = cipher;
        if (active == null) {
            return;
        }
        byte[] plaintext;
        try {
            plaintext = active.decrypt(envelope.body(), envelope.nonce(), envelope.timestamp());
        } catch (SessionException e) {
            lastError = "A response failed to authenticate; the session was closed";
            log(lastError);
            reset();
            return;
        }

        Message decoded;
        try {
            decoded = Message.decode(plaintext);
        } catch (RuntimeException e) {
            return;
        }
        if (decoded instanceof OrderResultMessage result) {
            Pending request = pending.remove(result.requestId());
            if (request != null) {
                try {
                    request.callback().accept(result);
                } catch (RuntimeException e) {
                    log("Dashboard error while handling a response");
                }
            }
        }
    }

    private void onHandshakeResult(HandshakeResultMessage result) {
        this.lastStatus = result.status();
        this.serverVersion = result.serverVersion();
        this.revealedCredential = result.credential();
        switch (result.status()) {
            case HandshakeResultMessage.STATUS_OK -> {
                byte[] key = passwordKey;
                if (key == null || clientNonce == null) {
                    lastError = "Handshake state was lost; reconnect";
                    reset();
                    return;
                }
                this.cipher = new SessionCipher(Kdf.deriveSessionKey(key, clientNonce, result.serverNonce()));
                this.state = State.READY;
                this.lastError = "";
                log("Session established with server " + result.serverVersion());
            }
            case HandshakeResultMessage.STATUS_VERSION_MISMATCH -> {
                lastError = "Protocol mismatch: server speaks " + result.protocolVersion()
                        + ", this client speaks " + AdminProtocol.PROTOCOL_VERSION;
                log(lastError);
                reset();
            }
            case HandshakeResultMessage.STATUS_DISABLED -> {
                lastError = "Remote administration is disabled on this server";
                log(lastError);
                reset();
            }
            default -> {
                lastError = "The server rejected the handshake";
                log(lastError);
                reset();
            }
        }
    }

    private void reset() {
        state = State.DISCONNECTED;
        cipher = null;
        passwordKey = null;
        clientNonce = null;
        revealedCredential = "";
        pending.clear();
    }

    /** Drops the session, for example when the connection to the server ends. */
    public void disconnect() {
        reset();
        log("Disconnected");
    }

    /**
     * Sends an order and registers a callback for its answer.
     *
     * @return {@code false} when nothing was sent because the session is not ready, the arguments
     *         are invalid, or the local rate budget is exhausted
     */
    public boolean send(OrderType orderType, Consumer<OrderResultMessage> callback, String... arguments) {
        if (state != State.READY || cipher == null) {
            if (callback != null) {
                callback.accept(new OrderResultMessage(0, false, lastError.isEmpty()
                        ? "Not connected: open Settings and authenticate" : lastError));
            }
            return false;
        }
        if (!outgoingLimiter.tryAcquire(System.currentTimeMillis())) {
            if (callback != null) {
                callback.accept(new OrderResultMessage(0, false, "Local request limit reached; slow down"));
            }
            return false;
        }

        int requestId = nextRequestId.getAndIncrement();
        long nonce = ThreadLocalRandom.current().nextLong();
        long timestamp = System.currentTimeMillis();
        OrderMessage order = new OrderMessage(requestId, orderType, arguments);
        byte[] body = cipher.encrypt(Message.encode(order), nonce, timestamp);

        pending.put(requestId, new Pending(requestId, orderType,
                callback == null ? result -> {
                } : callback, timestamp + 15_000L));

        try {
            ClientPlayNetworking.send(new AdminPayload(new Envelope(nonce, timestamp, body).encode()));
            return true;
        } catch (RuntimeException e) {
            pending.remove(requestId);
            if (callback != null) {
                callback.accept(new OrderResultMessage(requestId, false, "Could not send the request"));
            }
            return false;
        }
    }

    /** Fails requests whose answer never arrived. Called from the client tick. */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Pending> entry : pending.entrySet()) {
            Pending request = entry.getValue();
            if (now > request.deadline() && pending.remove(entry.getKey(), request)) {
                request.callback().accept(new OrderResultMessage(request.requestId(), false,
                        "The server did not answer in time"));
            }
        }
    }

    /** The local player's UUID, used only for display. */
    public static UUID localPlayerUuid() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player == null ? new UUID(0L, 0L) : client.player.getUuid();
    }
}
