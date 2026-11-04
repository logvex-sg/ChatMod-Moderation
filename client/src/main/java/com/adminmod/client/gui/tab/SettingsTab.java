package com.adminmod.client.gui.tab;

import java.util.List;

import com.adminmod.client.gui.AdminDashboardScreen;
import com.adminmod.client.gui.DarkTextField;
import com.adminmod.client.gui.FlatButton;
import com.adminmod.client.gui.Theme;
import com.adminmod.client.net.AdminClientNetworking;
import com.adminmod.common.net.AdminProtocol;

import net.minecraft.client.gui.DrawContext;

/**
 * Authentication, connection information and GUI preferences.
 *
 * <p>The password lives only in this screen's text field and in the in-memory session key. It is
 * never written to disk on the client. If the server is configured to reveal the generated
 * password to operators, it is shown here read-only after a successful handshake.
 */
public final class SettingsTab extends DashboardTab {

    private DarkTextField passwordField;
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

        passwordField = new DarkTextField(formX, y + 60, formWidth, 20,
                net.minecraft.text.Text.of("Operator password"));
        passwordField.setMaxLength(128);
        screen.addWidget(passwordField);

        screen.addWidget(FlatButton.primary(formX, y + 86, 120, 20, "Authenticate",
                button -> authenticate()));
        screen.addWidget(FlatButton.secondary(formX + 126, y + 86, 120, 20, "Disconnect",
                button -> AdminClientNetworking.get().disconnect()));
    }

    private void authenticate() {
        char[] password = passwordField.getText().toCharArray();
        AdminClientNetworking.get().connect(password);
        java.util.Arrays.fill(password, '\0');
    }

    @Override
    public void tick() {
        AdminClientNetworking net = AdminClientNetworking.get();
        if (net.isReady() && revealed.isEmpty() && !net.serverVersion().isEmpty()) {
            // The credential arrives with the handshake result; it is kept in the session only.
            revealed = net.lastRevealedCredential();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        AdminClientNetworking net = AdminClientNetworking.get();

        panel(context, x, y, width, 120);
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
        if (!net.lastError().isEmpty()) {
            clippedText(context, net.lastError(), x + 12, y + 104, width - 24, Theme.DANGER);
        }

        int logY = y + 132;
        int logHeight = height - 132;
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
}
