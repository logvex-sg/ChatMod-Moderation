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
 * Live server health and ADMIN's own overhead.
 *
 * <p>The numbers come from the server's {@code PerformanceMonitor}; nothing is sampled on the
 * client. The tab also exposes the optimisation profile switch and the report export, so an
 * operator diagnosing a lag spike does not have to edit the configuration file.
 */
public final class DiagnosticsTab extends DashboardTab {

    private static final long REFRESH_INTERVAL_MILLIS = 2_000L;

    private record Event(long at, String category, String actor, String subject, String detail) {
    }

    private final List<Event> events = new ArrayList<>();
    private final List<String> errorComponents = new ArrayList<>();

    private String profile = "?";
    private long uptimeMillis;
    private double mspt;
    private double tps;
    private double adminMspt;
    private int players;
    private int maxPlayers;
    private int entities;
    private int chunks;
    private double heapUsedMb;
    private double heapMaxMb;
    private long errors;
    private boolean seriousErrors;
    private int bridgeQueue;
    private long bridgeDelivered;
    private long bridgeFailed;
    private long punishments;
    private String reportPath = "";
    private long lastRefresh;
    private String status = "Refresh to load the diagnostics";

    public DiagnosticsTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Diagnostics";
    }

    @Override
    public String subtitle() {
        return "Server health, ADMIN overhead and recent events";
    }

    @Override
    public void init() {
        screen.addWidget(FlatButton.primary(x, y + height - 30, 120, 20, "Refresh", button -> refresh()));
        screen.addWidget(FlatButton.secondary(x + 126, y + height - 30, 130, 20, "Performance",
                button -> setProfile("performance")));
        screen.addWidget(FlatButton.secondary(x + 262, y + height - 30, 110, 20, "Balanced",
                button -> setProfile("balanced")));
        screen.addWidget(FlatButton.secondary(x + 378, y + height - 30, 110, 20, "Maximum",
                button -> setProfile("maximum")));
        screen.addWidget(FlatButton.secondary(x + 494, y + height - 30, 160, 20, "Export report",
                button -> exportReport()));
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
        AdminClientNetworking.get().send(OrderType.DIAGNOSTICS_STATUS, this::onStatus);
        AdminClientNetworking.get().send(OrderType.DIAGNOSTICS_HISTORY, this::onHistory);
    }

    private void setProfile(String name) {
        AdminClientNetworking.get().send(OrderType.SET_PROFILE, result -> {
            status = result.message();
            if (result.success()) {
                screen.notifySuccess("Profile set to " + name);
                refresh();
            } else {
                screen.notifyError(result.message());
            }
        }, name);
    }

    private void exportReport() {
        AdminClientNetworking.get().send(OrderType.EXPORT_REPORT, result -> {
            if (result.success()) {
                JsonObject root = Json.parseObject(result.json());
                reportPath = Json.str(root, "name", "report");
                screen.notifySuccess("Report written: " + reportPath);
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
        profile = Json.str(root, "profile", "?");
        uptimeMillis = Json.lng(root, "uptimeMillis", 0L);
        mspt = Json.dbl(root, "mspt", 0.0);
        tps = Json.dbl(root, "tps", 0.0);
        adminMspt = Json.dbl(root, "adminMspt", 0.0);
        players = Json.integer(root, "players", 0);
        maxPlayers = Json.integer(root, "maxPlayers", 0);
        entities = Json.integer(root, "entities", 0);
        chunks = Json.integer(root, "chunks", 0);
        heapUsedMb = Json.dbl(root, "heapUsedMb", 0.0);
        heapMaxMb = Json.dbl(root, "heapMaxMb", 0.0);
        errors = Json.lng(root, "errors", 0L);
        seriousErrors = Json.bool(root, "seriousErrors", false);
        bridgeQueue = Json.integer(root, "bridgeQueue", 0);
        bridgeDelivered = Json.lng(root, "bridgeDelivered", 0L);
        bridgeFailed = Json.lng(root, "bridgeFailed", 0L);
        punishments = Json.lng(root, "punishments", 0L);

        errorComponents.clear();
        JsonArray components = root.getAsJsonArray("errorComponents");
        if (components != null) {
            for (JsonElement element : components) {
                JsonObject object = element.getAsJsonObject();
                errorComponents.add(Json.str(object, "component", "?") + " × "
                        + Json.lng(object, "count", 0L));
            }
        }
        status = "Updated " + java.time.LocalTime.now().withNano(0);
    }

    private void onHistory(OrderResultMessage result) {
        if (!result.success()) {
            return;
        }
        events.clear();
        JsonObject root = Json.parseObject(result.json());
        JsonArray array = root.getAsJsonArray("events");
        if (array == null) {
            return;
        }
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            events.add(new Event(Json.lng(object, "at", 0L), Json.str(object, "category", ""),
                    Json.str(object, "actor", ""), Json.str(object, "subject", ""),
                    Json.str(object, "detail", "")));
            if (events.size() >= 40) {
                break;
            }
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cardWidth = (width - 12) / 2;
        panel(context, x, y, cardWidth, 88);
        text(context, "Performance", x + 8, y + 6, Theme.TEXT_DIM);
        text(context, "MSPT " + fmt(mspt) + "   TPS " + fmt(tps), x + 8, y + 24,
                tps >= 19.0 ? Theme.SUCCESS : tps >= 15.0 ? Theme.WARNING : Theme.DANGER);
        text(context, "ADMIN tick cost " + fmt(adminMspt) + " ms", x + 8, y + 38, Theme.TEXT_DIM);
        text(context, "profile: " + profile, x + 8, y + 52, Theme.TEXT);
        text(context, "players " + players + "/" + maxPlayers + "   entities " + entities,
                x + 8, y + 66, Theme.TEXT_DIM);
        text(context, "chunks " + chunks + "   uptime " + duration(uptimeMillis), x + 8, y + 78,
                Theme.TEXT_DIM);

        panel(context, x + cardWidth + 12, y, cardWidth, 88);
        text(context, "Memory and errors", x + cardWidth + 20, y + 6, Theme.TEXT_DIM);
        double heapRatio = heapMaxMb <= 0 ? 0 : heapUsedMb / heapMaxMb;
        text(context, "heap " + fmt(heapUsedMb) + " / " + fmt(heapMaxMb) + " MB", x + cardWidth + 20, y + 24,
                heapRatio > 0.9 ? Theme.DANGER : heapRatio > 0.75 ? Theme.WARNING : Theme.TEXT);
        text(context, "errors " + errors + (seriousErrors ? " (serious)" : ""), x + cardWidth + 20, y + 38,
                seriousErrors ? Theme.DANGER : errors > 0 ? Theme.WARNING : Theme.SUCCESS);
        text(context, "bridge queue " + bridgeQueue + "   delivered " + bridgeDelivered,
                x + cardWidth + 20, y + 52, Theme.TEXT_DIM);
        text(context, "bridge failed " + bridgeFailed + "   punishments " + punishments,
                x + cardWidth + 20, y + 66, bridgeFailed > 0 ? Theme.WARNING : Theme.TEXT_DIM);
        if (!reportPath.isBlank()) {
            clippedText(context, "last report: " + reportPath, x + cardWidth + 20, y + 78, cardWidth - 28,
                    Theme.TEXT_MUTED);
        }

        int listY = y + 100;
        int listHeight = height - 140;
        panel(context, x, listY, width, listHeight);
        text(context, "Recent events", x + 8, listY + 6, Theme.TEXT_DIM);
        if (events.isEmpty()) {
            text(context, "no events recorded yet", x + 8, listY + 22, Theme.TEXT_MUTED);
        }
        int rowY = listY + 22;
        for (Event event : events) {
            if (rowY + 11 > listY + listHeight - 4) {
                break;
            }
            int color = switch (event.category()) {
                case "alert" -> Theme.WARNING;
                case "moderation" -> Theme.DANGER;
                case "staff" -> Theme.INFO;
                default -> Theme.TEXT_DIM;
            };
            String line = java.time.Instant.ofEpochMilli(event.at()).toString().substring(11, 19)
                    + "  " + event.category() + "  "
                    + (event.actor().isBlank() ? "" : event.actor() + " ")
                    + (event.subject().isBlank() ? "" : event.subject() + " ") + event.detail();
            clippedText(context, line, x + 8, rowY, width - 16, color);
            rowY += 11;
        }
        if (!errorComponents.isEmpty()) {
            text(context, "errors by component: " + String.join(", ", errorComponents),
                    x, y + height - 42, Theme.TEXT_MUTED);
        }
        text(context, status, x, y + height - 42 + (errorComponents.isEmpty() ? 0 : 12), Theme.TEXT_DIM);
    }

    private static String fmt(double value) {
        return String.format(java.util.Locale.ROOT, "%.2f", value);
    }

    private static String duration(long millis) {
        long seconds = millis / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m " + (seconds % 60L) + "s";
    }
}
