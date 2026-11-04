package com.adminmod.client.gui;

import java.util.ArrayList;
import java.util.List;

import com.adminmod.client.gui.tab.AccessTab;
import com.adminmod.client.gui.tab.ChatBridgeTab;
import com.adminmod.client.gui.tab.ConsoleTab;
import com.adminmod.client.gui.tab.DashboardTab;
import com.adminmod.client.gui.tab.ModerationTab;
import com.adminmod.client.gui.tab.OpTab;
import com.adminmod.client.gui.tab.PlayersTab;
import com.adminmod.client.gui.tab.SettingsTab;
import com.adminmod.client.net.AdminClientNetworking;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/**
 * The remote administration dashboard.
 *
 * <p>Layout is a fixed-width sidebar and a content area. The sidebar is drawn rather than built
 * from widgets because the tab strip needs its own selection state and hover animation, which the
 * vanilla button does not provide. Widgets belonging to the active tab are added as normal screen
 * children, so focus and narration keep working.
 *
 * <p>The screen does not pause the game: a remote console is meant to be used while the world is
 * running.
 */
public final class AdminDashboardScreen extends Screen {

    private static final int SIDEBAR_WIDTH = 148;
    private static final int HEADER_HEIGHT = 46;

    private final List<DashboardTab> tabs = new ArrayList<>();
    private DashboardTab active;
    private int activeIndex;

    private final List<Toast> toasts = new ArrayList<>();

    private record Toast(String message, int color, long expiresAt) {
    }

    public AdminDashboardScreen() {
        super(Text.of("ADMIN Mod"));
    }

    @Override
    protected void init() {
        if (tabs.isEmpty()) {
            tabs.add(new ConsoleTab(this));
            tabs.add(new PlayersTab(this));
            tabs.add(new OpTab(this));
            tabs.add(new ModerationTab(this));
            tabs.add(new ChatBridgeTab(this));
            tabs.add(new AccessTab(this));
            tabs.add(new SettingsTab(this));
            active = tabs.get(0);
        }
        layoutTabs();
        active.init();
    }

    private void layoutTabs() {
        int contentX = SIDEBAR_WIDTH + 16;
        int contentY = HEADER_HEIGHT + 12;
        int contentWidth = Math.max(80, width - contentX - 16);
        int contentHeight = Math.max(60, height - contentY - 34);
        for (DashboardTab tab : tabs) {
            tab.layout(contentX, contentY, contentWidth, contentHeight);
        }
    }

    public void selectTab(int index) {
        if (index < 0 || index >= tabs.size() || index == activeIndex) {
            return;
        }
        activeIndex = index;
        active = tabs.get(index);
        clearAndInit();
        active.onShown();
    }

    /** Shows a transient message at the bottom of the window. */
    public void notify(String message, int color) {
        toasts.add(new Toast(message, color, System.currentTimeMillis() + 4_500L));
        if (toasts.size() > 4) {
            toasts.remove(0);
        }
    }

    public void notifySuccess(String message) {
        notify(message, Theme.SUCCESS);
    }

    public void notifyError(String message) {
        notify(message, Theme.DANGER);
    }

    @Override
    public void tick() {
        AdminClientNetworking.get().tick();
        active.tick();
        long now = System.currentTimeMillis();
        toasts.removeIf(toast -> now > toast.expiresAt());
    }

    @Override
    public void renderBackground(DrawContext context, int mouseX, int mouseY, float delta) {
        context.fill(0, 0, width, height, Theme.BACKDROP);
        context.fill(0, 0, SIDEBAR_WIDTH, height, Theme.SIDEBAR);
        context.fill(SIDEBAR_WIDTH - 1, 0, SIDEBAR_WIDTH, height, Theme.BORDER);
        renderBrand(context);
        renderTabStrip(context, mouseX, mouseY);
        renderHeader(context);
        active.beginFrame();
        active.render(context, mouseX, mouseY, delta);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        renderStatusBar(context);
        renderToasts(context);
    }

    private void renderBrand(DrawContext context) {
        context.fill(16, 16, 22, 22, Theme.ACCENT);
        context.drawTextWithShadow(textRenderer, "ADMIN", 28, 15, Theme.TEXT);
        context.drawTextWithShadow(textRenderer, "MOD", 28, 26, Theme.TEXT_DIM);
        context.fill(16, 40, SIDEBAR_WIDTH - 16, 41, Theme.BORDER);
    }

