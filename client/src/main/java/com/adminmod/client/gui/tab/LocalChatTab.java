package com.adminmod.client.gui.tab;

import java.util.ArrayList;
import java.util.List;

import com.adminmod.client.gui.AdminDashboardScreen;
import com.adminmod.client.gui.DarkTextField;
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
 * Proximity chat settings and counters.
 *
 * <p>Reads the live state from the server and edits it through the same authenticated order channel
 * as every other tab. The radius and prefix are edited in text fields; the toggles are buttons. Each
 * change is written server-side and the panel refreshes from the answer, so what is shown is always
 * what the server actually applied.
 */
public final class LocalChatTab extends DashboardTab {

    private static final long REFRESH_INTERVAL_MILLIS = 4_000L;

    private record DimensionRule(String dimension, boolean enabled, double radius) {
    }

    private final List<DimensionRule> dimensions = new ArrayList<>();

    private DarkTextField radiusField;
    private DarkTextField prefixField;

    private boolean enabled;
    private boolean globalReachesAllDimensions = true;
    private boolean staffBypass = true;
    private boolean soundEnabled;
    private boolean showRecipientCount;
    private boolean filterMessages = true;
    private boolean forwardToBridge = true;
    private double radius = 50.0;
    private int staffLevel = 2;
    private long cooldownMillis = 750L;
    private String globalPrefix = "!";
    private String localFormat = "";
    private String globalFormat = "";
    private String soundId = "";
    private long localCount;
    private long globalCount;
    private long blockedCount;
    private long throttledCount;
    private long mutedCount;
    private long lastRefresh;
    private String status = "Refresh to load the proximity settings";

