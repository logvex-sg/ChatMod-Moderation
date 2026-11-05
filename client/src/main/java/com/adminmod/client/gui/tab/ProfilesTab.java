package com.adminmod.client.gui.tab;

import java.util.ArrayList;
import java.util.List;

import com.adminmod.client.gui.AdminDashboardScreen;
import com.adminmod.client.gui.DarkTextField;
import com.adminmod.client.gui.FlatButton;
import com.adminmod.client.gui.MultiLineTextField;
import com.adminmod.client.gui.Theme;
import com.adminmod.client.net.AdminClientNetworking;
import com.adminmod.common.model.Json;
import com.adminmod.common.net.OrderResultMessage;
import com.adminmod.common.net.OrderType;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.MathHelper;

/**
 * Profile and preset management.
 *
 * <p>A profile is a named set of settings that can be applied, exported and shared. This tab lists
 * the built-in profiles and presets alongside the operator's own, previews what applying one would
 * change before it is applied, and provides create, rename, duplicate, delete, export and import.
 *
 * <p>The preview is the point: applying a profile touches many settings at once, and the operator
 * sees exactly which before committing.
 */
public final class ProfilesTab extends DashboardTab {

    private static final int ROW_HEIGHT = 24;
    private static final int LIST_WIDTH = 260;
    private static final int ACTION_BAR = 30;

    private record ProfileView(String name, String description, boolean builtIn, int size) {
    }

    private record Change(String path, String label, String current, String proposed) {
    }

    private final List<ProfileView> profiles = new ArrayList<>();
    private final List<Change> preview = new ArrayList<>();

    private DarkTextField nameField;
    private DarkTextField importField;
    private MultiLineTextField exportArea;

    private String selected = "";
    private String selectedPreviewName = "";
    private String status = "Loading profiles…";
    private int scroll;
    private int previewScroll;

    public ProfilesTab(AdminDashboardScreen screen) {
        super(screen);
    }

    @Override
    public String title() {
        return "Profiles";
    }

    @Override
    public String subtitle() {
        return "Presets, previews and import/export";
    }

    @Override
    public void init() {
        int formY = y + height - ACTION_BAR - 108;
        nameField = new DarkTextField(x + 12, formY + 4, 200, 18,
                net.minecraft.text.Text.of("New profile name"));
        nameField.setMaxLength(48);
        screen.addWidget(nameField);

        importField = new DarkTextField(x + 12, formY + 30, Math.max(160, width - 24), 18,
                net.minecraft.text.Text.of("Paste an exported profile JSON here to import"));
        importField.setMaxLength(64_000);
        screen.addWidget(importField);

        exportArea = new MultiLineTextField(x + 12, formY + 4, Math.max(160, width - 24), 62);
        exportArea.setVisible(false);
        screen.addWidget(exportArea);

        int barY = y + height - ACTION_BAR + 4;
        screen.addWidget(FlatButton.primary(x + 4, barY, 110, 20, "Apply", button -> applyProfile()));
        screen.addWidget(FlatButton.secondary(x + 120, barY, 110, 20, "Preview", button -> previewProfile()));
        screen.addWidget(FlatButton.secondary(x + 236, barY, 110, 20, "Create", button -> createProfile()));
        screen.addWidget(FlatButton.secondary(x + 352, barY, 110, 20, "Export", button -> exportProfile()));
        screen.addWidget(FlatButton.secondary(x + 468, barY, 110, 20, "Import", button -> importProfile(false)));
        screen.addWidget(FlatButton.secondary(x + 584, barY, 110, 20, "Import + apply",
                button -> importProfile(true)));
        screen.addWidget(FlatButton.danger(x + 700, barY, 110, 20, "Delete", button -> deleteProfile()));
    }

    @Override
    public void onShown() {
        refresh();
    }

    @Override
    public void tick() {
        if (profiles.isEmpty() && AdminClientNetworking.get().isReady()) {
            refresh();
        }
    }

    private void refresh() {
        AdminClientNetworking.get().send(OrderType.PROFILE_LIST, result -> {
            if (!result.success()) {
                status = result.message();
                return;
            }
            loadProfiles(result.json());
            status = profiles.size() + " profile(s) available";
        });
    }

    private void loadProfiles(String json) {
        profiles.clear();
        var root = Json.parseObject(json);
        if (root.has("profiles")) {
            for (var element : root.getAsJsonArray("profiles")) {
                var object = element.getAsJsonObject();
                profiles.add(new ProfileView(Json.str(object, "name", ""),
                        Json.str(object, "description", ""), Json.bool(object, "builtIn", false),
                        Json.integer(object, "size", 0)));
            }
        }
        if (selected.isEmpty() && !profiles.isEmpty()) {
            selected = profiles.get(0).name();
        }
    }

