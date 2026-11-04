package com.adminmod.client.gui.tab;

import com.adminmod.client.gui.AdminDashboardScreen;
import com.adminmod.client.gui.Theme;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/**
 * One page of the dashboard.
 *
 * <p>A tab owns its own widgets and draws inside the content rectangle handed to it by
 * {@link AdminDashboardScreen}. Tabs are re-laid-out whenever the window is resized or the tab
 * changes, and they receive input only while active, so a tab cannot react to clicks made on
 * another page.
 */
public abstract class DashboardTab {

    protected final AdminDashboardScreen screen;
    protected int x;
    protected int y;
    protected int width;
    protected int height;

    /**
     * Clickable regions built during rendering.
     *
     * <p>Rows and inline action buttons are drawn rather than built from widgets, because a
     * widget per row would make a long player list expensive and would not clip correctly. Each
     * region is registered while drawing and hit-tested on the next click, which keeps what is
     * drawn and what is clickable in the same place.
     */
    private final java.util.List<HotZone> hotZones = new java.util.ArrayList<>();

    protected record HotZone(int x1, int y1, int x2, int y2, Runnable action) {
        boolean contains(double mx, double my) {
            return mx >= x1 && mx < x2 && my >= y1 && my < y2;
        }
    }

    protected DashboardTab(AdminDashboardScreen screen) {
        this.screen = screen;
    }

    /** Clears the clickable regions. Called by the screen before every frame. */
    public final void beginFrame() {
        hotZones.clear();
    }

    protected final void hotZone(int x1, int y1, int x2, int y2, Runnable action) {
        hotZones.add(new HotZone(x1, y1, x2, y2, action));
    }

    protected final boolean hovering(double mouseX, double mouseY, int x1, int y1, int x2, int y2) {
        return mouseX >= x1 && mouseX < x2 && mouseY >= y1 && mouseY < y2;
    }

    /** Runs the first registered region containing the point. */
    protected final boolean clickHotZones(double mouseX, double mouseY) {
        for (HotZone zone : hotZones) {
            if (zone.contains(mouseX, mouseY)) {
                zone.action().run();
                return true;
            }
        }
        return false;
    }

    public void layout(int x, int y, int width, int height) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
    }

    public abstract String title();

    public String subtitle() {
        return "";
    }

    /** Creates widgets for the current layout. Called on every resize and tab change. */
    public void init() {
    }

    /** Called once per client tick while this tab is active. */
    public void tick() {
    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
    }

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return false;
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        return false;
    }

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        return false;
    }

    /** Called when the tab becomes active. */
    public void onShown() {
    }

    // ---------------------------------------------------------------- drawing helpers

    protected static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    protected void panel(DrawContext context, int px, int py, int pw, int ph) {
        context.fill(px, py, px + pw, py + ph, Theme.PANEL);
        context.fill(px, py, px + pw, py + 1, Theme.BORDER);
        context.fill(px, py + ph - 1, px + pw, py + ph, Theme.BORDER);
        context.fill(px, py, px + 1, py + ph, Theme.BORDER);
        context.fill(px + pw - 1, py, px + pw, py + ph, Theme.BORDER);
    }

    protected void text(DrawContext context, String value, int tx, int ty, int color) {
        context.drawTextWithShadow(client().textRenderer, value, tx, ty, color);
    }

    protected void clippedText(DrawContext context, String value, int tx, int ty, int maxWidth, int color) {
        String shown = value;
        if (client().textRenderer.getWidth(shown) > maxWidth) {
            while (shown.length() > 1 && client().textRenderer.getWidth(shown + "…") > maxWidth) {
                shown = shown.substring(0, shown.length() - 1);
            }
            shown = shown + "…";
        }
        context.drawTextWithShadow(client().textRenderer, shown, tx, ty, color);
    }

    protected void statusDot(DrawContext context, int dx, int dy, int color) {
        context.fill(dx, dy, dx + 6, dy + 6, color);
        context.fill(dx - 1, dy - 1, dx + 7, dy, Theme.BORDER);
        context.fill(dx - 1, dy + 6, dx + 7, dy + 7, Theme.BORDER);
        context.fill(dx - 1, dy - 1, dx, dy + 7, Theme.BORDER);
        context.fill(dx + 6, dy - 1, dx + 7, dy + 7, Theme.BORDER);
    }

    protected void emptyState(DrawContext context, String message) {
        int centerX = x + width / 2;
        int centerY = y + height / 2;
        context.drawTextWithShadow(client().textRenderer, message,
                centerX - client().textRenderer.getWidth(message) / 2, centerY, Theme.TEXT_MUTED);
    }
}
