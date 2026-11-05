package com.adminmod.client.gui.tab;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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
 * Searchable view of the server audit log.
 *
 * <p>Events are read from the server's segmented, compressed store on demand; the client holds only
 * the rows it last searched for, so a long history costs nothing until it is queried. The store card
 * at the top reports real numbers (archive count, disk usage, compression ratio, dropped records)
 * taken from the live store rather than an estimate.
 *
 * <p>Fields left blank mean "any", so a bare Search browses the most recent events.
 */
public final class AuditTab extends DashboardTab {

    private static final int ROW_HEIGHT = 22;
    private static final long REFRESH_INTERVAL_MILLIS = 5_000L;

    private record Entry(long at, String category, String severity, String actor, String subject, String detail) {
    }

    private final List<Entry> entries = new ArrayList<>();
    private DarkTextField textField;
    private DarkTextField categoryField;
    private DarkTextField playerField;
    private int scrollOffset;
    private int selectedIndex = -1;
    private long lastRefresh;

    private boolean enabled;
    private String algorithm = "?";
    private boolean algorithmAvailable;
    private long archives;
    private long diskBytes;
    private double ratio;
    private double savedPercent;
    private long droppedRecords;
    private long deduplicatedRecords;
    private long pendingWrites;
    private String status = "Search to load audit events";