    private void previewProfile() {
        if (selected.isEmpty()) {
            screen.notifyError("Select a profile first");
            return;
        }
        AdminClientNetworking.get().send(OrderType.PROFILE_PREVIEW, result -> {
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            preview.clear();
            var root = Json.parseObject(result.json());
            if (root.has("changes")) {
                for (var element : root.getAsJsonArray("changes")) {
                    var object = element.getAsJsonObject();
                    preview.add(new Change(Json.str(object, "path", ""), Json.str(object, "label", ""),
                            Json.str(object, "current", ""), Json.str(object, "proposed", "")));
                }
            }
            selectedPreviewName = selected;
            previewScroll = 0;
            status = "Preview: " + preview.size() + " setting(s) would change";
        }, selected);
    }

    private void applyProfile() {
        if (selected.isEmpty()) {
            screen.notifyError("Select a profile first");
            return;
        }
        AdminClientNetworking.get().send(OrderType.PROFILE_APPLY, result -> {
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            status = result.message();
            screen.notifySuccess(result.message());
            refresh();
            previewProfile();
        }, selected);
    }

    private void createProfile() {
        String name = nameField.getText().trim();
        if (name.isEmpty()) {
            screen.notifyError("Enter a name for the new profile");
            return;
        }
        AdminClientNetworking.get().send(OrderType.PROFILE_CREATE, result -> {
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            status = result.message();
            screen.notifySuccess("Created '" + name + "'");
            refresh();
        }, name, "Captured from the current configuration");
    }

    private void exportProfile() {
        if (selected.isEmpty()) {
            screen.notifyError("Select a profile to export");
            return;
        }
        AdminClientNetworking.get().send(OrderType.PROFILE_EXPORT, result -> {
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            String json = Json.str(Json.parseObject(result.json()), "json", "");
            exportArea.setText(json);
            exportArea.setVisible(true);
            // The export is also placed in the import field so it can be re-imported or copied.
            importField.setText(json);
            status = "Exported '" + selected + "' (" + json.length() + " bytes)";
            screen.notify("Exported to the field below", Theme.ACCENT);
        }, selected);
    }

    private void importProfile(boolean applyNow) {
        String json = importField.getText().trim();
        if (json.isEmpty()) {
            screen.notifyError("Paste a profile JSON to import");
            return;
        }
        AdminClientNetworking.get().send(OrderType.PROFILE_IMPORT, result -> {
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            status = result.message();
            screen.notifySuccess(result.message());
            refresh();
        }, json, Boolean.toString(applyNow));
    }

    private void deleteProfile() {
        if (selected.isEmpty()) {
            return;
        }
        ProfileView view = profileByName(selected);
        if (view != null && view.builtIn()) {
            screen.notifyError("Built-in profiles cannot be deleted");
            return;
        }
        AdminClientNetworking.get().send(OrderType.PROFILE_DELETE, result -> {
            if (!result.success()) {
                screen.notifyError(result.message());
                return;
            }
            status = result.message();
            selected = "";
            preview.clear();
            refresh();
        }, selected);
    }

    private ProfileView profileByName(String name) {
        for (ProfileView view : profiles) {
            if (view.name().equals(name)) {
                return view;
            }
        }
        return null;
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        panel(context, x, y, width, height);

        // Profile list on the left.
        int listX = x + 4;
        int listY = y + 4;
        int listHeight = height - ACTION_BAR - 116;
        context.fill(listX, listY, listX + LIST_WIDTH, listY + listHeight, Theme.PANEL_ALT);
        renderList(context, listX, listY, listHeight, mouseX, mouseY);

        // Preview on the right.
        int previewX = listX + LIST_WIDTH + 8;
        int previewWidth = width - LIST_WIDTH - 16;
        context.fill(previewX, listY, previewX + previewWidth, listY + listHeight, Theme.PANEL_ALT);
        renderPreview(context, previewX, listY, previewWidth, listHeight);

        renderFormArea(context);
        renderActionBar(context);
    }

