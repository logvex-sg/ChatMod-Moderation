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
 * Moderation history and notes.
 *
 * <p>The tab reads three views from the server: the full history, the warnings and the notes. A
 * pardon takes a punishment id, which is shown on each row, so an operator can revoke one specific
 * entry rather than only the most recent.
 */
public final class ModerationTab extends DashboardTab {

    private static final int ROW_HEIGHT = 22;

    private record Entry(String id, String type, String staff, String reason, String created, String duration,
            boolean active) {
    }

    private final List<Entry> history = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    private DarkTextField nameField;
    private DarkTextField noteField;
    private String status = "Enter a player name and load their history";
    private boolean showNotes;

    public ModerationTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Moderation";
    }

    @Override
    public String subtitle() {
        return "History, warnings, notes and pardons";
    }

    @Override
    public void init() {
        nameField = new DarkTextField(x, y, Math.max(120, width / 3), 20,
                net.minecraft.text.Text.of("Player name or UUID"));
        nameField.setMaxLength(64);
        screen.addWidget(nameField);

        noteField = new DarkTextField(x + width / 3 + 6, y, Math.max(120, width - width / 3 - 200), 20,
                net.minecraft.text.Text.of("Note text"));
        noteField.setMaxLength(512);
        screen.addWidget(noteField);

        int buttonY = y + 26;
        int buttonWidth = Math.max(70, (width - 24) / 5);
        screen.addWidget(FlatButton.primary(x, buttonY, buttonWidth, 20, "History",
                button -> load(OrderType.MODERATION_HISTORY)));
        screen.addWidget(FlatButton.secondary(x + (buttonWidth + 6), buttonY, buttonWidth, 20, "Warnings",
                button -> load(OrderType.LIST_WARNINGS)));
        screen.addWidget(FlatButton.secondary(x + 2 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Notes",
                button -> load(OrderType.LIST_NOTES)));
        screen.addWidget(FlatButton.secondary(x + 3 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Add note",
                button -> addNote()));
        screen.addWidget(FlatButton.danger(x + 4 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Pardon",
                button -> pardon()));
    }

    @Override
    public void onShown() {
    }

    private void load(OrderType type) {
        String target = nameField.getText().trim();
        if (target.isEmpty()) {
            status = "Enter a player name first";
            return;
        }
        showNotes = type == OrderType.LIST_NOTES;
        AdminClientNetworking.get().send(type, result -> onLoaded(result, type), target);
    }

    private void onLoaded(OrderResultMessage result, OrderType type) {
        if (!result.success()) {
            status = result.message();
            return;
        }
        JsonObject root = Json.parseObject(result.json());
        if (type == OrderType.LIST_NOTES) {
            notes.clear();
            history.clear();
            JsonArray array = root.getAsJsonArray("notes");
            if (array != null) {
                for (JsonElement element : array) {
                    JsonObject object = element.getAsJsonObject();
                    notes.add("#" + Json.lng(object, "id", 0L) + "  " + Json.str(object, "authorName", "?")
                            + ": " + Json.str(object, "text", ""));
                }
            }
            status = notes.size() + " note" + (notes.size() == 1 ? "" : "s");
            return;
        }
        history.clear();
        notes.clear();
        JsonArray array = root.getAsJsonArray("punishments");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                history.add(new Entry(
                        Long.toString(Json.lng(object, "id", 0L)),
                        Json.str(object, "type", "?"),
                        Json.str(object, "staffName", "?"),
                        Json.str(object, "reason", ""),
                        Json.str(object, "createdAt", ""),
                        Json.str(object, "duration", ""),
                        Json.bool(object, "active", false)));
            }
        }
        status = history.size() + " record" + (history.size() == 1 ? "" : "s");
    }

    private void addNote() {
        String target = nameField.getText().trim();
        String text = noteField.getText().trim();
        if (target.isEmpty() || text.isEmpty()) {
            status = "A player name and a note are both required";
            return;
        }
        AdminClientNetworking.get().send(OrderType.NOTE_ADD, result -> {
            status = result.message();
            if (result.success()) {
                noteField.setText("");
                load(OrderType.LIST_NOTES);
            }
        }, target, text);
    }

    private void pardon() {
        String id = noteField.getText().trim();
        if (id.isEmpty()) {
            status = "Put the punishment id in the second field, then press Pardon";
            return;
        }
        AdminClientNetworking.get().send(OrderType.PARDON, result -> {
            status = result.message();
            if (result.success()) {
                screen.notifySuccess("Pardoned #" + id);
                load(OrderType.MODERATION_HISTORY);
            } else {
                screen.notifyError(result.message());
            }
        }, nameField.getText().trim(), id);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int listY = y + 56;
        int listHeight = height - 82;
        panel(context, x, listY, width, listHeight);

        if (showNotes) {
            text(context, "Notes", x + 8, listY + 6, Theme.TEXT_DIM);
            int rowY = listY + 20;
            for (String note : notes) {
                if (rowY + 12 > listY + listHeight - 4) {
                    break;
                }
                clippedText(context, note, x + 10, rowY, width - 20, Theme.TEXT);
                rowY += 12;
            }
            if (notes.isEmpty()) {
                emptyState(context, "No notes for this player");
            }
        } else {
            text(context, "ID", x + 8, listY + 6, Theme.TEXT_DIM);
            text(context, "Type", x + 60, listY + 6, Theme.TEXT_DIM);
            text(context, "Staff", x + 160, listY + 6, Theme.TEXT_DIM);
            text(context, "Reason", x + 260, listY + 6, Theme.TEXT_DIM);
            text(context, "Duration", x + width - 100, listY + 6, Theme.TEXT_DIM);

            int rowY = listY + 20;
            for (Entry entry : history) {
                if (rowY + ROW_HEIGHT > listY + listHeight - 4) {
                    break;
                }
                boolean hovered = hovering(mouseX, mouseY, x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT);
                if (hovered) {
                    context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT, Theme.ROW_HOVER);
                }
                int color = entry.active() ? Theme.DANGER : Theme.TEXT_MUTED;
                clippedText(context, "#" + entry.id(), x + 8, rowY + 3, 48, Theme.TEXT_DIM);
                clippedText(context, entry.type(), x + 60, rowY + 3, 96, color);
                clippedText(context, entry.staff(), x + 160, rowY + 3, 96, Theme.TEXT_DIM);
                clippedText(context, entry.reason(), x + 260, rowY + 3, width - 380, Theme.TEXT);
                clippedText(context, entry.active() ? entry.duration() : "revoked", x + width - 100, rowY + 3,
                        92, Theme.TEXT_DIM);
                rowY += ROW_HEIGHT;
            }
            if (history.isEmpty()) {
                emptyState(context, "No records for this player");
            }
        }
        text(context, status, x, y + height - 20, Theme.TEXT_DIM);
    }
}
