package com.adminmod.client.gui.tab;

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
 * Online and offline player management.
 *
 * <p>The list refreshes on a timer rather than on every frame, so an open dashboard does not
 * generate a request storm. Selecting a player enables the actions; each action reads the reason
 * and duration from the two fields at the bottom, because the server refuses moderation without a
 * reason when {@code moderation.requireReasons} is set.
 */
public final class PlayersTab extends DashboardTab {

    private static final int ROW_HEIGHT = 18;
    private static final long REFRESH_INTERVAL_MILLIS = 3_000L;

    private record PlayerRow(String uuid, String name, boolean online, boolean operator, boolean muted,
            boolean banned, int warnings, int ping) {
    }

    private final List<PlayerRow> rows = new ArrayList<>();
    private DarkTextField search;
    private DarkTextField reasonField;
    private DarkTextField durationField;
    private String query = "";
    private String selectedUuid = "";
    private int scrollOffset;
    private long lastRefresh;
    private String status = "Refresh to load the player list";

    public PlayersTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Players";
    }

    @Override
    public String subtitle() {
        return "Kicks, mutes, bans and warnings";
    }

    @Override
    public void init() {
        search = new DarkTextField(x, y, Math.max(120, width - 100), 20,
                net.minecraft.text.Text.of("Search name or UUID"));
        search.setMaxLength(64);
        search.setChangedListener(value -> query = value.toLowerCase(Locale.ROOT).trim());
        screen.addWidget(search);
        screen.addWidget(FlatButton.secondary(x + width - 92, y, 92, 20, "Refresh", button -> refresh()));

        int formY = y + height - 52;
        reasonField = new DarkTextField(x, formY, Math.max(100, width / 3), 20,
                net.minecraft.text.Text.of("Reason"));
        reasonField.setMaxLength(256);
        durationField = new DarkTextField(x + width / 3 + 6, formY, Math.max(80, width / 5), 20,
                net.minecraft.text.Text.of("Duration, e.g. 1h or blank"));
        durationField.setMaxLength(32);
        screen.addWidget(reasonField);
        screen.addWidget(durationField);

        int buttonY = formY + 24;
        int buttonWidth = Math.max(48, (width - 30) / 6);
        screen.addWidget(FlatButton.secondary(x, buttonY, buttonWidth, 20, "Kick",
                button -> kick()));
        screen.addWidget(FlatButton.secondary(x + (buttonWidth + 6), buttonY, buttonWidth, 20, "Mute",
                button -> timed(OrderType.MUTE, "muted")));
        screen.addWidget(FlatButton.secondary(x + 2 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Unmute",
                button -> simple(OrderType.UNMUTE, "unmuted")));
        screen.addWidget(FlatButton.danger(x + 3 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Ban",
                button -> timed(OrderType.BAN, "banned")));
        screen.addWidget(FlatButton.secondary(x + 4 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Unban",
                button -> simple(OrderType.UNBAN, "unbanned")));
        screen.addWidget(FlatButton.primary(x + 5 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Warn",
                button -> warned()));
    }

    @Override
    public void onShown() {
        refresh();
    }

    @Override
    public void tick() {
        if (AdminClientNetworking.get().isReady() && System.currentTimeMillis() - lastRefresh > REFRESH_INTERVAL_MILLIS) {
            refresh();
        }
    }

    private void refresh() {
        lastRefresh = System.currentTimeMillis();
        AdminClientNetworking.get().send(OrderType.LIST_PLAYERS, this::onPlayers);
    }

    private void onPlayers(OrderResultMessage result) {
        if (!result.success()) {
            status = result.message();
            return;
        }
        rows.clear();
        JsonArray array = Json.parseObject(result.json()).getAsJsonArray("players");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                rows.add(new PlayerRow(Json.str(object, "uuid", ""), Json.str(object, "name", "?"),
                        Json.bool(object, "online", true), Json.bool(object, "operator", false),
                        Json.bool(object, "muted", false), Json.bool(object, "banned", false),
                        Json.integer(object, "warnings", 0), Json.integer(object, "ping", -1)));
            }
        }
        status = rows.size() + " player" + (rows.size() == 1 ? "" : "s") + " online";
    }

    private List<PlayerRow> filtered() {
        if (query.isEmpty()) {
            return rows;
        }
        List<PlayerRow> result = new ArrayList<>();
        for (PlayerRow row : rows) {
            if (row.name().toLowerCase(Locale.ROOT).contains(query)
                    || row.uuid().toLowerCase(Locale.ROOT).contains(query)) {
                result.add(row);
            }
        }
        return result;
    }

    private void kick() {
        String target = selectedName();
        if (target == null) {
            return;
        }
        dispatch(OrderType.KICK, "kick", target, reasonField.getText().trim());
    }

    private void warned() {
        String target = selectedName();
        if (target == null) {
            return;
        }
        dispatch(OrderType.WARN, "warn", target, reasonField.getText().trim());
    }

    private void timed(OrderType type, String label) {
        String target = selectedName();
        if (target == null) {
            return;
        }
        dispatch(type, label, target, durationField.getText().trim(), reasonField.getText().trim());
    }

    private void simple(OrderType type, String label) {
        String target = selectedName();
        if (target == null) {
            return;
        }
        dispatch(type, label, target);
    }

    private String selectedName() {
        if (selectedUuid.isEmpty()) {
            status = "Select a player in the list first";
            return null;
        }
        for (PlayerRow row : rows) {
            if (row.uuid().equals(selectedUuid)) {
                return row.name();
            }
        }
        status = "Select a player in the list first";
        return null;
    }

    private void dispatch(OrderType type, String label, String... arguments) {
        status = "Sending " + label + "…";
        AdminClientNetworking.get().send(type, result -> {
            status = result.message();
            if (result.success()) {
                screen.notifySuccess(label + " applied");
                refresh();
            } else {
                screen.notifyError(result.message());
            }
        }, arguments);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int listHeight = height - 84;
        panel(context, x, y + 24, width, listHeight);

        List<PlayerRow> visible = filtered();
        int visibleRows = Math.max(1, (listHeight - 8) / ROW_HEIGHT);
        int maxScroll = Math.max(0, visible.size() - visibleRows);
        scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));

        int rowY = y + 28;
        for (int i = scrollOffset; i < visible.size() && rowY + ROW_HEIGHT <= y + 24 + listHeight; i++) {
            PlayerRow row = visible.get(i);
            boolean hovered = hovering(mouseX, mouseY, x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT);
            if (row.uuid().equals(selectedUuid)) {
                context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, Theme.ROW_SELECTED);
            } else if (hovered) {
                context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, Theme.ROW_HOVER);
            }

            statusDot(context, x + 8, rowY + 6, row.online() ? Theme.SUCCESS : Theme.TEXT_MUTED);
            clippedText(context, row.name(), x + 22, rowY + 5, width / 4, Theme.TEXT);
            clippedText(context, row.uuid(), x + 22 + width / 4, rowY + 5, width / 3, Theme.TEXT_MUTED);

            int tagX = x + 22 + width / 4 + width / 3;
            if (row.operator()) {
                tagX = drawTag(context, tagX, rowY, "OP", Theme.WARNING);
            }
            if (row.muted()) {
                tagX = drawTag(context, tagX, rowY, "MUTED", Theme.DANGER);
            }
            if (row.banned()) {
                tagX = drawTag(context, tagX, rowY, "BANNED", Theme.DANGER);
            }
            if (row.warnings() > 0) {
                drawTag(context, tagX, rowY, row.warnings() + "w", Theme.INFO);
            }

            String right = row.online() ? row.ping() + "ms" : "offline";
            context.drawTextWithShadow(client().textRenderer, right,
                    x + width - 8 - client().textRenderer.getWidth(right), rowY + 5,
                    row.online() ? Theme.TEXT_DIM : Theme.TEXT_MUTED);

            final String uuid = row.uuid();
            final int top = rowY;
            hotZone(x + 1, top, x + width - 1, top + ROW_HEIGHT, () -> selectedUuid = uuid);
            rowY += ROW_HEIGHT;
        }

        if (visible.isEmpty()) {
            emptyState(context, query.isEmpty() ? "No players online" : "No player matches the search");
        }
        text(context, status, x, y + height - 74, Theme.TEXT_DIM);
    }

    private int drawTag(DrawContext context, int tagX, int rowY, String label, int color) {
        int tagWidth = client().textRenderer.getWidth(label) + 8;
        context.fill(tagX, rowY + 4, tagX + tagWidth, rowY + 15, Theme.PANEL_ALT);
        context.fill(tagX, rowY + 4, tagX + 2, rowY + 15, color);
        context.drawTextWithShadow(client().textRenderer, label, tagX + 5, rowY + 6, color);
        return tagX + tagWidth + 4;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int listHeight = height - 84;
        int visibleRows = Math.max(1, (listHeight - 8) / ROW_HEIGHT);
        int maxScroll = Math.max(0, filtered().size() - visibleRows);
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset - vertical * 2));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return clickHotZones(mouseX, mouseY);
    }
}
