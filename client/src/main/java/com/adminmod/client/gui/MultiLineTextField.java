package com.adminmod.client.gui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

/**
 * A small multi-line text area for editing list settings.
 *
 * <p>A list setting — blocked keywords, extra moderators, announcement lines — is naturally
 * several lines, and the single-line {@link DarkTextField} cannot hold a newline. This widget edits
 * the value as one entry per line, which is exactly the form the settings protocol uses, so what is
 * typed here is what the server stores.
 *
 * <p>It is deliberately small: printable characters, Enter, Backspace, the arrow keys and Home/End.
 * That is enough to edit a list, and it avoids reimplementing the full text-editing surface of the
 * vanilla field.
 */
public final class MultiLineTextField extends ClickableWidget {

    private final List<String> lines = new ArrayList<>();
    private int caretLine;
    private int caretColumn;
    private int scrollLine;
    private int maxLength = 4_096;
    private int lineHeight = 10;

    public MultiLineTextField(int x, int y, int width, int height) {
        super(x, y, width, height, Text.of("List value"));
        lines.add("");
    }

    public void setMaxLength(int value) {
        this.maxLength = value;
    }

    /** Shows or hides the area; {@code ClickableWidget} has no setter for its visible flag. */
    public void setVisible(boolean value) {
        this.visible = value;
    }

    @Override
    protected void appendClickableNarrations(net.minecraft.client.gui.screen.narration.NarrationMessageBuilder builder) {
        builder.put(net.minecraft.client.gui.screen.narration.NarrationPart.TITLE, Text.of(getText()));
    }

    public void setText(String value) {
        lines.clear();
        if (value == null || value.isEmpty()) {
            lines.add("");
        } else {
            for (String line : value.replace("\r", "").split("\n", -1)) {
                lines.add(line);
            }
        }
        caretLine = lines.size() - 1;
        caretColumn = lines.get(caretLine).length();
        clampScroll();
    }

    public String getText() {
        return String.join("\n", lines);
    }

    public List<String> getLines() {
        return new ArrayList<>(lines);
    }

    @Override
    protected void renderWidget(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient client = MinecraftClient.getInstance();
        context.fill(getX() - 2, getY() - 2, getX() + getWidth() + 2, getY() + getHeight() + 2,
                isFocused() ? Theme.BORDER_FOCUS : Theme.BORDER);
        context.fill(getX() - 1, getY() - 1, getX() + getWidth() + 1, getY() + getHeight() + 1, Theme.PANEL_ALT);
        int visible = Math.max(1, (getHeight() - 4) / lineHeight);
        for (int i = 0; i < visible && scrollLine + i < lines.size(); i++) {
            int lineIndex = scrollLine + i;
            context.drawTextWithShadow(client.textRenderer, lines.get(lineIndex), getX() + 2,
                    getY() + 2 + i * lineHeight, Theme.TEXT);
        }
        if (isFocused()) {
            int caretRow = caretLine - scrollLine;
            if (caretRow >= 0 && caretRow < visible) {
                String prefix = lines.get(caretLine).substring(0, MathHelper.clamp(caretColumn, 0,
                        lines.get(caretLine).length()));
                int caretX = getX() + 2 + client.textRenderer.getWidth(prefix);
                context.fill(caretX, getY() + 2 + caretRow * lineHeight,
                        caretX + 1, getY() + 2 + caretRow * lineHeight + lineHeight, Theme.ACCENT);
            }
        }
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        MinecraftClient client = MinecraftClient.getInstance();
        int row = (int) ((mouseY - getY() - 2) / lineHeight) + scrollLine;
        caretLine = MathHelper.clamp(row, 0, lines.size() - 1);
        String line = lines.get(caretLine);
        int column = line.length();
        for (int i = 0; i <= line.length(); i++) {
            if (getX() + 2 + client.textRenderer.getWidth(line.substring(0, i)) >= mouseX) {
                column = i;
                break;
            }
        }
        caretColumn = MathHelper.clamp(column, 0, line.length());
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (!isFocused() || chr < 32 || chr == 127) {
            return false;
        }
        String line = lines.get(caretLine);
        if (totalLength() >= maxLength) {
            return false;
        }
        lines.set(caretLine, line.substring(0, caretColumn) + chr + line.substring(caretColumn));
        caretColumn++;
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!isFocused()) {
            return false;
        }
        switch (keyCode) {
            case 257, 335 -> { // Enter / keypad enter
                String line = lines.get(caretLine);
                lines.set(caretLine, line.substring(0, caretColumn));
                lines.add(caretLine + 1, line.substring(caretColumn));
                caretLine++;
                caretColumn = 0;
                clampScroll();
                return true;
            }
            case 259 -> { // Backspace
                if (caretColumn > 0) {
                    String line = lines.get(caretLine);
                    lines.set(caretLine, line.substring(0, caretColumn - 1) + line.substring(caretColumn));
                    caretColumn--;
                } else if (caretLine > 0) {
                    String previous = lines.get(caretLine - 1);
                    caretColumn = previous.length();
                    lines.set(caretLine - 1, previous + lines.get(caretLine));
                    lines.remove(caretLine);
                    caretLine--;
                    clampScroll();
                }
                return true;
            }
            case 261 -> { // Delete
                String line = lines.get(caretLine);
                if (caretColumn < line.length()) {
                    lines.set(caretLine, line.substring(0, caretColumn) + line.substring(caretColumn + 1));
                } else if (caretLine < lines.size() - 1) {
                    lines.set(caretLine, line + lines.get(caretLine + 1));
                    lines.remove(caretLine + 1);
                }
                return true;
            }
            case 263 -> { // Left
                if (caretColumn > 0) {
                    caretColumn--;
                } else if (caretLine > 0) {
                    caretLine--;
                    caretColumn = lines.get(caretLine).length();
                    clampScroll();
                }
                return true;
            }
            case 262 -> { // Right
                if (caretColumn < lines.get(caretLine).length()) {
                    caretColumn++;
                } else if (caretLine < lines.size() - 1) {
                    caretLine++;
                    caretColumn = 0;
                    clampScroll();
                }
                return true;
            }
            case 265 -> { // Up
                caretLine = Math.max(0, caretLine - 1);
                caretColumn = Math.min(caretColumn, lines.get(caretLine).length());
                clampScroll();
                return true;
            }
            case 264 -> { // Down
                caretLine = Math.min(lines.size() - 1, caretLine + 1);
                caretColumn = Math.min(caretColumn, lines.get(caretLine).length());
                clampScroll();
                return true;
            }
            case 268 -> { // Home
                caretColumn = 0;
                return true;
            }
            case 269 -> { // End
                caretColumn = lines.get(caretLine).length();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private int totalLength() {
        int total = 0;
        for (String line : lines) {
            total += line.length() + 1;
        }
        return total;
    }

    private void clampScroll() {
        int visible = Math.max(1, (getHeight() - 4) / lineHeight);
        if (caretLine < scrollLine) {
            scrollLine = caretLine;
        }
        if (caretLine >= scrollLine + visible) {
            scrollLine = caretLine - visible + 1;
        }
        scrollLine = Math.max(0, Math.min(scrollLine, Math.max(0, lines.size() - visible)));
    }
}
