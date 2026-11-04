package com.adminmod.client.gui;

/**
 * Dark palette for the dashboard.
 *
 * <p>Colours are declared as ARGB ints so they can be handed straight to
 * {@code DrawContext.fill}. The scheme is deliberately low-contrast in the chrome and
 * high-contrast in the content, which keeps long console and player lists readable.
 */
public final class Theme {

    public static final int BACKDROP = 0xE60B0E13;
    public static final int WINDOW = 0xF0141821;
    public static final int SIDEBAR = 0xFF0F131A;
    public static final int PANEL = 0xFF1A1F2B;
    public static final int PANEL_ALT = 0xFF202634;
    public static final int BORDER = 0xFF2C3444;
    public static final int BORDER_FOCUS = 0xFF3D7EFF;

    public static final int TEXT = 0xFFE6EAF2;
    public static final int TEXT_DIM = 0xFF8B94A7;
    public static final int TEXT_MUTED = 0xFF5C6577;

    public static final int ACCENT = 0xFF3D7EFF;
    public static final int ACCENT_DIM = 0xFF2A5BC0;
    public static final int SUCCESS = 0xFF3FCF7F;
    public static final int WARNING = 0xFFE8B93B;
    public static final int DANGER = 0xFFE8544C;
    public static final int INFO = 0xFF52B7E0;

    public static final int TAB_ACTIVE = 0xFF232A3A;
    public static final int TAB_HOVER = 0xFF1C2230;
    public static final int ROW_HOVER = 0xFF232B3B;
    public static final int ROW_SELECTED = 0xFF2B3A55;

    private Theme() {
    }
}
