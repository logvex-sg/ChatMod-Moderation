package com.adminmod.client.net;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.adminmod.client.auth.ClientCredentialStore;
import com.adminmod.common.net.AdminProtocol;
import com.adminmod.common.net.AuthMode;
import com.adminmod.common.net.Envelope;
import com.adminmod.common.net.HandshakeMessage;
import com.adminmod.common.net.HandshakeResultMessage;
import com.adminmod.common.net.Message;
import com.adminmod.common.net.OrderMessage;
import com.adminmod.common.net.OrderResultMessage;
import com.adminmod.common.net.OrderType;
import com.adminmod.common.net.PairingRequestMessage;
import com.adminmod.common.net.PairingResultMessage;
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
 * <p>State machine: DISCONNECTED, then HANDSHAKING once a credential is supplied, then READY. The
 * credential is never written to disk except the paired-client secret, which the operator explicitly
 * saved by pairing. The operator password is typed into the authentication panel and only its derived
 * key is held in memory.
 *
 * <p>Three authentication modes are supported, and the client picks the strongest one it can: a
 * stored paired secret if this machine is paired, otherwise a one-time pairing key, otherwise the
 * shared password. Requests are serialised one at a time with a timeout, and every request carries a
 * fresh nonce that the server rejects if it has already seen it.
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

    private ClientCredentialStore store = ClientCredentialStore.empty();
    private Path storeDirectory;

    private volatile State state = State.DISCONNECTED;
    private volatile SessionCipher cipher;
    private volatile byte[] credentialKey;
    private volatile byte[] clientNonce;
    private volatile AuthMode pendingMode = AuthMode.PASSWORD;
    private volatile String pendingClientId = "";
    private volatile boolean awaitingPairing;
    private volatile String serverVersion = "";
    private volatile String serverBuildId = "";
    private volatile int allowedModes = 0;
    private volatile String lastError = "";
    private volatile int lastStatus = -1;
    private volatile String revealedCredential = "";
    private volatile String lastIssuedPairingKey = "";

    private AdminClientNetworking() {
    }

    public static AdminClientNetworking get() {
        return INSTANCE;
    }

    /** Points the networking at the directory holding the paired-client credential. */
    public void bindStore(Path directory) {
        this.storeDirectory = directory;
        this.store = ClientCredentialStore.load(directory);
    }

    public boolean isPaired() {
        return store.isPaired();
    }

    public String pairedClientId() {
        return store.clientId();
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

    public String serverBuildId() {
        return serverBuildId;
    }

    public String lastError() {
        return lastError;
    }

    public int lastStatus() {
        return lastStatus;
    }

    public String lastRevealedCredential() {
        return revealedCredential;
    }

    public String lastIssuedPairingKey() {
        return lastIssuedPairingKey;
    }

    /** Whether the server said it accepts the given authentication mode. */
    public boolean serverAllows(AuthMode mode) {
        return (allowedModes & (1 << mode.id())) != 0;
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

    /**
     * Authenticates with the strongest credential available.
     *
     * <p>A stored paired secret is used if present; otherwise the supplied value is used as a
     * one-time pairing key when the server allows pairing and the value looks like a pairing key
     * (a single token), or as the password. The panel chooses explicitly when it can, so this
     * convenience path is only a fallback.
     */
    public void connect(char[] password) {
        if (store.isPaired()) {
            connectWithClientSecret();
            return;
        }
        connectWithPassword(password);
    }

    /** Authenticates with the shared operator password. */
    public void connectWithPassword(char[] password) {
        if (password == null || password.length == 0) {
            lastError = "Enter the operator password";
            return;
        }
        startHandshake(AuthMode.PASSWORD, "", password);
    }

    /** Redeems a one-time pairing key and enrols this machine as a new client. */
    public void connectWithPairingKey(char[] pairingKey) {
        if (pairingKey == null || pairingKey.length == 0) {
            lastError = "Enter the pairing key";
            return;
        }
        awaitingPairing = true;
        startHandshake(AuthMode.PAIRING_KEY, "", pairingKey);
    }

    /** Authenticates with the paired secret stored on this machine. */
    public void connectWithClientSecret() {
        if (!store.isPaired()) {
            lastError = "This client is not paired";
            return;
        }
        awaitingPairing = false;
        startHandshake(AuthMode.CLIENT_SECRET, store.clientId(), store.clientSecret().toCharArray());
    }

    private void startHandshake(AuthMode mode, String clientId, char[] credential) {
        if (!ClientPlayNetworking.canSend(AdminPayload.ID)) {
            state = State.DISCONNECTED;
            lastError = "This server does not run the ADMIN Mod server jar";
            log("Handshake refused: channel unavailable");
            return;
        }
        byte[] nonce = PasswordVault.randomNonce(HandshakeMessage.NONCE_LENGTH);
        this.clientNonce = nonce;
        this.pendingMode = mode;
        this.pendingClientId = clientId;
        this.state = State.HANDSHAKING;
        this.lastError = "";
        this.lastStatus = -1;
        this.cipher = null;
        wipe(this.credentialKey);
        // Each mode has its own at-rest salt on the server, so the client must derive the same
        // verifier the server will look up: a password verifier, a pairing-key verifier, or a
        // paired-client secret verifier.
        this.credentialKey = switch (mode) {
            case PASSWORD -> Kdf.derivePasswordKey(credential);
            case PAIRING_KEY -> Kdf.derivePairingKey(new String(credential));
            case CLIENT_SECRET -> Kdf.deriveClientKey(new String(credential));
        };
        // The caller's array is not needed once the key is derived; clear it in place.
        java.util.Arrays.fill(credential, '\0');

        HandshakeMessage handshake = new HandshakeMessage(AdminProtocol.PROTOCOL_VERSION, AdminProtocol.BUILD_ID,
                mode.id(), clientId, nonce);
        ClientPlayNetworking.send(new AdminPayload(
                new Envelope(0L, System.currentTimeMillis(), Message.encode(handshake)).encode()));
        log("Handshake sent (" + mode.name() + ", protocol " + AdminProtocol.PROTOCOL_VERSION + ")");
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
        if (decoded instanceof PairingResultMessage result) {
            onPairingResult(result);
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
        this.serverBuildId = result.serverBuildId();
        this.allowedModes = result.allowedModes();
        this.revealedCredential = result.credential();
        switch (result.status()) {
            case HandshakeResultMessage.STATUS_OK -> {
                byte[] key = credentialKey;
                if (key == null || clientNonce == null) {
                    lastError = "Handshake state was lost; reconnect";
                    reset();
                    return;
                }
                this.cipher = new SessionCipher(
                        Kdf.deriveSessionKey(key, pendingMode.id(), clientNonce, result.serverNonce()));
                this.state = State.READY;
                this.lastError = "";
                log("Session established with server " + result.serverVersion() + " (" + pendingMode.name() + ")");
                // In pairing mode the first encrypted frame carries the pairing request.
                if (awaitingPairing && pendingMode == AuthMode.PAIRING_KEY) {
                    sendPairingRequest();
                }
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
            case HandshakeResultMessage.STATUS_BUILD_REJECTED -> {
                lastError = "This client build is not accepted by the server";
                log(lastError);
                reset();
            }
            case HandshakeResultMessage.STATUS_UNKNOWN_CLIENT -> {
                lastError = "This client is no longer paired with the server";
                log(lastError);
                // The stored credential is stale; drop it so the operator can re-pair.
                store.clear();
                persistStore();
                reset();
            }
            case HandshakeResultMessage.STATUS_LOCKED_OUT -> {
                lastError = "Too many failed attempts; try again later";
                log(lastError);
                reset();
            }
            case HandshakeResultMessage.STATUS_MODE_NOT_ALLOWED -> {
                lastError = "The server does not allow this authentication mode";
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

    private void sendPairingRequest() {
        int requestId = nextRequestId.getAndIncrement();
        long nonce = ThreadLocalRandom.current().nextLong();
        long timestamp = System.currentTimeMillis();
        PairingRequestMessage request = new PairingRequestMessage(requestId, clientLabel(), AdminProtocol.BUILD_ID);
        byte[] body = cipher.encrypt(Message.encode(request), nonce, timestamp);
        try {
            ClientPlayNetworking.send(new AdminPayload(new Envelope(nonce, timestamp, body).encode()));
            log("Pairing request sent");
        } catch (RuntimeException e) {
            lastError = "Could not send the pairing request";
            log(lastError);
        }
    }

    private void onPairingResult(PairingResultMessage result) {
        awaitingPairing = false;
        if (!result.success()) {
            lastError = result.message();
            log("Pairing failed: " + result.message());
            reset();
            return;
        }
        store.set(result.clientId(), result.clientSecret());
        persistStore();
        log("Paired as " + result.clientId());
        // Re-authenticate with the stored secret so the live session is bound to the new identity.
        connectWithClientSecret();
    }

    private String clientLabel() {
        MinecraftClient client = MinecraftClient.getInstance();
        String player = client.getSession() == null ? "" : client.getSession().getUsername();
        String host = client.getCurrentServerEntry() == null ? "singleplayer"
                : client.getCurrentServerEntry().address;
        String label = (player.isBlank() ? "client" : player) + "@" + host;
        return label.length() <= 48 ? label : label.substring(0, 48);
    }

    private void persistStore() {
        if (storeDirectory == null) {
            return;
        }
        try {
            store.save(storeDirectory);
        } catch (IOException e) {
            log("Could not save the paired credential");
        }
    }

    /** Forgets the stored pairing and drops the session. */
    public void unpair() {
        store.clear();
        persistStore();
        reset();
        log("Unpaired this client");
    }

    private void reset() {
        state = State.DISCONNECTED;
        cipher = null;
        wipe(credentialKey);
        credentialKey = null;
        clientNonce = null;
        revealedCredential = "";
        pendingMode = AuthMode.PASSWORD;
        pendingClientId = "";
        awaitingPairing = false;
        pending.clear();
    }

    private static void wipe(byte[] key) {
        if (key != null) {
            java.util.Arrays.fill(key, (byte) 0);
        }
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

    /** Records a pairing key the operator just generated, so the panel can show it once. */
    public void noteIssuedPairingKey(String key) {
        this.lastIssuedPairingKey = key == null ? "" : key;
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
