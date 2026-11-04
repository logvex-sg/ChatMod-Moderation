package com.adminmod.client.gui;

import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * A text field styled to match the dashboard.
 *
 * <p>Plain {@code TextFieldWidget} draws its own vanilla chrome, which clashes with the dark
 * panels, so the widget is configured with the palette and given a custom border.
 */
public final class DarkTextField extends TextFieldWidget {

    public DarkTextField(int x, int y, int width, int height, Text placeholder) {
        super(net.minecraft.client.MinecraftClient.getInstance().textRenderer, x, y, width, height, placeholder);
        setPlaceholder(placeholder);
        setEditableColor(Theme.TEXT);
        setUneditableColor(Theme.TEXT_MUTED);
        setDrawsBackground(false);
    }

    @Override
    public void renderWidget(net.minecraft.client.gui.DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(getX() - 2, getY() - 2, getX() + getWidth() + 2, getY() + getHeight() + 2,
                isFocused() ? Theme.BORDER_FOCUS : Theme.BORDER);
        context.fill(getX() - 1, getY() - 1, getX() + getWidth() + 1, getY() + getHeight() + 1, Theme.PANEL_ALT);
        super.renderWidget(context, mouseX, mouseY, delta);
    }
}
