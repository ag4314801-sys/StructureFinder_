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
 * Compact, centered 320x196 floating modal GUI for StructureRadar (Minecraft 1.16.5).
 *
 * Design Improvements:
 * - Occupies a small centered 320x196 window instead of covering the entire screen.
 * - Left side features a Player-Yaw-Synced Directional Radar Scope (UP = exact direction player is looking)
 *   with rotating N/E/S/W compass ring and a directional guidance line to the locked/nearest structure.
 * - Right side features a clean, scannable list with live directional arrows (▲ ↗ ▶ ↘ ▼ ↙ ◀ ↖),
 *   cardinal bearings, distances, and one-click HUD locking or coordinate copying.
 */
public class StructureRadarScreen extends Screen {

    private static final int WINDOW_WIDTH = 320;
    private static final int WINDOW_HEIGHT = 196;

    private final ChunkScanner scanner;

    private int winX;
    private int winY;
    private int filterCategoryIndex = -1; // -1 = All
    private int scrollOffset = 0;

    public StructureRadarScreen(ChunkScanner scanner) {
        super(Text.of("StructureRadar"));
        this.scanner = scanner;
    }

    @Override
    protected void init() {
        super.init();
        this.winX = (this.width - WINDOW_WIDTH) / 2;
        this.winY = (this.height - WINDOW_HEIGHT) / 2;

        final ModConfig config = ModConfig.get();
        int btnY = this.winY + WINDOW_HEIGHT - 24;
        int btnW = 73;
        int gap = 4;
        int startX = this.winX + 8;

        // 4 clean, non-overlapping buttons in a single compact bottom bar
        this.addButton(new ButtonWidget(
                startX, btnY, btnW, 18,
                getScanLabel(config),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        config.scanningEnabled = !config.scanningEnabled;
                        config.save();
                        button.setMessage(getScanLabel(config));
                    }
                }
        ));

        this.addButton(new ButtonWidget(
                startX + (btnW + gap), btnY, btnW + 8, 18,
                getFilterLabel(),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        StructureInfo.StructureCategory[] all = StructureInfo.StructureCategory.values();
                        filterCategoryIndex++;
                        if (filterCategoryIndex >= all.length) {
                            filterCategoryIndex = -1;
                        }
                        scrollOffset = 0;
                        button.setMessage(getFilterLabel());
                    }
                }
        ));

        this.addButton(new ButtonWidget(
                startX + (btnW + gap) * 2 + 8, btnY, btnW - 4, 18,
                Text.of("Copy XYZ"),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        copyTrackedStructureCoords();
                    }
                }
        ));

        this.addButton(new ButtonWidget(
                startX + (btnW + gap) * 3 + 4, btnY, btnW - 4, 18,
                Text.of("Clear"),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        scanner.clearCache();
                        scrollOffset = 0;
                    }
                }
        ));
    }

    private Text getScanLabel(ModConfig cfg) {
        return Text.of("Scan: " + (cfg.scanningEnabled ? "\u00a7aON" : "\u00a7cOFF"));
    }

    private Text getFilterLabel() {
        if (filterCategoryIndex < 0) {
            return Text.of("Type: All");
        }
        StructureInfo.StructureCategory cat = StructureInfo.StructureCategory.values()[filterCategoryIndex];
        return Text.of(cat.getShortCode() + ": " + cat.getDisplayName());
    }

    private StructureInfo.StructureCategory getActiveCategoryFilter() {
        if (filterCategoryIndex < 0) return null;
        StructureInfo.StructureCategory[] all = StructureInfo.StructureCategory.values();
        return filterCategoryIndex < all.length ? all[filterCategoryIndex] : null;
    }

    private void copyTrackedStructureCoords() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        StructureInfo target = scanner.getPrimaryTarget(mc.player.getX(), mc.player.getZ());
        if (target != null) {
            String coords = target.getX() + " " + target.getY() + " " + target.getZ();
            mc.keyboard.setClipboard(coords);
            mc.player.sendMessage(new LiteralText("\u00a7b[Radar] \u00a7fCopied \u00a7e"
                    + target.getCategory().getDisplayName() + "\u00a7f XYZ: \u00a7a" + coords), true);
        }
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null) {
            super.render(matrices, mouseX, mouseY, delta);
            return;
        }

        // Subtle shadow behind the compact 320x196 modal window (leaves game world visible)
        DrawableHelper.fill(matrices, winX + 3, winY + 3, winX + WINDOW_WIDTH + 3, winY + WINDOW_HEIGHT + 3, 0x66000000);
        DrawableHelper.fill(matrices, winX, winY, winX + WINDOW_WIDTH, winY + WINDOW_HEIGHT, 0xF210161E);
        drawBorder(matrices, winX, winY, WINDOW_WIDTH, WINDOW_HEIGHT, 0xFF2A3B4C);

        // Compact Header Bar (20px tall)
        DrawableHelper.fill(matrices, winX + 1, winY + 1, winX + WINDOW_WIDTH - 1, winY + 20, 0xFF16202B);
        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, "STRUCTURE RADAR", winX + 8, winY + 6, 0xFF00E5FF);

        String stats = "RD: " + mc.options.viewDistance + "c  |  Scanned: " + scanner.getScannedChunkCount();
        int statsW = this.textRenderer.getWidth(stats);
        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, stats, winX + WINDOW_WIDTH - statsW - 8, winY + 6, 0xFF95A5A6);

        List<StructureInfo> structures = scanner.getFilteredStructuresSorted(
                mc.player.getX(), mc.player.getZ(), getActiveCategoryFilter()
        );
        StructureInfo activeTarget = scanner.getPrimaryTarget(mc.player.getX(), mc.player.getZ());

        // Left Column: Player-Centric Directional Radar Scope (104x104)
        int radarX = winX + 8;
        int radarY = winY + 26;
        int radarSize = 104;
        renderDirectionalRadarScope(matrices, mc, structures, activeTarget, radarX, radarY, radarSize);

        // Below Radar: Active Target Direction Summary Box
        renderTargetBearingCard(matrices, mc, activeTarget, radarX, radarY + radarSize + 4, radarSize, 32);

        // Right Column: Compact Scrollable Structure List (192x140)
        int listX = radarX + radarSize + 8;
        int listY = radarY;
        int listW = WINDOW_WIDTH - (radarSize + 24);
        int listH = 140;
        renderCompactStructureList(matrices, mc, structures, activeTarget, listX, listY, listW, listH, mouseX, mouseY);

        super.render(matrices, mouseX, mouseY, delta);
    }

    /**
     * Renders a player-centric radar where UP (top of radar) is ALWAYS the exact direction
     * the player is currently facing (player.yaw).
     */
    private void renderDirectionalRadarScope(
            MatrixStack matrices,
            MinecraftClient mc,
            List<StructureInfo> structures,
            StructureInfo activeTarget,
            int x,
            int y,
            int size
    ) {
        DrawableHelper.fill(matrices, x, y, x + size, y + size, 0xFF090D12);
        drawBorder(matrices, x, y, size, size, 0xFF243447);

        int cx = x + size / 2;
        int cy = y + size / 2;
        int maxRadius = (size / 2) - 10;

        // Crosshairs & inner range ring
        DrawableHelper.fill(matrices, x + 6, cy, x + size - 6, cy + 1, 0x2200E5FF);
        DrawableHelper.fill(matrices, cx, y + 6, cx + 1, y + size - 6, 0x2200E5FF);
        drawBorder(matrices, cx - maxRadius / 2, cy - maxRadius / 2, maxRadius, maxRadius, 0x1E00E5FF);

        // Forward view cone lines (shows player's field of view pointing UP)
        drawLine(matrices, cx, cy, cx - 18, y + 8, 0x2800FF88);
        drawLine(matrices, cx, cy, cx + 18, y + 8, 0x2800FF88);

        double playerX = mc.player.getX();
        double playerZ = mc.player.getZ();
        float playerYaw = mc.player.yaw;
        double range = ModConfig.get().radarZoomRange;

        // Draw rotating N, E, S, W cardinal markers around the radar perimeter
        // In Minecraft: North is yaw 180, East is -90, South is 0, West is +90
        drawRotatedCardinal(matrices, "N", 180.0D - playerYaw, cx, cy, maxRadius + 4, 0xFFFF5252);
        drawRotatedCardinal(matrices, "E", -90.0D - playerYaw, cx, cy, maxRadius + 4, 0xFF7F8C8D);
        drawRotatedCardinal(matrices, "S", 0.0D - playerYaw, cx, cy, maxRadius + 4, 0xFF7F8C8D);
        drawRotatedCardinal(matrices, "W", 90.0D - playerYaw, cx, cy, maxRadius + 4, 0xFF7F8C8D);

        // Plot structure blips rotated by player.yaw so UP = straight ahead of player
        for (int i = 0; i < structures.size(); i++) {
            StructureInfo s = structures.get(i);
            double dist = s.getHorizontalDistanceTo(playerX, playerZ);
            double relDeg = s.getRelativeAngleDegrees(playerX, playerZ, playerYaw);
            double rad = Math.toRadians(relDeg);

            double norm = Math.min(1.0D, dist / range);
            int bx = cx + (int) Math.round(Math.sin(rad) * norm * maxRadius);
            int by = cy - (int) Math.round(Math.cos(rad) * norm * maxRadius);

            int color = s.getCategory().getColorArgb();
            if (s.equals(activeTarget)) {
                // Draw direct pointer line from player center to the locked/nearest target
                drawLine(matrices, cx, cy, bx, by, (color & 0x00FFFFFF) | 0xAA000000);
                DrawableHelper.fill(matrices, bx - 3, by - 3, bx + 4, by + 4, 0xFFFFFFFF);
            }
            DrawableHelper.fill(matrices, bx - 2, by - 2, bx + 3, by + 3, color);
        }

        // Player center pointer (▲ facing UP = player's look direction)
        DrawableHelper.fill(matrices, cx - 2, cy - 2, cx + 3, cy + 3, 0xFF00E676);
        DrawableHelper.fill(matrices, cx, cy - 4, cx + 1, cy - 2, 0xFFFFFFFF);

        // Zoom scale label in bottom-left of radar
        String rangeText = (int) range + "m";
        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, rangeText, x + 3, y + size - 10, 0xFF607D8B);
    }

    private void drawRotatedCardinal(MatrixStack matrices, String letter, double relDegrees, int cx, int cy, int radius, int color) {
        double rad = Math.toRadians(MathHelper.wrapDegrees(relDegrees));
        int lx = cx + (int) Math.round(Math.sin(rad) * radius) - 2;
        int ly = cy - (int) Math.round(Math.cos(rad) * radius) - 3;
        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, letter, lx, ly, color);
    }

    private void renderTargetBearingCard(
            MatrixStack matrices,
            MinecraftClient mc,
            StructureInfo target,
            int x,
            int y,
            int w,
            int h
    ) {
        DrawableHelper.fill(matrices, x, y, x + w, y + h, 0xFF141D26);
        drawBorder(matrices, x, y, w, h, 0xFF243447);

        if (target == null) {
            DrawableHelper.drawCenteredString(matrices, this.textRenderer, "No Target", x + w / 2, y + 12, 0xFF7F8C8D);
            return;
        }

        double px = mc.player.getX();
        double pz = mc.player.getZ();
        float yaw = mc.player.yaw;

        String arrow = target.getRelativeDirectionArrow(px, pz, yaw);
        String cardinal = target.getWorldCardinalFromPlayer(px, pz);
        int dist = (int) Math.round(target.getHorizontalDistanceTo(px, pz));

        String line1 = arrow + " " + target.getCategory().getDisplayName();
        if (line1.length() > 16) line1 = line1.substring(0, 16);
        String line2 = dist + "m (" + cardinal + ")  Y:" + target.getY();

        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, line1, x + 5, y + 5, target.getCategory().getColorArgb());
        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, line2, x + 5, y + 18, 0xFFECF0F1);
    }

    private void renderCompactStructureList(
            MatrixStack matrices,
            MinecraftClient mc,
            List<StructureInfo> structures,
            StructureInfo activeTarget,
            int x,
            int y,
            int w,
            int h,
            int mouseX,
            int mouseY
    ) {
        DrawableHelper.fill(matrices, x, y, x + w, y + h, 0xFF0C1117);
        drawBorder(matrices, x, y, w, h, 0xFF243447);

        // Header strip
        DrawableHelper.fill(matrices, x + 1, y + 1, x + w - 1, y + 15, 0xFF16202B);
        DrawableHelper.drawStringWithShadow(
                matrices, this.textRenderer,
                "NEARBY (" + structures.size() + ") - Click to Track",
                x + 6, y + 4, 0xFFB0BEC5
        );

        int listTop = y + 17;
        int rowH = 20;
        int maxVisible = (h - 19) / rowH;

        if (structures.isEmpty()) {
            DrawableHelper.drawCenteredString(
                    matrices, this.textRenderer,
                    "Scanning chunks in render distance...",
                    x + w / 2, y + h / 2 - 4, 0xFF607D8B
            );
            return;
        }

        scrollOffset = MathHelper.clamp(scrollOffset, 0, Math.max(0, structures.size() - maxVisible));

        double px = mc.player.getX();
        double pz = mc.player.getZ();
        float yaw = mc.player.yaw;

        for (int i = 0; i < maxVisible; i++) {
            int idx = scrollOffset + i;
            if (idx >= structures.size()) break;

            StructureInfo s = structures.get(idx);
            int ry = listTop + i * rowH;
            boolean hovered = mouseX >= x + 2 && mouseX <= x + w - 2 && mouseY >= ry && mouseY < ry + rowH - 1;
            boolean isTracked = s.equals(activeTarget);

            int bg = isTracked ? 0xFF1E3246 : (hovered ? 0xFF162432 : 0xFF10171F);
            DrawableHelper.fill(matrices, x + 2, ry, x + w - 2, ry + rowH - 1, bg);

            int catColor = s.getCategory().getColorArgb();
            DrawableHelper.fill(matrices, x + 2, ry, x + 5, ry + rowH - 1, catColor);

            String arrow = s.getRelativeDirectionArrow(px, pz, yaw);
            String cardinal = s.getWorldCardinalFromPlayer(px, pz);
            int dist = (int) Math.round(s.getHorizontalDistanceTo(px, pz));

            String topRow = arrow + " " + s.getCategory().getDisplayName() + " \u00a77(" + cardinal + ")";
            String distStr = dist + "m";
            int distW = this.textRenderer.getWidth(distStr);

            DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, topRow, x + 9, ry + 2, catColor);
            DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, distStr, x + w - distW - 6, ry + 2, 0xFFFFFFFF);

            String bottomRow = "XYZ: " + s.getCompactCoords() + (isTracked ? "  \u00a7a[TRACKING]" : "");
            DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, bottomRow, x + 9, ry + 11, 0xFF90A4AE);
        }
    }

    /**
     * Draws a lightweight Bresenham-style pixel line using DrawableHelper.fill.
     */
    private void drawLine(MatrixStack matrices, int x0, int y0, int x1, int y1, int color) {
        int steps = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
        if (steps == 0) return;
        for (int i = 0; i <= steps; i += 2) {
            int px = x0 + (x1 - x0) * i / steps;
            int py = y0 + (y1 - y0) * i / steps;
            DrawableHelper.fill(matrices, px, py, px + 1, py + 1, color);
        }
    }

    private void drawBorder(MatrixStack matrices, int x, int y, int width, int height, int color) {
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
        if (mc == null || mc.player == null) return false;

        int radarX = winX + 8;
        int radarSize = 104;
        int listX = radarX + radarSize + 8;
        int listY = winY + 26 + 17;
        int listW = WINDOW_WIDTH - (radarSize + 24);
        int listH = 140 - 17;
        int rowH = 20;

        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            int clickedRow = ((int) mouseY - listY) / rowH;
            List<StructureInfo> structures = scanner.getFilteredStructuresSorted(
                    mc.player.getX(), mc.player.getZ(), getActiveCategoryFilter()
            );
            int idx = scrollOffset + clickedRow;
            if (idx >= 0 && idx < structures.size()) {
                scanner.setTrackedTarget(structures.get(idx));
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        int radarX = winX + 8;
        int radarY = winY + 26;
        int radarSize = 104;

        // Scroll inside radar scope to zoom in/out smoothly
        if (mouseX >= radarX && mouseX <= radarX + radarSize && mouseY >= radarY && mouseY <= radarY + radarSize) {
            ModConfig cfg = ModConfig.get();
            if (amount > 0) {
                cfg.radarZoomRange = Math.max(64.0D, cfg.radarZoomRange * 0.8D);
            } else if (amount < 0) {
                cfg.radarZoomRange = Math.min(2048.0D, cfg.radarZoomRange / 0.8D);
            }
            cfg.save();
            return true;
        }

        int delta = amount > 0 ? -1 : (amount < 0 ? 1 : 0);
        scrollOffset = Math.max(0, scrollOffset + delta);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
