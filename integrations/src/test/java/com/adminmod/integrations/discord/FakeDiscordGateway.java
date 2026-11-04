package com.adminmod.integrations.discord;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A minimal WebSocket server that speaks just enough of the Discord Gateway protocol for a test:
 * it performs the HTTP upgrade, sends HELLO, and records the frames the client sends.
 *
 * <p>This is deliberately hand-rolled rather than mocked. The point of the test is to exercise the
 * real {@link java.net.http.WebSocket} client in {@link DiscordGatewayReceiver}, including the
 * handshake, the frame masking the client must apply, and the JSON it puts on the wire.
 */
final class FakeDiscordGateway implements AutoCloseable {

    private static final String WEBSOCKET_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket serverSocket;
    private final Thread thread;
    private final List<String> clientFrames = new CopyOnWriteArrayList<>();
    private final CountDownLatch helloAcknowledged = new CountDownLatch(1);

    private volatile Socket socket;
    private volatile OutputStream output;
    private volatile boolean running = true;

    FakeDiscordGateway() throws IOException {
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        thread = new Thread(this::serve, "fake-discord-gateway");
        thread.setDaemon(true);
        thread.start();
    }

    String url() {
        return "ws://127.0.0.1:" + serverSocket.getLocalPort();
    }

    List<String> clientFrames() {
        return List.copyOf(clientFrames);
    }

    /** Waits until the client has sent its first frame, which is IDENTIFY. */
    boolean awaitFirstFrame(long timeout, TimeUnit unit) throws InterruptedException {
        return helloAcknowledged.await(timeout, unit);
    }

    /** Sends a Gateway dispatch frame with the given event type and data object. */
    void sendDispatch(String type, String dataJson) throws IOException {
        String frame = "{\"op\":0,\"s\":1,\"t\":\"" + type + "\",\"d\":" + dataJson + "}";
        writeText(frame);
    }

    private void serve() {
        try (Socket accepted = serverSocket.accept()) {
            socket = accepted;
            InputStream in = accepted.getInputStream();
            OutputStream out = accepted.getOutputStream();
            output = out;
            if (!handshake(in, out)) {
                return;
            }
            // HELLO: the client answers with IDENTIFY.
            writeText("{\"op\":10,\"d\":{\"heartbeat_interval\":45000}}");
            while (running) {
                String frame = readFrame(in);
                if (frame == null) {
                    return;
                }
                if (!frame.isEmpty()) {
                    clientFrames.add(frame);
                    helloAcknowledged.countDown();
                }
            }
        } catch (IOException e) {
            if (running) {
                // A closed socket at shutdown is expected; anything else is recorded by the test.
                clientFrames.add("IO_ERROR: " + e.getMessage());
            }
        }
    }

    private boolean handshake(InputStream in, OutputStream out) throws IOException {
        ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                return false;
            }
            headerBytes.write(b);
            matched = switch (matched) {
                case 0 -> b == '\r' ? 1 : 0;
                case 1 -> b == '\n' ? 2 : 0;
                case 2 -> b == '\r' ? 3 : 0;
                default -> b == '\n' ? 4 : 0;
            };
        }
        String headers = headerBytes.toString(StandardCharsets.ISO_8859_1);
        String key = null;
        for (String line : headers.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).equalsIgnoreCase("Sec-WebSocket-Key")) {
                key = line.substring(colon + 1).trim();
            }
        }
        if (key == null) {
            return false;
        }
        String accept;
        try {
            accept = Base64.getEncoder().encodeToString(
                    MessageDigest.getInstance("SHA-1").digest(
                            (key + WEBSOCKET_MAGIC).getBytes(StandardCharsets.US_ASCII)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-1 is unavailable", e);
        }
        String response = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n";
        out.write(response.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        return true;
    }

    /** Reads one frame and returns its text payload, or {@code null} on close. */
    private String readFrame(InputStream in) throws IOException {
        int first = in.read();
        if (first < 0) {
            return null;
        }
        int opcode = first & 0x0F;
        int second = in.read();
        if (second < 0) {
            return null;
        }
        boolean masked = (second & 0x80) != 0;
        long length = second & 0x7F;
        if (length == 126) {
            length = (readByte(in) << 8) | readByte(in);
        } else if (length == 127) {
            length = 0;
            for (int i = 0; i < 8; i++) {
                length = (length << 8) | readByte(in);
            }
        }
        byte[] mask = new byte[4];
        if (masked) {
            readFully(in, mask);
        }
        byte[] payload = new byte[(int) length];
        readFully(in, payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        if (opcode == 0x8) {
            return null;
        }
        if (opcode == 0x1 || opcode == 0x0) {
            return new String(payload, StandardCharsets.UTF_8);
        }
        return "";
    }

    private void writeText(String text) throws IOException {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        OutputStream out = output;
        if (out == null) {
            return;
        }
        synchronized (out) {
            out.write(0x81);
            if (payload.length < 126) {
                out.write(payload.length);
            } else if (payload.length < 65536) {
                out.write(126);
                out.write((payload.length >> 8) & 0xFF);
                out.write(payload.length & 0xFF);
            } else {
                out.write(127);
                for (int shift = 56; shift >= 0; shift -= 8) {
                    out.write((int) ((payload.length >> shift) & 0xFF));
                }
            }
            out.write(payload);
            out.flush();
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("unexpected end of stream");
        }
        return value;
    }

    private static void readFully(InputStream in, byte[] target) throws IOException {
        int offset = 0;
        while (offset < target.length) {
            int read = in.read(target, offset, target.length - offset);
            if (read < 0) {
                throw new IOException("unexpected end of stream");
            }
            offset += read;
        }
    }

    @Override
    public void close() {
        running = false;
        Socket open = socket;
        if (open != null) {
            try {
                open.close();
            } catch (IOException ignored) {
                // Closing during teardown.
            }
        }
        try {
            serverSocket.close();
        } catch (IOException ignored) {
            // Closing during teardown.
        }
        thread.interrupt();
    }
}