    public LocalChatTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Local Chat";
    }

    @Override
    public String subtitle() {
        return "Proximity chat radius, channels and counters";
    }

    @Override
    public void init() {
        int fieldWidth = Math.max(80, Math.min(140, (width - 40) / 3));
        radiusField = new DarkTextField(x + 64, y + 30, fieldWidth, 20,
                net.minecraft.text.Text.of("50"));
        radiusField.setMaxLength(8);
        radiusField.setText(String.valueOf(Math.round(radius)));
        screen.addWidget(radiusField);

        prefixField = new DarkTextField(x + 64 + fieldWidth + 70, y + 30, Math.max(40, fieldWidth - 40), 20,
                net.minecraft.text.Text.of("!"));
        prefixField.setMaxLength(8);
        prefixField.setText(globalPrefix);
        screen.addWidget(prefixField);

        int buttonY = y + height - 30;
        int buttonWidth = Math.max(96, (width - 18) / 4);
        screen.addWidget(FlatButton.primary(x, buttonY, buttonWidth, 20, "Refresh", button -> refresh()));
        screen.addWidget(FlatButton.secondary(x + (buttonWidth + 6), buttonY, buttonWidth, 20, "Apply radius",
                button -> apply("localchat.radius", radiusField.getText().trim())));
        screen.addWidget(FlatButton.secondary(x + 2 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Apply prefix",
                button -> apply("localchat.globalprefix", prefixField.getText())));
        screen.addWidget(FlatButton.secondary(x + 3 * (buttonWidth + 6), buttonY, buttonWidth, 20,
                enabled ? "Disable" : "Enable", button -> apply("localchat.enabled", Boolean.toString(!enabled))));
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
        AdminClientNetworking.get().send(OrderType.LOCAL_CHAT_STATUS, this::onStatus);
    }

    private void apply(String key, String value) {
        if (value == null || value.isBlank()) {
            screen.notifyError("Enter a value first");
            return;
        }
        AdminClientNetworking.get().send(OrderType.UPDATE_LOCAL_CHAT, result -> {
            status = result.message();
            if (result.success()) {
                onStatus(result);
            } else {
                screen.notifyError(result.message());
            }
        }, key, value);
    }

    private void onStatus(OrderResultMessage result) {
        if (!result.success()) {
            status = result.message();
            return;
        }
        JsonObject root = Json.parseObject(result.json());
        enabled = Json.bool(root, "enabled", false);
        radius = root.has("radius") ? root.get("radius").getAsDouble() : 50.0;
        globalPrefix = Json.str(root, "globalPrefix", "!");
        localFormat = Json.str(root, "localFormat", "");
        globalFormat = Json.str(root, "globalFormat", "");
        globalReachesAllDimensions = Json.bool(root, "globalReachesAllDimensions", true);
        staffBypass = Json.bool(root, "staffBypass", true);
        staffLevel = Json.integer(root, "staffLevel", 2);
        soundEnabled = Json.bool(root, "soundEnabled", false);
        soundId = Json.str(root, "soundId", "");
        cooldownMillis = Json.lng(root, "cooldownMillis", 750L);
        showRecipientCount = Json.bool(root, "showRecipientCount", false);
        filterMessages = Json.bool(root, "filterMessages", true);
        forwardToBridge = Json.bool(root, "forwardToBridge", true);
        localCount = Json.lng(root, "localCount", 0L);
        globalCount = Json.lng(root, "globalCount", 0L);
        blockedCount = Json.lng(root, "blockedCount", 0L);
        throttledCount = Json.lng(root, "throttledCount", 0L);
        mutedCount = Json.lng(root, "mutedCount", 0L);

        dimensions.clear();
        JsonArray array = root.getAsJsonArray("dimensions");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                dimensions.add(new DimensionRule(Json.str(object, "dimension", "?"),
                        Json.bool(object, "enabled", true),
                        object.has("radius") ? object.get("radius").getAsDouble() : 0));
            }
        }
        if (radiusField != null) {
            radiusField.setText(String.valueOf(Math.round(radius)));
        }
        if (prefixField != null) {
            prefixField.setText(globalPrefix);
        }
        status = "Updated " + java.time.LocalTime.now().withNano(0);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The toggles below are drawn rather than built from widgets, so their click handling is
        // hit-tested here against the regions registered during the last frame.
        if (clickHotZones(mouseX, mouseY)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        panel(context, x, y, width, 96);
        text(context, "Proximity", x + 8, y + 8, Theme.TEXT);
        statusDot(context, x + 8, y + 24, enabled ? Theme.SUCCESS : Theme.DANGER);
        text(context, enabled ? "enabled" : "disabled", x + 20, y + 23,
                enabled ? Theme.SUCCESS : Theme.DANGER);
        text(context, "Radius (blocks):", x + 8, y + 36, Theme.TEXT_DIM);
        text(context, "Global prefix:", x + 64 + Math.max(80, Math.min(140, (width - 40) / 3)) + 12, y + 36,
                Theme.TEXT_DIM);
        text(context, "local " + localCount + "   global " + globalCount, x + 8, y + 60, Theme.TEXT);
        text(context, "blocked " + blockedCount + "   throttled " + throttledCount + "   muted " + mutedCount,
                x + 8, y + 72, blockedCount + throttledCount + mutedCount > 0 ? Theme.WARNING : Theme.TEXT_DIM);
        clippedText(context, "local format: " + localFormat, x + 8, y + 84, width - 16, Theme.TEXT_MUTED);

        int toggleY = y + 102;
        int columnWidth = (width - 12) / 2;
        panel(context, x, toggleY, columnWidth, 74);
        text(context, "Delivery", x + 8, toggleY + 6, Theme.TEXT_DIM);
        toggle(context, mouseX, mouseY, x + 8, toggleY + 22, columnWidth - 16,
                "Global reaches all dimensions", globalReachesAllDimensions,
                () -> apply("localchat.globalreachesalldimensions",
                        Boolean.toString(!globalReachesAllDimensions)));
        toggle(context, mouseX, mouseY, x + 8, toggleY + 38, columnWidth - 16,
                "Staff bypass radius", staffBypass,
                () -> apply("localchat.staffbypass", Boolean.toString(!staffBypass)));
        toggle(context, mouseX, mouseY, x + 8, toggleY + 54, columnWidth - 16,
                "Filter messages", filterMessages,
                () -> apply("localchat.filtermessages", Boolean.toString(!filterMessages)));

        int rightX = x + columnWidth + 12;
        panel(context, rightX, toggleY, columnWidth, 74);
        text(context, "Extras", rightX + 8, toggleY + 6, Theme.TEXT_DIM);
        toggle(context, mouseX, mouseY, rightX + 8, toggleY + 22, columnWidth - 16,
                "Sound on receipt", soundEnabled,
                () -> apply("localchat.soundenabled", Boolean.toString(!soundEnabled)));
        toggle(context, mouseX, mouseY, rightX + 8, toggleY + 38, columnWidth - 16,
                "Show recipient count", showRecipientCount,
                () -> apply("localchat.showrecipientcount", Boolean.toString(!showRecipientCount)));
        toggle(context, mouseX, mouseY, rightX + 8, toggleY + 54, columnWidth - 16,
                "Forward to chat bridge", forwardToBridge,
                () -> apply("localchat.forwardtobridge", Boolean.toString(!forwardToBridge)));

        int infoY = toggleY + 82;
        panel(context, x, infoY, width, Math.max(30, y + height - 44 - infoY));
        text(context, "Configuration", x + 8, infoY + 6, Theme.TEXT_DIM);
        text(context, "staff level " + staffLevel + "   cooldown " + cooldownMillis + "ms"
                + "   sound " + (soundId.isEmpty() ? "none" : soundId), x + 8, infoY + 22, Theme.TEXT_DIM);
        clippedText(context, "global format: " + globalFormat, x + 8, infoY + 34, width - 16, Theme.TEXT_MUTED);
        int dimY = infoY + 50;
        if (dimensions.isEmpty()) {
            text(context, "no per-dimension overrides", x + 8, dimY, Theme.TEXT_MUTED);
        } else {
            text(context, "Per-dimension overrides:", x + 8, dimY, Theme.TEXT_DIM);
            dimY += 12;
            for (DimensionRule rule : dimensions) {
                if (dimY > y + height - 46) {
                    break;
                }
                text(context, rule.dimension() + "  " + (rule.enabled() ? "on" : "off")
                        + (rule.radius() > 0 ? "  radius " + Math.round(rule.radius()) : ""),
                        x + 12, dimY, Theme.TEXT);
                dimY += 12;
            }
        }
        text(context, status, x, y + height - 42, Theme.TEXT_DIM);
    }

    private void toggle(DrawContext context, int mouseX, int mouseY, int tx, int ty, int maxWidth,
            String label, boolean value, Runnable action) {
        boolean hovered = hovering(mouseX, mouseY, tx, ty, tx + maxWidth, ty + 12);
        if (hovered) {
            context.fill(tx - 2, ty - 1, tx + maxWidth, ty + 13, Theme.ROW_HOVER);
        }
        text(context, (value ? "[x] " : "[ ] ") + label, tx, ty, value ? Theme.SUCCESS : Theme.TEXT_DIM);
        hotZone(tx, ty, tx + maxWidth, ty + 14, action);
    }
}