    private void renderTabStrip(DrawContext context, int mouseX, int mouseY) {
        int ty = 54;
        for (int i = 0; i < tabs.size(); i++) {
            DashboardTab tab = tabs.get(i);
            boolean hovered = mouseX >= 8 && mouseX < SIDEBAR_WIDTH - 8 && mouseY >= ty && mouseY < ty + 26;
            boolean selected = i == activeIndex;
            if (selected) {
                context.fill(8, ty, SIDEBAR_WIDTH - 8, ty + 26, Theme.TAB_ACTIVE);
                context.fill(8, ty, 10, ty + 26, Theme.ACCENT);
            } else if (hovered) {
                context.fill(8, ty, SIDEBAR_WIDTH - 8, ty + 26, Theme.TAB_HOVER);
            }
            context.drawTextWithShadow(textRenderer, tab.title(), 20, ty + 9,
                    selected ? Theme.TEXT : hovered ? Theme.TEXT_DIM : Theme.TEXT_MUTED);
            ty += 28;
        }
    }

    private void renderHeader(DrawContext context) {
        context.fill(SIDEBAR_WIDTH, 0, width, HEADER_HEIGHT, Theme.WINDOW);
        context.fill(SIDEBAR_WIDTH, HEADER_HEIGHT - 1, width, HEADER_HEIGHT, Theme.BORDER);
        context.drawTextWithShadow(textRenderer, active.title(), SIDEBAR_WIDTH + 16, 12, Theme.TEXT);
        String subtitle = active.subtitle();
        if (!subtitle.isEmpty()) {
            context.drawTextWithShadow(textRenderer, subtitle, SIDEBAR_WIDTH + 16, 25, Theme.TEXT_MUTED);
        }
        String connection = switch (AdminClientNetworking.get().state()) {
            case READY -> "CONNECTED";
            case HANDSHAKING -> "HANDSHAKING";
            case DISCONNECTED -> "OFFLINE";
        };
        int color = switch (AdminClientNetworking.get().state()) {
            case READY -> Theme.SUCCESS;
            case HANDSHAKING -> Theme.WARNING;
            case DISCONNECTED -> Theme.DANGER;
        };
        int textWidth = textRenderer.getWidth(connection);
        int dotX = width - 24 - textWidth;
        statusDot(context, dotX, 19, color);
        context.drawTextWithShadow(textRenderer, connection, dotX + 12, 18, color);
    }

    private void statusDot(DrawContext context, int dx, int dy, int color) {
        context.fill(dx, dy, dx + 6, dy + 6, color);
    }

    private void renderStatusBar(DrawContext context) {
        context.fill(SIDEBAR_WIDTH, height - 22, width, height, Theme.WINDOW);
        context.fill(SIDEBAR_WIDTH, height - 22, width, height - 21, Theme.BORDER);
        AdminClientNetworking net = AdminClientNetworking.get();
        String status = "protocol 1 | pending " + net.pendingCount()
                + (net.serverVersion().isEmpty() ? "" : " | server " + net.serverVersion());
        context.drawTextWithShadow(textRenderer, status, SIDEBAR_WIDTH + 16, height - 16, Theme.TEXT_MUTED);
        String error = net.lastError();
        if (!error.isEmpty()) {
            String shown = textRenderer.getWidth(error) > width - SIDEBAR_WIDTH - 220
                    ? textRenderer.trimToWidth(error, width - SIDEBAR_WIDTH - 220) + "…" : error;
            context.drawTextWithShadow(textRenderer, shown, width - 16 - textRenderer.getWidth(shown),
                    height - 16, Theme.DANGER);
        }
    }

    private void renderToasts(DrawContext context) {
        int ty = height - 40;
        for (int i = toasts.size() - 1; i >= 0; i--) {
            Toast toast = toasts.get(i);
            int tw = textRenderer.getWidth(toast.message()) + 24;
            int tx = width - tw - 16;
            context.fill(tx, ty - 4, width - 16, ty + 14, Theme.PANEL);
            context.fill(tx, ty - 4, tx + 2, ty + 14, toast.color());
            context.drawTextWithShadow(textRenderer, toast.message(), tx + 10, ty, Theme.TEXT);
            ty -= 24;
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && mouseX >= 8 && mouseX < SIDEBAR_WIDTH - 8 && mouseY >= 54) {
            int index = (int) ((mouseY - 54) / 28);
            if (index >= 0 && index < tabs.size() && mouseY - 54 - index * 28 < 26) {
                selectTab(index);
                return true;
            }
        }
        if (active.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (active.mouseScrolled(mouseX, mouseY, horizontal, vertical)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontal, vertical);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (active.keyPressed(keyCode, scanCode, modifiers)) {
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    /**
     * Adds a widget belonging to the active tab.
     *
     * <p>{@code Screen#addDrawableChild} is protected, and the tabs live in another package, so
     * this is the one door through which they may register widgets. Widgets are cleared on every
     * tab change and resize, so a tab does not have to unregister anything itself.
     */
    public void addWidget(net.minecraft.client.gui.widget.ClickableWidget widget) {
        addDrawableChild(widget);
    }
}
