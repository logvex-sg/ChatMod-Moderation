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
 * Anti-crash and abuse protection status.
 *
 * <p>Shows the live thresholds, how many triggers were recorded and the most recent ones, so an
 * operator can tell a genuine attack from a false positive without reading the server log. The
 * counters can be cleared once they have been reviewed.
 */
public final class ProtectionTab extends DashboardTab {

    private static final long REFRESH_INTERVAL_MILLIS = 3_000L;

    private record Trigger(long at, String kind, String subject, String detail) {
    }

    private final List<Trigger> triggers = new ArrayList<>();
    private boolean enabled;
    private String profile = "?";
    private long violations;
    private int trackedAddresses;
    private int restrictedAddresses;
    private int trackedPackets;
    private int maxChatLength;
    private int maxPayloadBytes;
    private int chatPerMinute;
    private int commandsPerMinute;
    private int packetsPerSecond;
    private int packetsPerTenSeconds;
    private int inventoryClicksPerMinute;
    private int entityInteractionsPerMinute;
    private int violationsBeforeKick;
    private long lastRefresh;
    private String status = "Refresh to load the protection status";

    public ProtectionTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Protection";
    }

    @Override
    public String subtitle() {
        return "Anti-crash thresholds, triggers and counters";
    }

    @Override
    public void init() {
        screen.addWidget(FlatButton.primary(x, y + height - 30, 120, 20, "Refresh", button -> refresh()));
        screen.addWidget(FlatButton.danger(x + 126, y + height - 30, 180, 20, "Clear counters",
                button -> clear()));
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
        AdminClientNetworking.get().send(OrderType.PROTECTION_STATUS, this::onStatus);
    }

    private void clear() {
        AdminClientNetworking.get().send(OrderType.CLEAR_VIOLATIONS, result -> {
            if (result.success()) {
                screen.notifySuccess("Protection counters cleared");
                refresh();
            } else {
                screen.notifyError(result.message());
            }
        });
    }

    private void onStatus(OrderResultMessage result) {
        if (!result.success()) {
            status = result.message();
            return;
        }
        JsonObject root = Json.parseObject(result.json());
        enabled = Json.bool(root, "enabled", false);
        profile = Json.str(root, "profile", "?");
        violations = Json.lng(root, "violations", 0L);
        trackedAddresses = Json.integer(root, "trackedAddresses", 0);
        restrictedAddresses = Json.integer(root, "restrictedAddresses", 0);
        trackedPackets = Json.integer(root, "trackedPackets", 0);

        JsonObject thresholds = root.has("thresholds") && root.get("thresholds").isJsonObject()
                ? root.getAsJsonObject("thresholds") : new JsonObject();
        maxChatLength = Json.integer(thresholds, "maxChatLength", 0);
        maxPayloadBytes = Json.integer(thresholds, "maxPayloadBytes", 0);
        chatPerMinute = Json.integer(thresholds, "chatPerMinute", 0);
        commandsPerMinute = Json.integer(thresholds, "commandsPerMinute", 0);
        packetsPerSecond = Json.integer(thresholds, "packetsPerSecond", 0);
        packetsPerTenSeconds = Json.integer(thresholds, "packetsPerTenSeconds", 0);
        inventoryClicksPerMinute = Json.integer(thresholds, "inventoryClicksPerMinute", 0);
        entityInteractionsPerMinute = Json.integer(thresholds, "entityInteractionsPerMinute", 0);
        violationsBeforeKick = Json.integer(thresholds, "violationsBeforeKick", 0);

        triggers.clear();
        JsonArray array = root.getAsJsonArray("recent");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                triggers.add(new Trigger(Json.lng(object, "at", 0L), Json.str(object, "kind", "?"),
                        Json.str(object, "subject", ""), Json.str(object, "detail", "")));
            }
        }
        status = "Updated " + java.time.LocalTime.now().withNano(0);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cardWidth = (width - 12) / 2;
        panel(context, x, y, cardWidth, 92);
        text(context, "Status", x + 8, y + 6, Theme.TEXT_DIM);
        statusDot(context, x + 8, y + 22, enabled ? Theme.SUCCESS : Theme.DANGER);
        text(context, enabled ? "protection enabled" : "protection disabled", x + 20, y + 21,
                enabled ? Theme.SUCCESS : Theme.DANGER);
        text(context, "profile: " + profile, x + 8, y + 36, Theme.TEXT);
        text(context, "triggers recorded: " + violations, x + 8, y + 50,
                violations > 0 ? Theme.WARNING : Theme.TEXT_DIM);
        text(context, "tracked addresses: " + trackedAddresses + "   restricted: " + restrictedAddresses,
                x + 8, y + 64, restrictedAddresses > 0 ? Theme.WARNING : Theme.TEXT_DIM);
        text(context, "players under packet watch: " + trackedPackets, x + 8, y + 78, Theme.TEXT_DIM);

        panel(context, x + cardWidth + 12, y, cardWidth, 92);
        text(context, "Thresholds", x + cardWidth + 20, y + 6, Theme.TEXT_DIM);
        text(context, "chat " + chatPerMinute + "/min   commands " + commandsPerMinute + "/min",
                x + cardWidth + 20, y + 22, Theme.TEXT_DIM);
        text(context, "packets " + packetsPerSecond + "/s and " + packetsPerTenSeconds + "/10s",
                x + cardWidth + 20, y + 34, Theme.TEXT_DIM);
        text(context, "clicks " + inventoryClicksPerMinute + "/min   interactions "
                + entityInteractionsPerMinute + "/min", x + cardWidth + 20, y + 46, Theme.TEXT_DIM);
        text(context, "max chat length " + maxChatLength, x + cardWidth + 20, y + 58, Theme.TEXT_DIM);
        text(context, "max payload " + maxPayloadBytes + " bytes", x + cardWidth + 20, y + 70, Theme.TEXT_DIM);
        text(context, "kick after " + (violationsBeforeKick == 0 ? "never" : violationsBeforeKick + " violations"),
                x + cardWidth + 20, y + 82, Theme.TEXT_DIM);

        int listY = y + 104;
        int listHeight = height - 144;
        panel(context, x, listY, width, listHeight);
        text(context, "Recent triggers", x + 8, listY + 6, Theme.TEXT_DIM);
        if (triggers.isEmpty()) {
            text(context, "no triggers recorded", x + 8, listY + 22, Theme.TEXT_MUTED);
        }
        int rowY = listY + 22;
        for (Trigger trigger : triggers) {
            if (rowY + 11 > listY + listHeight - 4) {
                break;
            }
            String line = java.time.Instant.ofEpochMilli(trigger.at()).toString().substring(11, 19)
                    + "  " + trigger.kind() + "  " + trigger.subject() + "  " + trigger.detail();
            clippedText(context, line, x + 8, rowY, width - 16, Theme.WARNING);
            rowY += 11;
        }
        text(context, status, x, y + height - 42, Theme.TEXT_DIM);
    }
}
