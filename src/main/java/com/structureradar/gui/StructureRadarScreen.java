package com.structureradar.gui;

import com.structureradar.config.ModConfig;
import com.structureradar.scanner.ChunkScanner;
import com.structureradar.scanner.StructureInfo;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;

import java.util.List;

/**
 * Interactive 1.16.5 Radar + Scrollable Structure List GUI opened with 'R'.
 * Uses strictly 1.16.5 MatrixStack + DrawableHelper + ButtonWidget + Text.of().
 */
public class StructureRadarScreen extends Screen {

    private final ChunkScanner scanner;

    private int filterCategoryIndex = -1; // -1 = ALL categories
    private int scrollOffset = 0;
    private int selectedIndex = -1;
    private double radarRangeBlocks = 256.0D;

    private ButtonWidget toggleScanButton;
    private ButtonWidget filterTypeButton;
    private ButtonWidget clearCacheButton;
    private ButtonWidget rescanButton;
    private ButtonWidget zoomInButton;
    private ButtonWidget zoomOutButton;
    private ButtonWidget copyCoordsButton;
    private ButtonWidget hudAlertsButton;

    public StructureRadarScreen(ChunkScanner scanner) {
        super(Text.of("StructureRadar - Render Distance Scanner"));
        this.scanner = scanner;
    }

