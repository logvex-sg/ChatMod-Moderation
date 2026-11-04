package com.adminmod.client.gui.tab;

import java.util.ArrayDeque;
import java.util.Deque;

import com.adminmod.client.gui.AdminDashboardScreen;
import com.adminmod.client.gui.DarkTextField;
import com.adminmod.client.gui.FlatButton;
import com.adminmod.client.gui.Theme;
import com.adminmod.client.net.AdminClientNetworking;
import com.adminmod.common.net.OrderResultMessage;
import com.adminmod.common.net.OrderType;

import net.minecraft.client.gui.DrawContext;

/**
 * Remote console.
 *
 * <p>Commands are executed on the server through the mod's own dispatcher, with the output
 * discarded, so nothing is written to the server log and nothing appears in chat. This panel
 * keeps the command and its result in memory for the session only; there is no file, and the
 * history is gone when the screen closes.
 */
public final class ConsoleTab extends DashboardTab {

    private static final int LINE_HEIGHT = 11;
    private static final int MAX_LINES = 500;

    private final Deque<Line> lines = new ArrayDeque<>();
    private DarkTextField input;
    private int scrollOffset;

    private record Line(String text, int color) {
    }

    public ConsoleTab(AdminDashboardScreen screen) {
        super(screen);
        add("ADMIN Mod remote console.", Theme.TEXT_DIM);
        add("Commands run with the server's own dispatcher and produce no server-side log.", Theme.TEXT_MUTED);
    }

    private void add(String text, int color) {
        lines.addLast(new Line(text, color));
        while (lines.size() > MAX_LINES) {
            lines.removeFirst();
        }
        scrollOffset = 0;
    }

    @Override
    public String title() {
        return "Console";
    }

    @Override
    public String subtitle() {
        return "Remote server command execution";
    }

    @Override
    public void init() {
        int inputWidth = Math.max(120, width - 110);
        input = new DarkTextField(x, y + height - 30, inputWidth, 20,
                net.minecraft.text.Text.of("Enter a command, without the leading slash"));
        input.setMaxLength(4096);
        input.setChangedListener(value -> {
        });
        screen.addWidget(input);
        screen.addWidget(FlatButton.primary(x + width - 100, y + height - 30, 100, 20, "Execute",
                button -> submit()));
    }

    private void submit() {
        String command = input.getText().trim();
        if (command.isEmpty()) {
            return;
        }
        if (!AdminClientNetworking.get().isReady()) {
            add("Not connected: authenticate in Settings first.", Theme.DANGER);
            return;
        }
        add("> " + command, Theme.ACCENT);
        input.setText("");
        AdminClientNetworking.get().send(OrderType.EXECUTE_COMMAND, this::onResult, command);
    }

    private void onResult(OrderResultMessage result) {
        add((result.success() ? "  " : "  ! ") + result.message(),
                result.success() ? Theme.TEXT : Theme.DANGER);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        panel(context, x, y, width, height - 36);

        int visibleLines = Math.max(1, (height - 48) / LINE_HEIGHT);
        int total = lines.size();
        int start = Math.max(0, total - visibleLines - scrollOffset);

        int lineY = y + 6;
        int index = 0;
        for (Line line : lines) {
            if (index++ < start) {
                continue;
            }
            if (lineY + LINE_HEIGHT > y + height - 40) {
                break;
            }
            clippedText(context, line.text(), x + 6, lineY, width - 12, line.color());
            lineY += LINE_HEIGHT;
        }

        if (total > visibleLines) {
            String hint = "scroll: " + scrollOffset + " lines back";
            text(context, hint, x + width - 6 - client().textRenderer.getWidth(hint), y + 6, Theme.TEXT_MUTED);
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int visibleLines = Math.max(1, (height - 48) / LINE_HEIGHT);
        int maxScroll = Math.max(0, lines.size() - visibleLines);
        scrollOffset = (int) Math.max(0, Math.min(maxScroll, scrollOffset + vertical * 2));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return clickHotZones(mouseX, mouseY);
    }
}