    public AuditTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Audit";
    }

    @Override
    public String subtitle() {
        return "Search the durable, compressed audit log";
    }

    @Override
    public void init() {
        int fieldWidth = Math.max(80, (width - 24) / 3);
        textField = new DarkTextField(x, y, fieldWidth, 20,
                net.minecraft.text.Text.of("text (player, item, block, address…)"));
        textField.setMaxLength(200);
        screen.addWidget(textField);

        categoryField = new DarkTextField(x + fieldWidth + 6, y, fieldWidth, 20,
                net.minecraft.text.Text.of("category (player, block, command…)"));
        categoryField.setMaxLength(32);
        screen.addWidget(categoryField);

        playerField = new DarkTextField(x + (fieldWidth + 6) * 2, y, fieldWidth, 20,
                net.minecraft.text.Text.of("player (name or uuid)"));
        playerField.setMaxLength(64);
        screen.addWidget(playerField);

        int buttonY = y + 24;
        screen.addWidget(FlatButton.primary(x, buttonY, 90, 20, "Search", button -> search()));
        screen.addWidget(FlatButton.secondary(x + 96, buttonY, 90, 20, "Refresh", button -> refreshStatus()));
        screen.addWidget(FlatButton.secondary(x + 192, buttonY, 100, 20, "Export CSV",
                button -> export("csv")));
        screen.addWidget(FlatButton.secondary(x + 298, buttonY, 100, 20, "Export JSON",
                button -> export("json")));
        screen.addWidget(FlatButton.danger(x + 404, buttonY, 110, 20, "Compress now",
                button -> compress()));
        screen.addWidget(FlatButton.secondary(x + 520, buttonY, 90, 20, "Archives",
                button -> listArchives()));
    }

    @Override
    public void onShown() {
        refreshStatus();
        search();
    }

    @Override
    public void tick() {
        if (AdminClientNetworking.get().isReady()
                && System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MILLIS) {
            refreshStatus();
        }
    }

    private void search() {
        lastRefresh = System.currentTimeMillis();
        // Argument order matches AuditQuery: player, category, minSeverity, from, to, item, block,
        // position, container, text, limit. A blank field means "any".
        AdminClientNetworking.get().send(OrderType.AUDIT_SEARCH, this::onSearch,
                playerField == null ? "" : playerField.getText().trim(),
                categoryField == null ? "" : categoryField.getText().trim(),
                "",
                "", "",
                "", "", "", "",
                textField == null ? "" : textField.getText().trim(),
                "");
    }

    private void refreshStatus() {
        lastRefresh = System.currentTimeMillis();
        AdminClientNetworking.get().send(OrderType.AUDIT_STATUS, this::onStatus);
    }

    private void export(String format) {
        AdminClientNetworking.get().send(OrderType.AUDIT_EXPORT, result -> {
            if (result.success()) {
                screen.notifySuccess("Audit exported");
                status = result.message();
            } else {
                screen.notifyError(result.message());
            }
        }, format,
                playerField == null ? "" : playerField.getText().trim(),
                categoryField == null ? "" : categoryField.getText().trim(),
                textField == null ? "" : textField.getText().trim());
    }

    private void compress() {
        AdminClientNetworking.get().send(OrderType.AUDIT_COMPRESS, result -> {
            if (result.success()) {
                screen.notifySuccess("Compression requested");
                refreshStatus();
            } else {
                screen.notifyError(result.message());
            }
        });
    }

    private void listArchives() {
        AdminClientNetworking.get().send(OrderType.AUDIT_ARCHIVES, result -> {
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            JsonArray array = Json.parseObject(result.json()).getAsJsonArray("archives");
            int count = array == null ? 0 : array.size();
            status = count == 0 ? "No archives yet; nothing is old enough to compress"
                    : count + " archive" + (count == 1 ? "" : "s") + ", newest first";
            screen.notifySuccess(count + " archive" + (count == 1 ? "" : "s"));
        });
    }

    private void onSearch(OrderResultMessage result) {
        if (!result.success()) {
            status = result.message();
            return;
        }
        entries.clear();
        selectedIndex = -1;
        scrollOffset = 0;
        JsonObject root = Json.parseObject(result.json());
        JsonArray array = root.getAsJsonArray("events");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                entries.add(new Entry(Json.lng(object, "at", 0L),
                        Json.str(object, "category", "?").toUpperCase(Locale.ROOT),
                        Json.str(object, "severity", "info").toUpperCase(Locale.ROOT),
                        Json.str(object, "actor", ""),
                        Json.str(object, "subject", ""),
                        Json.str(object, "detail", "")));
            }
        }
        status = entries.size() + " event" + (entries.size() == 1 ? "" : "s") + " matched";
    }

    private void onStatus(OrderResultMessage result) {
        if (!result.success()) {
            return;
        }
        JsonObject root = Json.parseObject(result.json());
        enabled = Json.bool(root, "enabled", false);
        algorithm = Json.str(root, "algorithm", "?");
        algorithmAvailable = Json.bool(root, "algorithmAvailable", false);
        archives = Json.lng(root, "archives", 0L);
        diskBytes = Json.lng(root, "diskBytes", 0L);
        ratio = Json.dbl(root, "ratio", 0.0);
        savedPercent = Json.dbl(root, "savedPercent", 0.0);
        droppedRecords = Json.lng(root, "droppedRecords", 0L);
        deduplicatedRecords = Json.lng(root, "deduplicatedRecords", 0L);
        pendingWrites = Json.lng(root, "pendingWrites", 0L);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int cardTop = y + 50;
        panel(context, x, cardTop, width, 46);
        statusDot(context, x + 8, cardTop + 8, enabled ? Theme.SUCCESS : Theme.TEXT_MUTED);
        text(context, enabled ? "audit log active" : "audit log disabled", x + 20, cardTop + 7,
                enabled ? Theme.SUCCESS : Theme.TEXT_MUTED);
        text(context, "codec: " + algorithm + (algorithmAvailable ? "" : " (unavailable, falling back)")
                + "   archives: " + archives + "   disk: " + humanBytes(diskBytes),
                x + 8, cardTop + 22, Theme.TEXT_DIM);
        text(context, "compression ratio " + String.format(Locale.ROOT, "%.2f", ratio)
                + "x  (" + String.format(Locale.ROOT, "%.1f", savedPercent) + "% saved)"
                + "   dropped: " + droppedRecords + "   deduped: " + deduplicatedRecords
                + "   pending: " + pendingWrites,
                x + 8, cardTop + 34, droppedRecords > 0 ? Theme.WARNING : Theme.TEXT_DIM);

        int listY = cardTop + 52;
        int listHeight = height - (listY - y) - 16;
        panel(context, x, listY, width, listHeight);

        int visibleRows = Math.max(1, (listHeight - 8) / ROW_HEIGHT);
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        if (entries.isEmpty()) {
            emptyState(context, "No audit events match");
        }

        int rowY = listY + 4;
        for (int i = scrollOffset; i < entries.size() && rowY + ROW_HEIGHT <= listY + listHeight - 4; i++) {
            Entry entry = entries.get(i);
            boolean hovered = hovering(mouseX, mouseY, x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT);
            if (i == selectedIndex) {
                context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, Theme.ROW_SELECTED);
            } else if (hovered) {
                context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, Theme.ROW_HOVER);
            }

            int severityColor = severityColor(entry.severity());
            context.fill(x + 1, rowY, x + 3, rowY + ROW_HEIGHT, severityColor);
            String time = entry.at() == 0L ? "--:--:--"
                    : Instant.ofEpochMilli(entry.at()).toString().substring(11, 19);
            text(context, time, x + 8, rowY + 3, Theme.TEXT_MUTED);
            text(context, entry.category(), x + 62, rowY + 3, Theme.TEXT_DIM);

            String who = entry.actor().isEmpty() ? entry.subject() : entry.actor();
            text(context, who, x + 132, rowY + 3, Theme.TEXT);
            clippedText(context, entry.detail(), x + 232, rowY + 3, width - 240, Theme.TEXT_DIM);

            final int index = i;
            hotZone(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, () -> selectedIndex = index);
            rowY += ROW_HEIGHT;
        }

        String detail = selectedIndex >= 0 && selectedIndex < entries.size()
                ? entries.get(selectedIndex).detail() : status;
        clippedText(context, detail, x, y + height - 12, width, Theme.TEXT_DIM);
    }

    private static int severityColor(String severity) {
        return switch (severity) {
            case "HIGH", "CRITICAL" -> Theme.DANGER;
            case "WARNING" -> Theme.WARNING;
            case "NOTICE" -> Theme.INFO;
            default -> Theme.ACCENT_DIM;
        };
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024L * 1024L) {
            return String.format(Locale.ROOT, "%.1f MiB", bytes / (1024.0 * 1024.0));
        }
        return String.format(Locale.ROOT, "%.2f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int listY = y + 50 + 52;
        int listHeight = height - (listY - y) - 16;
        int visibleRows = Math.max(1, (listHeight - 8) / ROW_HEIGHT);
        int maxScroll = Math.max(0, entries.size() - visibleRows);
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - vertical * 2));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return clickHotZones(mouseX, mouseY);
    }
}
