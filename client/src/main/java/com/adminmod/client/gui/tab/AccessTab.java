package com.adminmod.client.gui.tab;

import java.util.ArrayList;
import java.util.List;

import com.adminmod.client.gui.AdminDashboardScreen;
import com.adminmod.client.gui.FlatButton;
import com.adminmod.client.gui.Theme;
import com.adminmod.client.net.AdminClientNetworking;
import com.adminmod.common.model.Json;
import com.adminmod.common.net.OrderResultMessage;
import com.adminmod.common.net.OrderType;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.gui.DrawContext;

/**
 * Access management: paired clients, live sessions and the authentication history.
 *
 * <p>This is where an operator mints a one-time pairing key for a new machine, revokes a machine
 * that should no longer connect, and reads the audit trail. The key is shown once, right after it is
 * minted, because only its verifier is stored on the server and it cannot be recovered afterwards.
 */
public final class AccessTab extends DashboardTab {

    private static final int ROW_HEIGHT = 18;

    private final List<String[]> clients = new ArrayList<>();
    private final List<String[]> sessions = new ArrayList<>();
    private final List<String[]> events = new ArrayList<>();

    private String status = "Ready";
    private String issuedKey = "";
    private long issuedExpiresAt;

    public AccessTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Access";
    }

    @Override
    public String subtitle() {
        return "Pairing keys, paired clients and sessions";
    }

    @Override
    public void init() {
        int buttonY = y + 4;
        int buttonWidth = Math.max(84, (width - 18) / 3);
        screen.addWidget(FlatButton.primary(x, buttonY, buttonWidth, 20, "Generate pairing key",
                button -> generate()));
        screen.addWidget(FlatButton.secondary(x + (buttonWidth + 6), buttonY, buttonWidth, 20, "Refresh",
                button -> refresh()));
        screen.addWidget(FlatButton.secondary(x + 2 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Rotate password",
                button -> rotatePassword()));
    }

    @Override
    public void onShown() {
        refresh();
    }

    private void refresh() {
        AdminClientNetworking.get().send(OrderType.PAIRING_LIST, this::onClients);
        AdminClientNetworking.get().send(OrderType.SESSION_LIST, this::onSessions);
        AdminClientNetworking.get().send(OrderType.AUTH_HISTORY, this::onHistory);
    }

    private void generate() {
        AdminClientNetworking.get().send(OrderType.PAIRING_GENERATE, result -> {
            status = result.message();
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            JsonObject root = Json.parseObject(result.json());
            issuedKey = Json.str(root, "key", "");
            issuedExpiresAt = Json.lng(root, "expiresAt", 0L);
            AdminClientNetworking.get().noteIssuedPairingKey(issuedKey);
            screen.notifySuccess("Pairing key generated");
            refresh();
        }, "panel");
    }

    private void rotatePassword() {
        AdminClientNetworking.get().send(OrderType.CREDENTIAL_ROTATE, result -> {
            status = result.message();
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            JsonObject root = Json.parseObject(result.json());
            issuedKey = Json.str(root, "value", "");
            issuedExpiresAt = 0L;
            screen.notifySuccess("Operator password rotated; copy it now");
        }, "password");
    }

    private void revoke(String clientId) {
        AdminClientNetworking.get().send(OrderType.PAIRING_REVOKE, result -> {
            status = result.message();
            if (result.success()) {
                screen.notifySuccess(result.message());
                refresh();
            } else {
                screen.notifyError(result.message());
            }
        }, clientId);
    }

    private void onClients(OrderResultMessage result) {
        if (!result.success()) {
            status = result.message();
            return;
        }
        clients.clear();
        JsonArray array = Json.parseObject(result.json()).getAsJsonArray("clients");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                clients.add(new String[] {
                        Json.str(object, "id", "?"),
                        Json.str(object, "label", ""),
                        Json.str(object, "ownerName", ""),
                        Json.str(object, "buildId", "")});
            }
        }
    }

    private void onSessions(OrderResultMessage result) {
        if (!result.success()) {
            return;
        }
        sessions.clear();
        JsonArray array = Json.parseObject(result.json()).getAsJsonArray("sessions");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                sessions.add(new String[] {
                        Json.str(object, "player", "?"),
                        Json.str(object, "mode", ""),
                        Json.str(object, "clientId", ""),
                        Json.bool(object, "authenticated", false) ? "authenticated" : "pending"});
            }
        }
    }

    private void onHistory(OrderResultMessage result) {
        if (!result.success()) {
            return;
        }
        events.clear();
        JsonArray array = Json.parseObject(result.json()).getAsJsonArray("events");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                events.add(new String[] {
                        Json.str(object, "actor", "?"),
                        Json.str(object, "action", ""),
                        Json.bool(object, "success", false) ? "ok" : "failed",
                        Json.str(object, "detail", "")});
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int top = y + 30;
        int half = Math.max(60, (height - 54) / 2);

        // Paired clients.
        panel(context, x, top, width, half);
        text(context, "Paired clients", x + 8, top + 6, Theme.TEXT_DIM);
        int rowY = top + 20;
        for (String[] entry : clients) {
            if (rowY + ROW_HEIGHT > top + half - 4) {
                break;
            }
            boolean hovered = hovering(mouseX, mouseY, x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT);
            context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT,
                    hovered ? Theme.ROW_HOVER : Theme.PANEL);
            clippedText(context, entry[1], x + 8, rowY + 5, width / 3, Theme.TEXT);
            clippedText(context, entry[0] + "  " + entry[2], x + 8 + width / 3, rowY + 5,
                    width / 3, Theme.TEXT_MUTED);
            String revoke = "Revoke";
            int revokeX = x + width - 8 - client().textRenderer.getWidth(revoke);
            text(context, revoke, revokeX, rowY + 5, Theme.DANGER);
            hotZone(revokeX - 4, rowY, x + width - 1, rowY + ROW_HEIGHT, () -> revoke(entry[0]));
            rowY += ROW_HEIGHT;
        }
        if (clients.isEmpty()) {
            text(context, "No clients paired yet", x + 8, top + 24, Theme.TEXT_MUTED);
        }

        // Live sessions.
        int midY = top + half + 6;
        panel(context, x, midY, width, half);
        text(context, "Active sessions", x + 8, midY + 6, Theme.TEXT_DIM);
        rowY = midY + 20;
        for (String[] entry : sessions) {
            if (rowY + ROW_HEIGHT > midY + half - 4) {
                break;
            }
            statusDot(context, x + 8, rowY + 6,
                    "authenticated".equals(entry[3]) ? Theme.SUCCESS : Theme.WARNING);
            clippedText(context, entry[0], x + 22, rowY + 5, width / 3, Theme.TEXT);
            clippedText(context, entry[1] + (entry[2].isEmpty() ? "" : " · " + entry[2]),
                    x + 22 + width / 3, rowY + 5, width / 2, Theme.TEXT_MUTED);
            rowY += ROW_HEIGHT;
        }
        if (sessions.isEmpty()) {
            text(context, "No active sessions", x + 8, midY + 24, Theme.TEXT_MUTED);
        }

        text(context, status, x, y + height - 20, Theme.TEXT_DIM);
        if (!issuedKey.isEmpty()) {
            String line = issuedExpiresAt > 0
                    ? "Pairing key (shown once): " + issuedKey
                    : "New value (shown once): " + issuedKey;
            clippedText(context, line, x, y + height - 32, width, Theme.WARNING);
        }
    }
}
