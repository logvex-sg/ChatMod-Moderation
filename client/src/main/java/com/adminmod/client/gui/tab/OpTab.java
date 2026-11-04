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
 * Operator management.
 *
 * <p>Granting and revoking OP is owner-only on the server, so a non-owner gets a refusal rather
 * than a silent no-op. Changes are applied immediately: a connected player receives the new
 * command tree without reconnecting.
 */
public final class OpTab extends DashboardTab {

    private static final int ROW_HEIGHT = 18;

    private final List<String[]> operators = new ArrayList<>();
    private DarkTextField nameField;
    private String status = "Enter a player name or UUID";

    public OpTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "OP Management";
    }

    @Override
    public String subtitle() {
        return "Grant, revoke and inspect operator status";
    }

    @Override
    public void init() {
        nameField = new DarkTextField(x, y, Math.max(120, width - 260), 20,
                net.minecraft.text.Text.of("Player name or UUID"));
        nameField.setMaxLength(64);
        screen.addWidget(nameField);

        int buttonY = y + 26;
        int buttonWidth = Math.max(64, (width - 18) / 4);
        screen.addWidget(FlatButton.primary(x, buttonY, buttonWidth, 20, "Grant OP",
                button -> send(OrderType.OP_GRANT, "granted OP")));
        screen.addWidget(FlatButton.secondary(x + (buttonWidth + 6), buttonY, buttonWidth, 20, "Revoke OP",
                button -> send(OrderType.OP_REVOKE, "revoked OP")));
        screen.addWidget(FlatButton.secondary(x + 2 * (buttonWidth + 6), buttonY, buttonWidth, 20, "Check status",
                button -> check()));
        screen.addWidget(FlatButton.secondary(x + 3 * (buttonWidth + 6), buttonY, buttonWidth, 20, "List operators",
                button -> list()));
    }

    @Override
    public void onShown() {
        list();
    }

    private void send(OrderType type, String label) {
        String target = nameField.getText().trim();
        if (target.isEmpty()) {
            status = "Enter a player name first";
            return;
        }
        status = "Sending…";
        AdminClientNetworking.get().send(type, result -> {
            status = result.message();
            if (result.success()) {
                screen.notifySuccess(label + ": " + target);
                list();
            } else {
                screen.notifyError(result.message());
            }
        }, target);
    }

    private void check() {
        String target = nameField.getText().trim();
        if (target.isEmpty()) {
            status = "Enter a player name first";
            return;
        }
        AdminClientNetworking.get().send(OrderType.OP_STATUS, result -> {
            if (!result.success()) {
                status = result.message();
                return;
            }
            JsonObject root = Json.parseObject(result.json());
            status = Json.str(root, "name", target) + " is "
                    + (Json.bool(root, "operator", false) ? "an operator" : "not an operator");
        }, target);
    }

    private void list() {
        AdminClientNetworking.get().send(OrderType.OP_LIST, this::onList);
    }

    private void onList(OrderResultMessage result) {
        if (!result.success()) {
            status = result.message();
            return;
        }
        operators.clear();
        JsonArray array = Json.parseObject(result.json()).getAsJsonArray("operators");
        if (array != null) {
            for (JsonElement element : array) {
                JsonObject object = element.getAsJsonObject();
                operators.add(new String[] {
                        Json.str(object, "name", "?"),
                        Json.str(object, "uuid", ""),
                        Json.bool(object, "online", false) ? "online" : "offline"});
            }
        }
        status = operators.size() + " operator" + (operators.size() == 1 ? "" : "s");
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        int listY = y + 56;
        int listHeight = height - 82;
        panel(context, x, listY, width, listHeight);

        text(context, "Operators", x + 8, listY + 6, Theme.TEXT_DIM);
        int rowY = listY + 20;
        for (String[] operator : operators) {
            if (rowY + ROW_HEIGHT > listY + listHeight - 4) {
                break;
            }
            context.fill(x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT,
                    hovering(mouseX, mouseY, x + 1, rowY, x + width - 1, rowY + ROW_HEIGHT)
                            ? Theme.ROW_HOVER : Theme.PANEL);
            statusDot(context, x + 8, rowY + 6,
                    "online".equals(operator[2]) ? Theme.SUCCESS : Theme.TEXT_MUTED);
            clippedText(context, operator[0], x + 22, rowY + 5, width / 3, Theme.TEXT);
            clippedText(context, operator[1], x + 22 + width / 3, rowY + 5, width / 2, Theme.TEXT_MUTED);
            context.drawTextWithShadow(client().textRenderer, operator[2],
                    x + width - 8 - client().textRenderer.getWidth(operator[2]), rowY + 5, Theme.TEXT_DIM);
            rowY += ROW_HEIGHT;
        }
        if (operators.isEmpty()) {
            emptyState(context, "No operators reported");
        }
        text(context, status, x, y + height - 20, Theme.TEXT_DIM);
    }
}
