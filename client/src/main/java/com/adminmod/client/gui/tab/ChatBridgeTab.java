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
 * Chat bridge status.
 *
 * <p>Reads the live status from the server: which provider is selected, whether it is configured,
 * how many messages were delivered, failed or dropped, and the recent delivery errors. The
 * destination shown is a host, never a full webhook URL, because the URL contains a token.
 */
public final class ChatBridgeTab extends DashboardTab {

    private static final long REFRESH_INTERVAL_MILLIS = 3_000L;

    private record Integration(String id, boolean active, boolean configured, String destination) {
    }

    private record Reader(String id, boolean running, String destination, String lastError) {
    }

    private final List<Integration> integrations = new ArrayList<>();
    private final List<Reader> readers = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private boolean enabled;
    private boolean publicChatOnly;
    private boolean forwardCommands;
    private boolean forwardPrivateMessages;
    private boolean forwardJoinLeave;
    private boolean inboundEnabled;
    private boolean antiSpamEnabled;
    private long inboundReceived;
    private long inboundDisplayed;
    private long inboundDropped;
    private String provider = "?";
    private String destination = "?";
    private int queued;
    private int delivered;
    private int failed;
    private int dropped;
    private long lastRefresh;
    private String status = "Refresh to load the bridge status";

    public ChatBridgeTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Chat Bridge";
    }

    @Override
    public String subtitle() {
        return "Integrations, delivery counters and errors";
    }

    @Override
    public void init() {
        screen.addWidget(FlatButton.primary(x, y + height - 30, 120, 20, "Refresh", button -> refresh()));
        screen.addWidget(FlatButton.secondary(x + 126, y + height - 30, 160, 20, "Toggle bridge",
                button -> toggle("chatbridge.enabled", !enabled)));
        screen.addWidget(FlatButton.secondary(x + 292, y + height - 30, 200, 20, "Toggle join/leave messages",
                button -> toggle("chatbridge.forwardjoinleave", !forwardJoinLeave)));
        screen.addWidget(FlatButton.secondary(x + 498, y + height - 30, 160, 20, "Toggle anti-spam",
                button -> toggle("chatbridge.antispam", !antiSpamEnabled)));
    }

    @Override
    public void onShown() {
        refresh();
    }

    @Override
    public void tick() {
        if (AdminClientNetworking.get().isReady()
                && System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MILLIS) {
            refresh();
        }
    }

    private void refresh() {
        lastRefresh = System.currentTimeMillis();
        AdminClientNetworking.get().send(OrderType.BRIDGE_STATUS, this::onStatus);
    }

    private void toggle(String key, boolean value) {
        AdminClientNetworking.get().send(OrderType.UPDATE_SETTING, result -> {
            status = result.message();
            if (result.success()) {
                refresh();
            } else {
                screen.notifyError(result.message());
            }
        }, key, Boolean.toString(value));
    }

    private void onStatus(OrderResultMessage result) {
        if (!result.success()) {
            status = result.message();
            return;
        }
        JsonObject root = Json.parseObject(result.json());
        enabled = Json.bool(root, "enabled", false);
        provider = Json.str(root, "provider", "?");
        destination = Json.str(root, "destination", "?");
        publicChatOnly = Json.bool(root, "publicChatOnly", true);
        forwardCommands = Json.bool(root, "forwardCommands", false);
        forwardPrivateMessages = Json.bool(root, "forwardPrivateMessages", false);
        forwardJoinLeave = Json.bool(root, "forwardJoinLeave", true);
        antiSpamEnabled = Json.bool(root, "antiSpamEnabled", true);
        queued = Json.integer(root, "queued", 0);
        delivered = Json.integer(root, "delivered", 0);
        failed = Json.integer(root, "failed", 0);
        dropped = Json.integer(root, "dropped", 0);

        integrations.clear();
        JsonArray array = root.getAsJsonArray("integrations");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                integrations.add(new Integration(Json.str(object, "id", "?"), Json.bool(object, "active", false),
                        Json.bool(object, "configured", false), Json.str(object, "destination", "")));
            }
        }
        errors.clear();
        JsonArray errorArray = root.getAsJsonArray("recentErrors");
        if (errorArray != null) {
            for (JsonElement element : errorArray) {
                errors.add(element.getAsString());
            }
        }

        readers.clear();
        JsonObject inbound = root.has("inbound") && root.get("inbound").isJsonObject()
                ? root.getAsJsonObject("inbound") : null;
        if (inbound != null) {
            inboundEnabled = Json.bool(inbound, "enabled", false);
            inboundReceived = Json.lng(inbound, "received", 0L);
            inboundDisplayed = Json.lng(inbound, "displayed", 0L);
            inboundDropped = Json.lng(inbound, "dropped", 0L);
            JsonArray readerArray = inbound.getAsJsonArray("receivers");
            if (readerArray != null) {
                for (JsonElement element : readerArray) {
                    JsonObject object = element.getAsJsonObject();
                    readers.add(new Reader(Json.str(object, "id", "?"), Json.bool(object, "running", false),
                            Json.str(object, "destination", ""), Json.str(object, "lastError", "")));
                }
            }
        }
        status = "Updated " + java.time.LocalTime.now().withNano(0);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cardWidth = (width - 12) / 2;
        panel(context, x, y, cardWidth, 78);
        text(context, "Status", x + 8, y + 6, Theme.TEXT_DIM);
        statusDot(context, x + 8, y + 22, enabled ? Theme.SUCCESS : Theme.DANGER);
        text(context, enabled ? "enabled" : "disabled", x + 20, y + 21, enabled ? Theme.SUCCESS : Theme.DANGER);
        text(context, "provider: " + provider, x + 8, y + 36, Theme.TEXT);
        clippedText(context, "destination: " + destination, x + 8, y + 48, cardWidth - 16, Theme.TEXT_DIM);
        text(context, "public chat only: " + publicChatOnly, x + 8, y + 60, Theme.TEXT_DIM);

        panel(context, x + cardWidth + 12, y, cardWidth, 78);
        text(context, "Delivery", x + cardWidth + 20, y + 6, Theme.TEXT_DIM);
        text(context, "queued " + queued + "   delivered " + delivered, x + cardWidth + 20, y + 24, Theme.TEXT);
        text(context, "failed " + failed + "   dropped " + dropped, x + cardWidth + 20, y + 40,
                failed > 0 ? Theme.DANGER : Theme.TEXT_DIM);
        text(context, "commands forwarded: " + forwardCommands, x + cardWidth + 20, y + 56, Theme.TEXT_DIM);
        text(context, "private messages forwarded: " + forwardPrivateMessages, x + cardWidth + 20, y + 68,
                Theme.TEXT_DIM);

        int listY = y + 90;
        int listHeight = height - 128;
        int halfWidth = (width - 12) / 2;
        panel(context, x, listY, halfWidth, listHeight);
        text(context, "Outbound integrations", x + 8, listY + 6, Theme.TEXT_DIM);
        int rowY = listY + 20;
        for (Integration integration : integrations) {
            if (rowY + 16 > listY + listHeight - 4) {
                break;
            }
            statusDot(context, x + 8, rowY + 5,
                    integration.active() ? (integration.configured() ? Theme.SUCCESS : Theme.WARNING)
                            : Theme.TEXT_MUTED);
            text(context, integration.id(), x + 22, rowY + 4,
                    integration.active() ? Theme.TEXT : Theme.TEXT_MUTED);
            String right = integration.active()
                    ? (integration.configured() ? "configured" : "not configured") : "not selected";
            context.drawTextWithShadow(client().textRenderer, right,
                    x + halfWidth - 8 - client().textRenderer.getWidth(right), rowY + 4,
                    integration.configured() ? Theme.TEXT_DIM : Theme.TEXT_MUTED);
            rowY += 16;
        }

        int inboundX = x + halfWidth + 12;
        panel(context, inboundX, listY, halfWidth, listHeight);
        text(context, "Inbound (platform to chat)", inboundX + 8, listY + 6, Theme.TEXT_DIM);
        statusDot(context, inboundX + 8, listY + 24, inboundEnabled ? Theme.SUCCESS : Theme.TEXT_MUTED);
        text(context, inboundEnabled ? "enabled" : "disabled", inboundX + 20, listY + 23,
                inboundEnabled ? Theme.SUCCESS : Theme.TEXT_MUTED);
        text(context, "shown " + inboundDisplayed + "   received " + inboundReceived
                + "   dropped " + inboundDropped, inboundX + 8, listY + 38, Theme.TEXT_DIM);
        int readerY = listY + 56;
        if (readers.isEmpty()) {
            text(context, inboundEnabled ? "no readers registered" : "enable inbound in config",
                    inboundX + 8, readerY, Theme.TEXT_MUTED);
        }
        for (Reader reader : readers) {
            if (readerY + 24 > listY + listHeight - 4) {
                break;
            }
            statusDot(context, inboundX + 8, readerY + 5, reader.running() ? Theme.SUCCESS : Theme.WARNING);
            text(context, reader.id(), inboundX + 22, readerY + 4, Theme.TEXT);
            text(context, reader.running() ? "connected" : "connecting", inboundX + 22, readerY + 15,
                    reader.running() ? Theme.SUCCESS : Theme.WARNING);
            if (!reader.lastError().isBlank()) {
                clippedText(context, reader.lastError(), inboundX + 90, readerY + 15, halfWidth - 100,
                        Theme.DANGER);
            }
            readerY += 30;
        }

        if (!errors.isEmpty()) {
            int errorY = y + height - 40;
            text(context, "Recent outbound errors", x, errorY, Theme.DANGER);
            errorY += 12;
            for (String error : errors) {
                if (errorY > y + height - 14) {
                    break;
                }
                clippedText(context, error, x, errorY, width - 16, Theme.TEXT_DIM);
                errorY += 11;
            }
        }
        text(context, status, x, y + height - 42, Theme.TEXT_DIM);
    }
}
