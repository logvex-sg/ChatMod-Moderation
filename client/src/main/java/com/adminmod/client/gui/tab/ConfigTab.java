package com.adminmod.client.gui.tab;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.adminmod.client.gui.AdminDashboardScreen;
import com.adminmod.client.gui.DarkTextField;
import com.adminmod.client.gui.FlatButton;
import com.adminmod.client.gui.MultiLineTextField;
import com.adminmod.client.gui.Theme;
import com.adminmod.client.model.ClientPreferences;
import com.adminmod.client.model.SettingsModel;
import com.adminmod.client.net.AdminClientNetworking;
import com.adminmod.common.model.Json;
import com.adminmod.common.net.OrderResultMessage;
import com.adminmod.common.net.OrderType;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * The central settings screen: every ADMIN feature, in one place, with a control per value.
 *
 * <p>The layout is a category strip on the left and a scrollable list of settings on the right,
 * with a search box and two view toggles above and a pending-changes bar below. A setting is
 * rendered from its type: a boolean is a switch, an enum cycles through its options, a ranged
 * number is a slider, and everything else opens an inline editor at the bottom of the list.
 *
 * <p>Edits are staged rather than sent immediately. A change becomes a pending edit, the row is
 * marked, and the bar at the bottom counts them; Apply sends the whole batch and Cancel throws it
 * away. The server validates the batch and refuses it as a whole if any value is rejected, so a
 * half-applied configuration is not possible.
 */
public final class ConfigTab extends DashboardTab {

    private static final int ROW_HEIGHT = 26;
    private static final int CATEGORY_WIDTH = 172;
    private static final int HEADER = 26;
    private static final int ACTION_BAR = 28;
    private static final int EDITOR_HEIGHT = 104;

    /** A category row in the strip, with its counts. */
    private record CategoryView(String id, String label, String description, int count, int changed) {
    }

    private final SettingsModel model = new SettingsModel();
    private final List<CategoryView> categories = new ArrayList<>();

    private DarkTextField searchField;
    private DarkTextField editorField;
    private MultiLineTextField editorList;
    private FlatButton applyButton;
    private FlatButton cancelButton;

    private String category = "";
    private boolean searching;
    private int scroll;
    private String selectedPath = "";
    private String status = "Loading configuration…";
    private boolean loaded;

    /** The slider currently being dragged, so a drag keeps updating the same setting. */
    private String dragPath = "";
    private int dragX1;
    private int dragX2;
    private double dragMin;
    private double dragMax;
    private boolean dragInteger;

    private String hoverLabel = "";
    private String hoverDescription = "";

