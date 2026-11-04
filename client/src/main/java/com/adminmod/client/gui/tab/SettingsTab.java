package com.adminmod.client.gui.tab;

import java.util.List;

import com.adminmod.client.gui.AdminDashboardScreen;
import com.adminmod.client.gui.DarkTextField;
import com.adminmod.client.gui.FlatButton;
import com.adminmod.client.gui.Theme;
import com.adminmod.client.net.AdminClientNetworking;
import com.adminmod.common.net.AdminProtocol;
import com.adminmod.common.net.AuthMode;

import net.minecraft.client.gui.DrawContext;

/**
 * Authentication, connection information and GUI preferences.
 *
 * <p>Three ways in are offered, in the order the operator is likely to want them: an already-paired
 * machine (a stored secret), a one-time pairing key (to enrol a new machine), or the shared operator
 * password (the bootstrap path). A credential typed here lives only in this screen's field and in
 * the in-memory session key; it is never written to disk on the client.
 */
public final class SettingsTab extends DashboardTab {

    private DarkTextField secretField;
    private boolean reveal;
    private String revealed = "";

    public SettingsTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Settings";
    }

    @Override
    public String subtitle() {
        return "Authentication, connection and preferences";
    }

    @Override
    public void init() {
        int formX = x + 12;
        int formWidth = Math.max(160, Math.min(320, width - 24));

        secretField = new DarkTextField(formX, y + 60, formWidth, 20,
                net.minecraft.text.Text.of("Pairing key or operator password"));
        secretField.setMaxLength(128);
        screen.addWidget(secretField);

        AdminClientNetworking net = AdminClientNetworking.get();
        boolean paired = net.isPaired();

        int rowY = y + 86;
        screen.addWidget(FlatButton.primary(formX, rowY, 118, 20, paired ? "Use stored key" : "Authenticate",
                button -> authenticate()));
        screen.addWidget(FlatButton.secondary(formX + 124, rowY, 118, 20, "Pair this client",
                button -> pair()));
        screen.addWidget(FlatButton.secondary(formX, rowY + 26, 118, 20, "Disconnect",
                button -> AdminClientNetworking.get().disconnect()));
        screen.addWidget(FlatButton.secondary(formX + 124, rowY + 26, 118, 20, "Forget pairing",
                button -> AdminClientNetworking.get().unpair()));
    }

    /**
     * Authenticates with the stored secret when this machine is paired, otherwise with the field
     * as a password. The panel decides, so the operator never has to.
     */
    private void authenticate() {
        AdminClientNetworking net = AdminClientNetworking.get();
        if (net.isPaired() && secretField.getText().isBlank()) {
            net.connectWithClientSecret();
            return;
        }
        char[] password = secretField.getText().toCharArray();
        net.connectWithPassword(password);
        java.util.Arrays.fill(password, '\0');
    }

    /** Redeems the field as a one-time pairing key and stores the secret the server returns. */
    private void pair() {
        if (secretField.getText().isBlank()) {
            screen.notifyError("Enter the pairing key from the server");
            return;
        }
        char[] key = secretField.getText().toCharArray();
        AdminClientNetworking.get().connectWithPairingKey(key);
        java.util.Arrays.fill(key, '\0');
    }

    @Override
    public void tick() {
        AdminClientNetworking net = AdminClientNetworking.get();
        if (net.isReady() && revealed.isEmpty() && !net.serverVersion().isEmpty()) {
            revealed = net.lastRevealedCredential();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        AdminClientNetworking net = AdminClientNetworking.get();

        panel(context, x, y, width, 146);
        text(context, "Connection", x + 12, y + 8, Theme.TEXT);
        statusDot(context, x + 12, y + 24, switch (net.state()) {
            case READY -> Theme.SUCCESS;
            case HANDSHAKING -> Theme.WARNING;
            case DISCONNECTED -> Theme.DANGER;
        });
        text(context, switch (net.state()) {
            case READY -> "Authenticated";
            case HANDSHAKING -> "Handshaking…";
            case DISCONNECTED -> "Not connected";
        }, x + 24, y + 23, Theme.TEXT);

        text(context, "server version: " + (net.serverVersion().isEmpty() ? "unknown" : net.serverVersion()),
                x + 12, y + 40, Theme.TEXT_DIM);
        text(context, "paired client: " + (net.isPaired() ? net.pairedClientId() : "not paired"),
                x + 12, y + 50, Theme.TEXT_DIM);

        // Which modes the server will accept is known only after the first handshake.
        if (net.lastStatus() >= 0) {
            String modes = describeModes(net);
            clippedText(context, "server accepts: " + modes, x + 12, y + 120, width - 24, Theme.TEXT_MUTED);
        }
        if (!net.lastError().isEmpty()) {
            clippedText(context, net.lastError(), x + 12, y + 132, width - 24, Theme.DANGER);
        }

        int logY = y + 158;
        int logHeight = height - 158;
        panel(context, x, logY, width, logHeight);
        text(context, "Session log", x + 12, logY + 8, Theme.TEXT_DIM);
        List<String> log = net.statusLog();
        int lineY = logY + 24;
        for (String line : log) {
            if (lineY + 11 > logY + logHeight - 8) {
                break;
            }
            clippedText(context, line, x + 12, lineY, width - 24, Theme.TEXT_MUTED);
            lineY += 11;
        }

        text(context, "Protocol version " + AdminProtocol.PROTOCOL_VERSION
                + " | channel " + AdminProtocol.CHANNEL, x, y + height - 20, Theme.TEXT_MUTED);

        if (!revealed.isEmpty()) {
            text(context, "Server credential: " + revealed, x, y + height - 32, Theme.WARNING);
        }
    }

    private static String describeModes(AdminClientNetworking net) {
        StringBuilder builder = new StringBuilder();
        append(builder, net, AuthMode.CLIENT_SECRET, "paired client");
        append(builder, net, AuthMode.PAIRING_KEY, "pairing key");
        append(builder, net, AuthMode.PASSWORD, "password");
        return builder.isEmpty() ? "none" : builder.toString();
    }

    private static void append(StringBuilder builder, AdminClientNetworking net, AuthMode mode, String label) {
        if (!net.serverAllows(mode)) {
            return;
        }
        if (!builder.isEmpty()) {
            builder.append(", ");
        }
        builder.append(label);
    }
}
