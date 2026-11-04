package com.adminmod.client.gui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Flat, dark button used throughout the dashboard.
 *
 * <p>Subclasses {@code ButtonWidget} so it participates in the normal focus and click handling,
 * and only overrides the drawing. A coloured accent bar on the left marks destructive actions.
 */
public class FlatButton extends ButtonWidget {

    private final int accent;
    private final int textColor;

    public FlatButton(int x, int y, int width, int height, Text message, int accent, int textColor,
            PressAction onPress) {
        super(x, y, width, height, message, onPress, DEFAULT_NARRATION_SUPPLIER);
        this.accent = accent;
        this.textColor = textColor;
    }

    public static FlatButton primary(int x, int y, int width, int height, String label, PressAction onPress) {
        return new FlatButton(x, y, width, height, Text.of(label), Theme.ACCENT, Theme.TEXT, onPress);
    }

    public static FlatButton secondary(int x, int y, int width, int height, String label, PressAction onPress) {
        return new FlatButton(x, y, width, height, Text.of(label), Theme.BORDER, Theme.TEXT, onPress);
    }

    public static FlatButton danger(int x, int y, int width, int height, String label, PressAction onPress) {
        return new FlatButton(x, y, width, height, Text.of(label), Theme.DANGER, Theme.TEXT, onPress);
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        int background = !active ? Theme.PANEL_ALT
                : isHovered() ? Theme.PANEL_ALT : Theme.PANEL;
        context.fill(getX(), getY(), getX() + getWidth(), getY() + getHeight(), background);
        context.fill(getX(), getY(), getX() + 2, getY() + getHeight(), accent);
        context.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), Theme.BORDER);
        if (isFocused()) {
            context.fill(getX(), getY(), getX() + getWidth(), getY() + 1, Theme.BORDER_FOCUS);
            context.fill(getX(), getY() + getHeight() - 1, getX() + getWidth(), getY() + getHeight(), Theme.BORDER_FOCUS);
        }
        String label = getMessage().getString();
        int color = active ? textColor : Theme.TEXT_MUTED;
        context.drawTextWithShadow(net.minecraft.client.MinecraftClient.getInstance().textRenderer, label,
                getX() + (getWidth() - net.minecraft.client.MinecraftClient.getInstance().textRenderer.getWidth(label)) / 2,
                getY() + (getHeight() - 8) / 2, color);
    }
}