    private void renderList(DrawContext context, int listX, int listY, int listHeight, int mouseX, int mouseY) {
        int maxScroll = Math.max(0, profiles.size() * ROW_HEIGHT - listHeight);
        scroll = MathHelper.clamp(scroll, 0, maxScroll);
        int first = scroll / ROW_HEIGHT;
        int offset = scroll % ROW_HEIGHT;
        int drawn = 0;
        for (int i = first; i < profiles.size(); i++) {
            ProfileView view = profiles.get(i);
            int rowY = listY + drawn * ROW_HEIGHT - offset;
            if (rowY > listY + listHeight) {
                break;
            }
            boolean hovered = hovering(mouseX, mouseY, listX, rowY, listX + LIST_WIDTH, rowY + ROW_HEIGHT);
            boolean isSelected = view.name().equals(selected);
            if (isSelected) {
                context.fill(listX, rowY, listX + LIST_WIDTH, rowY + ROW_HEIGHT, Theme.ROW_SELECTED);
                context.fill(listX, rowY, listX + 2, rowY + ROW_HEIGHT, Theme.ACCENT);
            } else if (hovered) {
                context.fill(listX, rowY, listX + LIST_WIDTH, rowY + ROW_HEIGHT, Theme.ROW_HOVER);
            }
            clippedText(context, view.name(), listX + 8, rowY + 3, LIST_WIDTH - 60, Theme.TEXT);
            clippedText(context, view.description(), listX + 8, rowY + 13, LIST_WIDTH - 60, Theme.TEXT_MUTED);
            String tag = view.builtIn() ? "built-in" : "custom";
            text(context, tag, listX + LIST_WIDTH - 8 - client().textRenderer.getWidth(tag), rowY + 3,
                    view.builtIn() ? Theme.TEXT_MUTED : Theme.INFO);
            final String name = view.name();
            hotZone(listX, rowY, listX + LIST_WIDTH, rowY + ROW_HEIGHT, () -> {
                selected = name;
                preview.clear();
            });
            drawn++;
        }
        if (profiles.isEmpty()) {
            text(context, "No profiles loaded", listX + 8, listY + 8, Theme.TEXT_MUTED);
        }
    }

    private void renderPreview(DrawContext context, int previewX, int listY, int previewWidth, int listHeight) {
        String heading = selectedPreviewName.isEmpty() ? "Preview" : "Preview — " + selectedPreviewName;
        text(context, heading, previewX + 8, listY + 4, Theme.TEXT);
        int rowsY = listY + 18;
        if (preview.isEmpty()) {
            text(context, "Select a profile and press Preview to see what would change.",
                    previewX + 8, rowsY + 4, Theme.TEXT_MUTED);
            return;
        }
        int maxScroll = Math.max(0, preview.size() * 22 - (listHeight - 22));
        previewScroll = MathHelper.clamp(previewScroll, 0, maxScroll);
        int drawn = 0;
        for (int i = previewScroll / 22; i < preview.size(); i++) {
            Change change = preview.get(i);
            int rowY = rowsY + drawn * 22 - previewScroll % 22;
            if (rowY > listY + listHeight - 20) {
                break;
            }
            clippedText(context, change.label(), previewX + 8, rowY, previewWidth / 2 - 12, Theme.TEXT);
            String before = trim(change.current(), 24);
            String after = trim(change.proposed(), 24);
            text(context, before, previewX + previewWidth / 2, rowY, Theme.TEXT_MUTED);
            text(context, "->", previewX + previewWidth - 8 - client().textRenderer.getWidth(after) - 16,
                    rowY, Theme.TEXT_DIM);
            text(context, after, previewX + previewWidth - 8 - client().textRenderer.getWidth(after),
                    rowY, Theme.SUCCESS);
            drawn++;
        }
    }

    private static String trim(String value, int max) {
        String single = value.replace("\n", " ");
        return single.length() <= max ? single : single.substring(0, max - 1) + "…";
    }

    private void renderFormArea(DrawContext context) {
        int formY = y + height - ACTION_BAR - 108;
        panel(context, x + 4, formY, width - 8, 108);
        text(context, "Create, export and import", x + 12, formY - 12, Theme.TEXT_DIM);
        text(context, "Import JSON (also shows the last export)", x + 12, formY + 20, Theme.TEXT_MUTED);
    }

    private void renderActionBar(DrawContext context) {
        int barY = y + height - ACTION_BAR;
        context.fill(x + 2, barY, x + width - 2, barY + 1, Theme.BORDER);
        text(context, status, x + 4, barY + 9, Theme.TEXT_DIM);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontal, double vertical) {
        if (mouseX < x + 4 + LIST_WIDTH) {
            int listHeight = height - ACTION_BAR - 116;
            int maxScroll = Math.max(0, profiles.size() * ROW_HEIGHT - listHeight);
            scroll = MathHelper.clamp(scroll - (int) (vertical * ROW_HEIGHT), 0, maxScroll);
        } else {
            int listHeight = height - ACTION_BAR - 116;
            int maxScroll = Math.max(0, preview.size() * 22 - (listHeight - 22));
            previewScroll = MathHelper.clamp(previewScroll - (int) (vertical * 22), 0, maxScroll);
        }
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return clickHotZones(mouseX, mouseY);
    }
}