    @Override
    protected void init() {
        super.init();
        final ModConfig config = ModConfig.get();

        int bottomY = this.height - 28;
        int topBtnY = 28;

        // Top Control Row
        this.toggleScanButton = this.addButton(new ButtonWidget(
                12, topBtnY, 115, 20,
                getScanButtonLabel(config),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        config.scanningEnabled = !config.scanningEnabled;
                        config.save();
                        button.setMessage(getScanButtonLabel(config));
                    }
                }
        ));

        this.filterTypeButton = this.addButton(new ButtonWidget(
                132, topBtnY, 145, 20,
                getFilterButtonLabel(),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        StructureInfo.StructureCategory[] all = StructureInfo.StructureCategory.values();
                        filterCategoryIndex++;
                        if (filterCategoryIndex >= all.length) {
                            filterCategoryIndex = -1;
                        }
                        scrollOffset = 0;
                        selectedIndex = -1;
                        button.setMessage(getFilterButtonLabel());
                    }
                }
        ));

        this.hudAlertsButton = this.addButton(new ButtonWidget(
                282, topBtnY, 110, 20,
                getHudButtonLabel(config),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        config.hudBannerEnabled = !config.hudBannerEnabled;
                        config.save();
                        button.setMessage(getHudButtonLabel(config));
                    }
                }
        ));

        // Bottom Control Row
        this.rescanButton = this.addButton(new ButtonWidget(
                12, bottomY, 95, 20,
                Text.of("Rescan Chunks"),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        scanner.rescanAroundPlayer();
                    }
                }
        ));

        this.clearCacheButton = this.addButton(new ButtonWidget(
                112, bottomY, 90, 20,
                Text.of("Clear Cache"),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        scanner.clearCache();
                        selectedIndex = -1;
                        scrollOffset = 0;
                    }
                }
        ));

        this.zoomInButton = this.addButton(new ButtonWidget(
                207, bottomY, 68, 20,
                Text.of("Radar +"),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        radarRangeBlocks = Math.max(64.0D, radarRangeBlocks * 0.75D);
                    }
                }
        ));

        this.zoomOutButton = this.addButton(new ButtonWidget(
                280, bottomY, 68, 20,
                Text.of("Radar -"),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        radarRangeBlocks = Math.min(2048.0D, radarRangeBlocks / 0.75D);
                    }
                }
        ));

        this.copyCoordsButton = this.addButton(new ButtonWidget(
                353, bottomY, 105, 20,
                Text.of("Copy Selected XYZ"),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        copySelectedStructureCoords();
                    }
                }
        ));
    }

    private Text getScanButtonLabel(ModConfig config) {
        return Text.of("Scanning: " + (config.scanningEnabled ? "ON" : "OFF"));
    }

    private Text getHudButtonLabel(ModConfig config) {
        return Text.of("HUD Alert: " + (config.hudBannerEnabled ? "ON" : "OFF"));
    }

    private Text getFilterButtonLabel() {
        if (filterCategoryIndex < 0) {
            return Text.of("Filter: ALL Structures");
        }
        StructureInfo.StructureCategory cat = StructureInfo.StructureCategory.values()[filterCategoryIndex];
        return Text.of("Filter: " + cat.getDisplayName());
    }

    private StructureInfo.StructureCategory getActiveCategoryFilter() {
        if (filterCategoryIndex < 0) {
            return null;
        }
        StructureInfo.StructureCategory[] all = StructureInfo.StructureCategory.values();
        if (filterCategoryIndex < all.length) {
            return all[filterCategoryIndex];
        }
        return null;
    }

    private void copySelectedStructureCoords() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) {
            return;
        }
        List<StructureInfo> list = scanner.getFilteredStructuresSorted(
                mc.player.getX(),
                mc.player.getZ(),
                getActiveCategoryFilter()
        );
        if (selectedIndex >= 0 && selectedIndex < list.size()) {
            StructureInfo info = list.get(selectedIndex);
            String coords = info.getX() + " " + info.getY() + " " + info.getZ();
            mc.keyboard.setClipboard(coords);
            mc.player.sendMessage(new LiteralText("\u00a7a[StructureRadar] Copied coordinates to clipboard: \u00a7f" + coords), false);
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null) {
            super.render(matrices, mouseX, mouseY, delta);
            return;
        }

        // Outer dark panel backdrop
        DrawableHelper.fill(matrices, 8, 8, this.width - 8, this.height - 6, 0xD80D131A);
        drawHollowBorder(matrices, 8, 8, this.width - 16, this.height - 14, 0xFF2C3E50);

        // Title bar
        DrawableHelper.drawCenteredText(
                matrices,
                this.textRenderer,
                new LiteralText("STRUCTURERADAR 1.16.5  |  Render Distance: " + mc.options.viewDistance
                        + " chunks  |  Scanned: " + scanner.getScannedChunkCount() + " chunks"),
                this.width / 2,
                14,
                0xFF00E5FF
        );

        int contentTop = 54;
        int contentBottom = this.height - 34;
        int radarPanelSize = Math.min(contentBottom - contentTop, Math.max(130, (this.width - 36) / 3));

        int radarLeft = 14;
        int radarRight = radarLeft + radarPanelSize;
        int listLeft = radarRight + 10;
        int listRight = this.width - 14;

        List<StructureInfo> structures = scanner.getFilteredStructuresSorted(
                mc.player.getX(),
                mc.player.getZ(),
                getActiveCategoryFilter()
        );

        // 1. Render 2D Dark Radar Display on the Left
        renderDarkRadarPanel(matrices, mc, structures, radarLeft, contentTop, radarPanelSize);

        // 2. Render Scrollable Sorted Structure List on the Right
        renderStructureListPanel(matrices, mc, structures, listLeft, contentTop, listRight, contentBottom, mouseX, mouseY);

        super.render(matrices, mouseX, mouseY, delta);
    }

    private void renderDarkRadarPanel(
            MatrixStack matrices,
            MinecraftClient mc,
            List<StructureInfo> structures,
            int x,
            int y,
            int size
    ) {
        DrawableHelper.fill(matrices, x, y, x + size, y + size, 0xF0080C11);
        drawHollowBorder(matrices, x, y, size, size, 0xFF00B8D4);

        int centerX = x + size / 2;
        int centerY = y + size / 2;
        int radius = (size / 2) - 8;

        // Crosshair axes & range rings
        DrawableHelper.fill(matrices, x + 4, centerY, x + size - 4, centerY + 1, 0x3300E5FF);
        DrawableHelper.fill(matrices, centerX, y + 4, centerX + 1, y + size - 4, 0x3300E5FF);
        drawHollowBorder(matrices, centerX - radius / 2, centerY - radius / 2, radius, radius, 0x2200E5FF);

        // Render-distance square indicator inside radar
        double renderDistBlocks = mc.options.viewDistance * 16.0D;
        int rdHalfPx = (int) Math.round((renderDistBlocks / radarRangeBlocks) * radius);
        if (rdHalfPx > 4 && rdHalfPx < radius) {
            drawHollowBorder(matrices, centerX - rdHalfPx, centerY - rdHalfPx, rdHalfPx * 2, rdHalfPx * 2, 0x662ECC71);
        }

        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();

        // Plot structure blips relative to player position (North-up 2D radar)
        for (int i = 0; i < structures.size(); i++) {
            StructureInfo s = structures.get(i);
            double dx = (s.getX() - playerX) / radarRangeBlocks;
            double dz = (s.getZ() - playerZ) / radarRangeBlocks;

            int blipX = centerX + (int) Math.round(dx * radius);
            int blipY = centerY + (int) Math.round(dz * radius);

            blipX = MathHelper.clamp(blipX, x + 5, x + size - 5);
            blipY = MathHelper.clamp(blipY, y + 5, y + size - 5);

            int color = s.getCategory().getColorArgb();
            if (i == selectedIndex) {
                DrawableHelper.fill(matrices, blipX - 4, blipY - 4, blipX + 5, blipY + 5, 0xFFFFFFFF);
            }
            DrawableHelper.fill(matrices, blipX - 2, blipY - 2, blipX + 3, blipY + 3, color);
        }

        // Player center dot
        DrawableHelper.fill(matrices, centerX - 2, centerY - 2, centerX + 3, centerY + 3, 0xFF00FF88);

        // Cardinal labels & range footer
        DrawableHelper.drawCenteredString(matrices, this.textRenderer, "N", centerX, y + 3, 0xFF7F8C8D);
        DrawableHelper.drawCenteredString(matrices, this.textRenderer, "S", centerX, y + size - 11, 0xFF7F8C8D);
        String rangeLabel = String.format("Range: %d blocks", (int) radarRangeBlocks);
        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, rangeLabel, x + 5, y + size - 11, 0xFFBDC3C7);
    }

    private void renderStructureListPanel(
            MatrixStack matrices,
            MinecraftClient mc,
            List<StructureInfo> structures,
            int left,
            int top,
            int right,
            int bottom,
            int mouseX,
            int mouseY
    ) {
        DrawableHelper.fill(matrices, left, top, right, bottom, 0xE6101720);
        drawHollowBorder(matrices, left, top, right - left, bottom - top, 0xFF34495E);

        // Column header bar
        DrawableHelper.fill(matrices, left + 1, top + 1, right - 1, top + 18, 0xFF1B2631);
        DrawableHelper.drawStringWithShadow(
                matrices,
                this.textRenderer,
                "DETECTED STRUCTURES (" + structures.size() + ") - Sorted by Distance",
                left + 8,
                top + 5,
                0xFF00E5FF
        );

        int listStartY = top + 22;
        int rowHeight = 22;
        int maxRows = Math.max(1, (bottom - listStartY - 4) / rowHeight);

        if (structures.isEmpty()) {
            DrawableHelper.drawCenteredString(
                    matrices,
                    this.textRenderer,
                    "No matching structures detected in render distance yet.",
                    (left + right) / 2,
                    (top + bottom) / 2 - 6,
                    0xFF95A5A6
            );
            return;
        }

        scrollOffset = MathHelper.clamp(scrollOffset, 0, Math.max(0, structures.size() - maxRows));

        double px = mc.player.getX();
        double pz = mc.player.getZ();

        for (int row = 0; row < maxRows; row++) {
            int idx = scrollOffset + row;
            if (idx >= structures.size()) {
                break;
            }
            StructureInfo info = structures.get(idx);
            int rowTop = listStartY + row * rowHeight;
            int rowBottom = rowTop + rowHeight - 2;

            boolean hovered = mouseX >= left + 4 && mouseX <= right - 4 && mouseY >= rowTop && mouseY <= rowBottom;
            boolean selected = (idx == selectedIndex);

            int bgColor = selected ? 0xFF243B53 : (hovered ? 0xFF1C2B3A : 0xAA141E28);
            DrawableHelper.fill(matrices, left + 4, rowTop, right - 4, rowBottom, bgColor);

            // Category color bar on the left edge of the row
            int catColor = info.getCategory().getColorArgb();
            DrawableHelper.fill(matrices, left + 4, rowTop, left + 8, rowBottom, catColor);

            int distBlocks = (int) Math.round(info.getHorizontalDistanceTo(px, pz));
            String titleLine = "[" + info.getCategory().getShortCode() + "] " + info.getCategory().getDisplayName()
                    + "  —  " + distBlocks + " blocks away";
            DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, titleLine, left + 13, rowTop + 2, catColor);

            String detailLine = info.getFormattedCoordinates()
                    + "   |   " + info.getDetectionSource()
                    + " (" + info.getFormattedTime() + ")";
            DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, detailLine, left + 13, rowTop + 11, 0xFFD5D8DC);
        }
    }

    private void drawHollowBorder(MatrixStack matrices, int x, int y, int width, int height, int color) {
        DrawableHelper.fill(matrices, x, y, x + width, y + 1, color);
        DrawableHelper.fill(matrices, x, y + height - 1, x + width, y + height, color);
        DrawableHelper.fill(matrices, x, y + 1, x + 1, y + height - 1, color);
        DrawableHelper.fill(matrices, x + width - 1, y + 1, x + width, y + height - 1, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) {
            return false;
        }

        int contentTop = 54;
        int contentBottom = this.height - 34;
        int radarPanelSize = Math.min(contentBottom - contentTop, Math.max(130, (this.width - 36) / 3));
        int listLeft = 14 + radarPanelSize + 10;
        int listRight = this.width - 14;
        int listStartY = contentTop + 22;
        int rowHeight = 22;

        if (mouseX >= listLeft + 4 && mouseX <= listRight - 4 && mouseY >= listStartY && mouseY <= contentBottom) {
            int clickedRow = ((int) mouseY - listStartY) / rowHeight;
            List<StructureInfo> structures = scanner.getFilteredStructuresSorted(
                    mc.player.getX(),
                    mc.player.getZ(),
                    getActiveCategoryFilter()
            );
            int idx = scrollOffset + clickedRow;
            if (idx >= 0 && idx < structures.size()) {
                selectedIndex = idx;
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int delta = amount > 0 ? -1 : (amount < 0 ? 1 : 0);
        scrollOffset = Math.max(0, scrollOffset + delta);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