    public ConfigTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Config";
    }

    @Override
    public String subtitle() {
        return "Every ADMIN setting in one place";
    }

    private static ClientPreferences prefs() {
        return AdminClientModAccess.preferences();
    }

    @Override
    public void init() {
        searchField = new DarkTextField(x + 4, y + 3, Math.max(120, width - 320), 20,
                net.minecraft.text.Text.of("Search settings…"));
        searchField.setMaxLength(80);
        searchField.setChangedListener(this::onSearch);
        screen.addWidget(searchField);

        screen.addWidget(FlatButton.secondary(x + width - 310, y + 3, 148, 20,
                prefs().showAdvanced() ? "Advanced: on" : "Advanced: off",
                button -> {
                    prefs().setShowAdvanced(!prefs().showAdvanced());
                    button.setMessage(net.minecraft.text.Text.of(
                            prefs().showAdvanced() ? "Advanced: on" : "Advanced: off"));
                }));
        screen.addWidget(FlatButton.secondary(x + width - 156, y + 3, 152, 20,
                prefs().changedOnly() ? "Changed only: on" : "Changed only: off",
                button -> {
                    prefs().setChangedOnly(!prefs().changedOnly());
                    button.setMessage(net.minecraft.text.Text.of(
                            prefs().changedOnly() ? "Changed only: on" : "Changed only: off"));
                }));

        editorField = new DarkTextField(x + 12, y + height - ACTION_BAR - EDITOR_HEIGHT + 40,
                Math.max(160, width - 24), 20, net.minecraft.text.Text.of("Value"));
        editorField.setMaxLength(4096);
        editorField.setVisible(false);
        screen.addWidget(editorField);

        editorList = new MultiLineTextField(x + 12, y + height - ACTION_BAR - EDITOR_HEIGHT + 40,
                Math.max(160, width - 24), 48);
        editorList.setVisible(false);
        screen.addWidget(editorList);

        int barY = y + height - ACTION_BAR + 4;
        applyButton = FlatButton.primary(x + 4, barY, 120, 20, "Apply changes", button -> applyPending());
        cancelButton = FlatButton.secondary(x + 130, barY, 110, 20, "Cancel", button -> discardPending());
        screen.addWidget(applyButton);
        screen.addWidget(cancelButton);
        screen.addWidget(FlatButton.secondary(x + 246, barY, 130, 20, "Reset category",
                button -> resetCategory()));
        screen.addWidget(FlatButton.secondary(x + 382, barY, 110, 20, "Back up now",
                button -> backup()));
        screen.addWidget(FlatButton.secondary(x + 498, barY, 130, 20, "Diagnostics",
                button -> showDiagnostics()));
    }

    @Override
    public void onShown() {
        refresh();
    }

    @Override
    public void tick() {
        if (!loaded && AdminClientNetworking.get().isReady()) {
            refresh();
        }
        updateButtons();
    }

    private void refresh() {
        AdminClientNetworking.get().send(OrderType.SETTINGS_CATALOG, result -> {
            if (!result.success()) {
                status = result.message();
                return;
            }
            model.load(result.json());
            rebuildCategories();
            if (category.isEmpty() && !categories.isEmpty()) {
                category = categories.get(0).id();
            }
            loaded = true;
            status = model.all().size() + " settings loaded";
        });
    }

    private void rebuildCategories() {
        categories.clear();
        categories.add(new CategoryView("__favorites", "Favorites", "Settings you starred", 0, 0));
        categories.add(new CategoryView("__changed", "Changed", "Settings that differ from the default",
                countChanged(), countChanged()));
        for (SettingsModel.Category entry : model.categories()) {
            categories.add(new CategoryView(entry.id(), entry.label(), entry.description(),
                    model.countIn(entry.id()), model.changedIn(entry.id())));
        }
    }

    private int countChanged() {
        int total = 0;
        for (SettingsModel.Entry entry : model.all()) {
            if (model.differsFromDefault(entry)) {
                total++;
            }
        }
        return total;
    }

    private void onSearch(String query) {
        searching = !query.isBlank();
        scroll = 0;
    }

    // ------------------------------------------------------------------ data selection

    private List<SettingsModel.Entry> visibleEntries() {
        List<SettingsModel.Entry> entries;
        if (searching) {
            entries = model.search(searchField.getText());
        } else if (category.equals("__favorites")) {
            entries = new ArrayList<>();
            for (String path : prefs().favorites()) {
                SettingsModel.Entry entry = model.get(path);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            entries.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        } else if (category.equals("__changed")) {
            entries = new ArrayList<>();
            for (SettingsModel.Entry entry : model.all()) {
                if (model.differsFromDefault(entry)) {
                    entries.add(entry);
                }
            }
            entries.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        } else {
            entries = model.inCategory(category);
        }
        if (!prefs().showAdvanced() && !searching) {
            List<SettingsModel.Entry> filtered = new ArrayList<>();
            for (SettingsModel.Entry entry : entries) {
                if (!entry.advanced || model.isPending(entry.path) || isFavorite(entry.path)) {
                    filtered.add(entry);
                }
            }
            entries = filtered;
        }
        return entries;
    }

    private boolean isFavorite(String path) {
        return prefs().isFavorite(path);
    }

    // ------------------------------------------------------------------ actions

    private void applyPending() {
        if (model.pendingCount() == 0) {
            screen.notify("Nothing to apply", Theme.TEXT_DIM);
            return;
        }
        String json = model.pendingAsJson();
        int count = model.pendingCount();
        AdminClientNetworking.get().send(OrderType.SETTINGS_UPDATE_BATCH, result -> {
            if (!result.success()) {
                status = result.message();
                screen.notifyError(result.message());
                return;
            }
            model.load(Json.str(Json.parseObject(result.json()), "catalog", "{}"));
            model.discardPending();
            rebuildCategories();
            model.setWarnings(Json.stringList(Json.parseObject(result.json()), "warnings"));
            status = result.message();
            screen.notifySuccess(count + " setting(s) applied");
            if (!model.warnings().isEmpty()) {
                screen.notify(model.warnings().get(0), Theme.WARNING);
            }
        }, json);
    }

    private void discardPending() {
        model.discardPending();
        status = "Pending changes discarded";
        screen.notify("Changes discarded", Theme.TEXT_DIM);
    }

    private void resetCategory() {
        if (category.startsWith("__")) {
            screen.notifyError("Select a category to reset");
            return;
        }
        AdminClientNetworking.get().send(OrderType.SETTINGS_RESET, result -> {
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            model.load(Json.str(Json.parseObject(result.json()), "catalog", "{}"));
            model.discardPending();
            rebuildCategories();
            status = result.message();
            screen.notifySuccess("Category reset to defaults");
        }, "category", category);
    }

    private void backup() {
        AdminClientNetworking.get().send(OrderType.SETTINGS_BACKUP, result -> {
            status = result.message();
            if (result.success()) {
                screen.notifySuccess("Configuration backed up");
            } else {
                screen.notifyError(result.message());
            }
        });
    }

    private void showDiagnostics() {
        AdminClientNetworking.get().send(OrderType.SETTINGS_DIAGNOSTICS, result -> {
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            var root = Json.parseObject(result.json());
            var counts = Json.parseObject(Json.str(root, "counts", "{}"));
            int errors = Json.integer(counts, "error", 0);
            int warnings = Json.integer(counts, "warning", 0);
            status = "Diagnostics: " + errors + " error(s), " + warnings + " warning(s)";
            screen.notify(status, errors > 0 ? Theme.DANGER : warnings > 0 ? Theme.WARNING : Theme.SUCCESS);
        });
    }

    /** Stages a new value for a setting, or sends it straight away when it is a destructive change. */
    private void stage(SettingsModel.Entry entry, String value) {
        if (entry.dangerous) {
            // A destructive value is applied on its own so the confirmation is unambiguous and a
            // later Cancel cannot silently drop it from a batch.
            AdminClientNetworking.get().send(OrderType.SETTINGS_UPDATE, result -> {
                if (!result.success()) {
                    screen.notifyError(result.message());
                    return;
                }
                model.load(Json.str(Json.parseObject(result.json()), "catalog", "{}"));
                rebuildCategories();
                prefs().noteRecent(entry.path);
                status = result.message();
                screen.notifySuccess(entry.label + " updated");
            }, entry.path, value);
            return;
        }
        model.setPending(entry.path, value);
        prefs().noteRecent(entry.path);
        status = model.pendingCount() + " pending change(s)";
    }

    private void updateButtons() {
        if (applyButton != null) {
            applyButton.active = model.pendingCount() > 0;
            applyButton.setMessage(net.minecraft.text.Text.of(
                    model.pendingCount() == 0 ? "Apply changes" : "Apply (" + model.pendingCount() + ")"));
        }
        if (cancelButton != null) {
            cancelButton.active = model.pendingCount() > 0;
        }
    }

    // ------------------------------------------------------------------ rendering

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        hoverLabel = "";
        hoverDescription = "";

        panel(context, x, y, width, height);
        renderCategoryStrip(context, mouseX, mouseY);

        int listX = x + CATEGORY_WIDTH + 8;
        int listY = y + HEADER;
        int listWidth = width - CATEGORY_WIDTH - 16;
        int listHeight = height - HEADER - ACTION_BAR;
        context.fill(listX, listY, listX + listWidth, listY + listHeight, Theme.PANEL_ALT);
        context.fill(listX, listY, listX + listWidth, listY + 1, Theme.BORDER);

        List<SettingsModel.Entry> entries = visibleEntries();
        renderRows(context, entries, listX, listY + 2, listWidth, listHeight - 4, mouseX, mouseY);
        renderEditor(context);
        renderActionBar(context, listX);
        if (!hoverLabel.isEmpty()) {
            renderTooltip(context, mouseX, mouseY);
        }
    }

    private void renderCategoryStrip(DrawContext context, int mouseX, int mouseY) {
        int stripX = x + 4;
        int stripY = y + HEADER;
        int stripWidth = CATEGORY_WIDTH - 8;
        int stripHeight = height - HEADER - ACTION_BAR;
        context.fill(stripX, stripY, stripX + stripWidth, stripY + stripHeight, Theme.PANEL_ALT);
        int rowY = stripY + 2;
        for (CategoryView view : categories) {
            if (rowY + 22 > stripY + stripHeight) {
                break;
            }
            boolean selected = !searching && view.id().equals(category);
            boolean hovered = hovering(mouseX, mouseY, stripX, rowY, stripX + stripWidth, rowY + 22);
            if (selected) {
                context.fill(stripX, rowY, stripX + stripWidth, rowY + 22, Theme.TAB_ACTIVE);
                context.fill(stripX, rowY, stripX + 2, rowY + 22, Theme.ACCENT);
            } else if (hovered) {
                context.fill(stripX, rowY, stripX + stripWidth, rowY + 22, Theme.TAB_HOVER);
            }
            clippedText(context, view.label(), stripX + 8, rowY + 7, stripWidth - 40,
                    selected ? Theme.TEXT : Theme.TEXT_DIM);
            String badge = view.changed() > 0 ? view.changed() + "/" + view.count() : String.valueOf(view.count());
            text(context, badge, stripX + stripWidth - 8 - client().textRenderer.getWidth(badge), rowY + 7,
                    view.changed() > 0 ? Theme.WARNING : Theme.TEXT_MUTED);
            final String id = view.id();
            hotZone(stripX, rowY, stripX + stripWidth, rowY + 22, () -> {
                category = id;
                searching = false;
                scroll = 0;
            });
            rowY += 24;
        }
    }

    private void renderRows(DrawContext context, List<SettingsModel.Entry> entries, int listX, int listY,
            int listWidth, int listHeight, int mouseX, int mouseY) {
        if (entries.isEmpty()) {
            emptyState(context, searching ? "No settings match the search" : "No settings here");
            return;
        }
        int maxScroll = Math.max(0, entries.size() * ROW_HEIGHT - listHeight);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);
        int first = scroll / ROW_HEIGHT;
        int offset = scroll % ROW_HEIGHT;
        int drawn = 0;
        for (int i = first; i < entries.size(); i++) {
            int rowY = listY + drawn * ROW_HEIGHT - offset;
            if (rowY > listY + listHeight) {
                break;
            }
            if (rowY + ROW_HEIGHT < listY) {
                drawn++;
                continue;
            }
            renderRow(context, entries.get(i), listX, rowY, listWidth, mouseX, mouseY);
            drawn++;
        }
        // Scrollbar.
        if (maxScroll > 0) {
            int trackX = listX + listWidth - 4;
            int thumbHeight = Math.max(16, listHeight * listHeight / (entries.size() * ROW_HEIGHT));
            int thumbY = listY + (listHeight - thumbHeight) * scroll / maxScroll;
            context.fill(trackX, listY, trackX + 3, listY + listHeight, Theme.BORDER);
            context.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, Theme.TEXT_MUTED);
        }
    }

    private void renderRow(DrawContext context, SettingsModel.Entry entry, int listX, int rowY, int listWidth,
            int mouseX, int mouseY) {
        boolean hovered = hovering(mouseX, mouseY, listX, rowY, listX + listWidth, rowY + ROW_HEIGHT);
        boolean selected = entry.path.equals(selectedPath);
        boolean pending = model.isPending(entry.path);
        boolean changed = model.differsFromDefault(entry);
        int background = selected ? Theme.ROW_SELECTED : hovered ? Theme.ROW_HOVER
                : (rowY / ROW_HEIGHT) % 2 == 0 ? Theme.PANEL_ALT : Theme.PANEL;
        context.fill(listX, rowY, listX + listWidth, rowY + ROW_HEIGHT, background);
        if (pending) {
            context.fill(listX, rowY, listX + 2, rowY + ROW_HEIGHT, Theme.WARNING);
        } else if (changed) {
            context.fill(listX, rowY, listX + 2, rowY + ROW_HEIGHT, Theme.ACCENT_DIM);
        }
        if (hovered) {
            hoverLabel = entry.label;
            hoverDescription = entry.description;
        }

        // Star (favourite) column on the far right.
        int starX = listX + listWidth - 18;
        boolean favorite = isFavorite(entry.path);
        text(context, favorite ? "*" : "+", starX, rowY + 8, favorite ? Theme.WARNING : Theme.TEXT_MUTED);
        hotZone(starX - 2, rowY, starX + 10, rowY + ROW_HEIGHT, () -> prefs().toggleFavorite(entry.path));

        // Value control on the right.
        int controlX = listX + listWidth - 240;
        int controlWidth = 210;
        renderControl(context, entry, controlX, rowY + 4, controlWidth, mouseX, mouseY);

        // Label and path on the left.
        int labelColor = entry.deprecated ? Theme.TEXT_MUTED : Theme.TEXT;
        clippedText(context, entry.label, listX + 8, rowY + 5, controlX - listX - 16, labelColor);
        String secondary = entry.secret ? (entry.configured ? "configured" : "not set")
                : entry.path;
        clippedText(context, secondary, listX + 8, rowY + 15, controlX - listX - 16, Theme.TEXT_MUTED);

        // Selecting a row opens the editor for a type that needs one.
        hotZone(listX, rowY, listX + listWidth, rowY + ROW_HEIGHT, () -> selectRow(entry));
    }

    private void selectRow(SettingsModel.Entry entry) {
        selectedPath = entry.path;
        if (entry.isBoolean() || entry.isEnum() || (entry.isNumber() && entry.hasRange())) {
            editorField.setVisible(false);
            editorList.setVisible(false);
            return;
        }
        String value = model.pendingValue(entry.path);
        if (entry.isList()) {
            editorList.setText(value);
            editorList.setVisible(true);
            editorList.setFocused(true);
            editorField.setVisible(false);
        } else {
            editorField.setText(value);
            editorField.setVisible(true);
            editorField.setFocused(true);
            editorList.setVisible(false);
        }
    }

    private void renderControl(DrawContext context, SettingsModel.Entry entry, int cx, int cy, int cw,
            int mouseX, int mouseY) {
        String value = model.pendingValue(entry.path);
        if (entry.isBoolean()) {
            boolean on = value.equalsIgnoreCase("true");
            int w = 54;
            int bx = cx + cw - w;
            context.fill(bx, cy, bx + w, cy + 18, on ? Theme.ACCENT_DIM : Theme.PANEL);
            context.fill(bx, cy, bx + (on ? w : 2), cy + 18, on ? Theme.ACCENT : Theme.BORDER);
            text(context, on ? "ON" : "OFF", bx + (w - client().textRenderer.getWidth(on ? "ON" : "OFF")) / 2,
                    cy + 5, on ? Theme.TEXT : Theme.TEXT_DIM);
            hotZone(bx, cy, bx + w, cy + 18, () -> stage(entry, on ? "false" : "true"));
            return;
        }
        if (entry.isEnum()) {
            int w = Math.min(cw, 150);
            int bx = cx + cw - w;
            context.fill(bx, cy, bx + w, cy + 18, Theme.PANEL);
            context.fill(bx, cy, bx + 2, cy + 18, Theme.BORDER);
            clippedText(context, value, bx + 6, cy + 5, w - 20, Theme.TEXT);
            text(context, ">", bx + w - 10, cy + 5, Theme.TEXT_DIM);
            hotZone(bx, cy, bx + w, cy + 18, () -> stage(entry, nextOption(entry, value)));
            return;
        }
        if (entry.isNumber() && entry.hasRange()) {
            int w = 130;
            int bx = cx + cw - w;
            double ratio = (parseDouble(value) - entry.min) / (entry.max - entry.min);
            ratio = MathHelper.clamp(ratio, 0, 1);
            context.fill(bx, cy + 7, bx + w, cy + 11, Theme.PANEL);
            context.fill(bx, cy + 7, bx + (int) (w * ratio), cy + 11, Theme.ACCENT);
            context.fill(bx + (int) (w * ratio) - 2, cy + 3, bx + (int) (w * ratio) + 2, cy + 15, Theme.TEXT);
            String shown = formatNumber(value, entry);
            clippedText(context, shown, cx, cy + 5, cw - w - 8, Theme.TEXT_DIM);
            hotZone(bx - 2, cy, bx + w + 2, cy + 18, () -> beginDrag(entry, bx, bx + w));
            return;
        }
        // Text, colour and list values show their value and rely on the editor.
        String shown = entry.secret ? (entry.configured ? "••••••••" : "(not set)")
                : entry.isList() ? value.replace("\n", " ") : value;
        clippedText(context, shown, cx, cy + 5, cw, entry.secret ? Theme.TEXT_MUTED : Theme.TEXT_DIM);
    }

    private void beginDrag(SettingsModel.Entry entry, int x1, int x2) {
        dragPath = entry.path;
        dragX1 = x1;
        dragX2 = x2;
        dragMin = entry.min;
        dragMax = entry.max;
        dragInteger = entry.type.equals("INTEGER") || entry.type.equals("LONG");
        applyDrag((int) 0, true);
    }

    private void applyDrag(int mouseX, boolean force) {
        SettingsModel.Entry entry = model.get(dragPath);
        if (entry == null) {
            return;
        }
        double ratio = MathHelper.clamp((mouseX - dragX1) / (double) (dragX2 - dragX1), 0, 1);
        double raw = dragMin + ratio * (dragMax - dragMin);
        String value;
        if (dragInteger) {
            value = String.valueOf(Math.round(raw));
        } else {
            value = String.format(Locale.ROOT, "%.2f", raw);
        }
        model.setPending(entry.path, value);
    }

    private void renderEditor(DrawContext context) {
        if (selectedPath.isEmpty()) {
            return;
        }
        SettingsModel.Entry entry = model.get(selectedPath);
        if (entry == null || entry.isBoolean() || entry.isEnum() || (entry.isNumber() && entry.hasRange())) {
            return;
        }
        int ex = x + 8;
        int ey = y + height - ACTION_BAR - EDITOR_HEIGHT;
        int ew = width - 16;
        panel(context, ex, ey, ew, EDITOR_HEIGHT);
        context.fill(ex, ey, ex + ew, ey + 2, Theme.ACCENT);
        text(context, entry.label, ex + 12, ey + 8, Theme.TEXT);
        text(context, entry.path + (entry.unit.isEmpty() ? "" : "  (" + entry.unit + ")"),
                ex + 12, ey + 20, Theme.TEXT_MUTED);
        clippedText(context, entry.description, ex + 12, ey + 30, ew - 24, Theme.TEXT_DIM);
        if (entry.isList()) {
            text(context, "One entry per line", ex + 12, ey + 40, Theme.TEXT_MUTED);
            hotZone(ex + ew - 210, ey + EDITOR_HEIGHT - 24, ex + ew - 116, ey + EDITOR_HEIGHT - 4,
                    () -> saveEditor(entry));
            text(context, "[ Save value ]", ex + ew - 208, ey + EDITOR_HEIGHT - 18, Theme.ACCENT);
            hotZone(ex + ew - 108, ey + EDITOR_HEIGHT - 24, ex + ew - 12, ey + EDITOR_HEIGHT - 4,
                    () -> clearEditor());
            text(context, "[ Default ]", ex + ew - 106, ey + EDITOR_HEIGHT - 18, Theme.TEXT_DIM);
        } else {
            hotZone(ex + ew - 210, ey + EDITOR_HEIGHT - 24, ex + ew - 116, ey + EDITOR_HEIGHT - 4,
                    () -> saveEditor(entry));
            text(context, "[ Save value ]", ex + ew - 208, ey + EDITOR_HEIGHT - 18, Theme.ACCENT);
            hotZone(ex + ew - 108, ey + EDITOR_HEIGHT - 24, ex + ew - 12, ey + EDITOR_HEIGHT - 4,
                    () -> clearEditor());
            text(context, "[ Default ]", ex + ew - 106, ey + EDITOR_HEIGHT - 18, Theme.TEXT_DIM);
        }
    }

    private void saveEditor(SettingsModel.Entry entry) {
        String value = entry.isList() ? editorList.getText() : editorField.getText();
        stage(entry, value);
        screen.notify(entry.label + " staged", Theme.ACCENT);
    }

    private void clearEditor() {
        selectedPath = "";
        editorField.setVisible(false);
        editorList.setVisible(false);
    }

    private void renderActionBar(DrawContext context, int listX) {
        int barY = y + height - ACTION_BAR;
        context.fill(x + 2, barY, x + width - 2, barY + 1, Theme.BORDER);
        String pending = model.pendingCount() == 0 ? "No pending changes"
                : model.pendingCount() + " pending change(s) — press Apply";
        text(context, pending, listX + 660, barY + 9,
                model.pendingCount() == 0 ? Theme.TEXT_MUTED : Theme.WARNING);
        text(context, status, x + 4, barY + 20, Theme.TEXT_DIM);
    }

    private void renderTooltip(DrawContext context, int mouseX, int mouseY) {
        int maxWidth = Math.min(320, width - 40);
        List<String> lines = wrap(hoverDescription, maxWidth - 16);
        int boxWidth = client().textRenderer.getWidth(hoverLabel) + 16;
        for (String line : lines) {
            boxWidth = Math.max(boxWidth, client().textRenderer.getWidth(line) + 16);
        }
        boxWidth = Math.min(boxWidth, maxWidth);
        int boxHeight = 14 + lines.size() * 10 + (lines.isEmpty() ? 0 : 4);
        int bx = MathHelper.clamp(mouseX + 12, x + 4, x + width - boxWidth - 4);
        int by = MathHelper.clamp(mouseY + 12, y + 4, y + height - boxHeight - 4);
        context.fill(bx, by, bx + boxWidth, by + boxHeight, Theme.WINDOW);
        context.fill(bx, by, bx + boxWidth, by + 1, Theme.BORDER_FOCUS);
        context.fill(bx, by + boxHeight - 1, bx + boxWidth, by + boxHeight, Theme.BORDER);
        text(context, hoverLabel, bx + 8, by + 5, Theme.TEXT);
        int lineY = by + 18;
        for (String line : lines) {
            text(context, line, bx + 8, lineY, Theme.TEXT_DIM);
            lineY += 10;
        }
    }

    private List<String> wrap(String value, int maxWidth) {
        List<String> lines = new ArrayList<>();
        if (value == null || value.isEmpty()) {
            return lines;
        }
        StringBuilder current = new StringBuilder();
        for (String word : value.split(" ")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (client().textRenderer.getWidth(candidate) > maxWidth && !current.isEmpty()) {
                lines.add(current.toString());
                current = new StringBuilder(word);
            } else {
                current = new StringBuilder(candidate);
            }
        }
        if (!current.isEmpty()) {
            lines.add(current.toString());
        }
        return lines;
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 1 && !selectedPath.isEmpty()) {
            model.discardPending(selectedPath);
            screen.notify("Reverted " + selectedPath, Theme.TEXT_DIM);
            return true;
        }
        if (clickHotZones(mouseX, mouseY)) {
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (!dragPath.isEmpty()) {
            applyDrag((int) mouseX, false);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!dragPath.isEmpty()) {
            dragPath = "";
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        int listHeight = height - HEADER - ACTION_BAR;
        int maxScroll = Math.max(0, visibleEntries().size() * ROW_HEIGHT - listHeight);
        scroll = MathHelper.clamp(scroll - (int) (vertical * ROW_HEIGHT), 0, maxScroll);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 256 && editorField.isVisible()) { // Escape closes the editor
            clearEditor();
            return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ helpers

    private static String nextOption(SettingsModel.Entry entry, String current) {
        List<String> options = entry.options;
        if (options.isEmpty()) {
            return current;
        }
        for (int i = 0; i < options.size(); i++) {
            if (options.get(i).equalsIgnoreCase(current)) {
                return options.get((i + 1) % options.size());
            }
        }
        return options.get(0);
    }

    private static double parseDouble(String value) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String formatNumber(String value, SettingsModel.Entry entry) {
        double parsed = parseDouble(value);
        String shown;
        if (entry.type.equals("DECIMAL")) {
            shown = String.format(Locale.ROOT, "%.2f", parsed);
        } else {
            shown = String.valueOf(Math.round(parsed));
        }
        return entry.unit.isEmpty() ? shown : shown + " " + entry.unit;
    }

    /** Holds the client preferences singleton, kept out of the tab to keep the tab testable. */
    static final class AdminClientModAccess {
        private static ClientPreferences preferences;

        static ClientPreferences preferences() {
            if (preferences == null) {
                preferences = new ClientPreferences(
                        net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir()
                                .resolve("adminmod-client.json"));
                preferences.load();
            }
            return preferences;
        }

        private AdminClientModAccess() {
        }
    }
}
