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
 * Ultra-compact 256x152 modal GUI for StructureRadar (Minecraft 1.16.5).
 * Features a small 72x72 player-relative radar scope locked to the player's exact Render Distance
 * and a clean structure list showing verified centerpiece coordinates.
 */
public class StructureRadarScreen extends Screen {

    private static final int WINDOW_WIDTH = 256;
    private static final int WINDOW_HEIGHT = 152;
    private static final int RADAR_SIZE = 72;

    private final ChunkScanner scanner;

    private int winX;
    private int winY;
    private int filterCategoryIndex = -1;
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
        int btnY = this.winY + WINDOW_HEIGHT - 22;
        int startX = this.winX + 6;

        this.addButton(new ButtonWidget(
                startX, btnY, 56, 16,
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
                startX + 59, btnY, 68, 16,
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
                startX + 130, btnY, 58, 16,
                Text.of("Copy XYZ"),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        copyTrackedCoords();
                    }
                }
        ));

        this.addButton(new ButtonWidget(
                startX + 191, btnY, 53, 16,
                Text.of("Rescan"),
                new ButtonWidget.PressAction() {
                    @Override
                    public void onPress(ButtonWidget button) {
                        scanner.rescanAroundPlayer();
                        scrollOffset = 0;
                    }
                }
        ));
    }

    private Text getScanLabel(ModConfig cfg) {
        return Text.of("Scan:" + (cfg.scanningEnabled ? "\u00a7aON" : "\u00a7cOFF"));
    }

    private Text getFilterLabel() {
        if (filterCategoryIndex < 0) return Text.of("Filter: All");
        StructureInfo.StructureCategory cat = StructureInfo.StructureCategory.values()[filterCategoryIndex];
        return Text.of("Filter: " + cat.getShortCode());
    }

    private StructureInfo.StructureCategory getActiveCategoryFilter() {
        if (filterCategoryIndex < 0) return null;
        StructureInfo.StructureCategory[] all = StructureInfo.StructureCategory.values();
        return filterCategoryIndex < all.length ? all[filterCategoryIndex] : null;
    }

    private void copyTrackedCoords() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return;
        int rd = Math.max(2, mc.options.viewDistance);
        StructureInfo target = scanner.getPrimaryTargetInRenderDistance(mc.player.getX(), mc.player.getZ(), rd);
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

        int rdChunks = Math.max(2, mc.options.viewDistance);
        double rdBlocks = rdChunks * 16.0D;

        // Compact 256x152 window backdrop
        DrawableHelper.fill(matrices, winX, winY, winX + WINDOW_WIDTH, winY + WINDOW_HEIGHT, 0xEE10161E);
        drawBorder(matrices, winX, winY, WINDOW_WIDTH, WINDOW_HEIGHT, 0xFF2A3B4C);

        // Slim 16px title bar
        DrawableHelper.fill(matrices, winX + 1, winY + 1, winX + WINDOW_WIDTH - 1, winY + 16, 0xFF16202B);
        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, "STRUCTURE RADAR", winX + 6, winY + 4, 0xFF00E5FF);

        String rdLabel = "RD: " + rdChunks + " chunks (" + (int) rdBlocks + "m)";
        int rdW = this.textRenderer.getWidth(rdLabel);
        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, rdLabel, winX + WINDOW_WIDTH - rdW - 6, winY + 4, 0xFF90A4AE);

        List<StructureInfo> structures = scanner.getStructuresInRenderDistanceSorted(
                mc.player.getX(), mc.player.getZ(), rdChunks, getActiveCategoryFilter()
        );
        StructureInfo activeTarget = scanner.getPrimaryTargetInRenderDistance(mc.player.getX(), mc.player.getZ(), rdChunks);

        int radarX = winX + 6;
        int radarY = winY + 20;

        // 1. Small 72x72 Directional Radar Scope (locked 1:1 to Render Distance)
        renderSmallRadarScope(matrices, mc, structures, activeTarget, radarX, radarY, RADAR_SIZE, rdBlocks);

        // 2. Small Target Readout below the 72x72 radar
        renderMiniBearingBox(matrices, mc, activeTarget, radarX, radarY + RADAR_SIZE + 3, RADAR_SIZE, 29);

        // 3. Right-side Verified Structure List (166x104)
        int listX = radarX + RADAR_SIZE + 6;
        int listY = radarY;
        int listW = WINDOW_WIDTH - RADAR_SIZE - 18;
        int listH = 104;
        renderStructureList(matrices, mc, structures, activeTarget, listX, listY, listW, listH, mouseX, mouseY);

        super.render(matrices, mouseX, mouseY, delta);
    }

    private void renderSmallRadarScope(
            MatrixStack matrices,
            MinecraftClient mc,
            List<StructureInfo> structures,
            StructureInfo activeTarget,
            int x,
            int y,
            int size,
            double renderDistBlocks
    ) {
        DrawableHelper.fill(matrices, x, y, x + size, y + size, 0xFF090D12);
        drawBorder(matrices, x, y, size, size, 0xFF243447);

        int cx = x + size / 2;
        int cy = y + size / 2;
        int maxRadius = (size / 2) - 7;

        DrawableHelper.fill(matrices, x + 4, cy, x + size - 4, cy + 1, 0x2200E5FF);
        DrawableHelper.fill(matrices, cx, y + 4, cx + 1, y + size - 4, 0x2200E5FF);

        double px = mc.player.getX();
        double pz = mc.player.getZ();
        float yaw = mc.player.yaw;

        // Rotating North ('N') indicator around the 72x72 radar edge
        double northRad = Math.toRadians(MathHelper.wrapDegrees(180.0D - yaw));
        int nx = cx + (int) Math.round(Math.sin(northRad) * (maxRadius + 2)) - 2;
        int ny = cy - (int) Math.round(Math.cos(northRad) * (maxRadius + 2)) - 3;
        DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, "N", nx, ny, 0xFFFF5252);

        for (int i = 0; i < structures.size(); i++) {
            StructureInfo s = structures.get(i);
            double dist = s.getHorizontalDistanceTo(px, pz);
            double relDeg = s.getRelativeAngleDegrees(px, pz, yaw);
            double rad = Math.toRadians(relDeg);

            double norm = Math.min(1.0D, dist / renderDistBlocks);
            int bx = cx + (int) Math.round(Math.sin(rad) * norm * maxRadius);
            int by = cy - (int) Math.round(Math.cos(rad) * norm * maxRadius);

            int color = s.getCategory().getColorArgb();
            if (s.equals(activeTarget)) {
                drawLine(matrices, cx, cy, bx, by, (color & 0x00FFFFFF) | 0xAA000000);
                DrawableHelper.fill(matrices, bx - 2, by - 2, bx + 3, by + 3, 0xFFFFFFFF);
            }
            DrawableHelper.fill(matrices, bx - 1, by - 1, bx + 2, by + 2, color);
        }

        // Player center dot (UP = player look direction)
        DrawableHelper.fill(matrices, cx - 1, cy - 1, cx + 2, cy + 2, 0xFF00E676);
        DrawableHelper.fill(matrices, cx, cy - 3, cx + 1, cy - 1, 0xFFFFFFFF);
    }

    private void renderMiniBearingBox(
            MatrixStack matrices,
            MinecraftClient mc,
            StructureInfo target,
            int x,
            int y,
            int w,
            int h
    ) {
        DrawableHelper.fill(matrices, x, y, x + w, y + h, 0xFF131B24);
        drawBorder(matrices, x, y, w, h, 0xFF243447);

        if (target == null) {
            DrawableHelper.drawCenteredString(matrices, this.textRenderer, "None in RD", x + w / 2, y + 10, 0xFF607D8B);
            return;
        }

        double px = mc.player.getX();
        double pz = mc.player.getZ();
        String arrow = target.getRelativeDirectionArrow(px, pz, mc.player.yaw);
        String cardinal = target.getWorldCardinalFromPlayer(px, pz);
        int dist = (int) Math.round(target.getHorizontalDistanceTo(px, pz));

        DrawableHelper.drawCenteredString(
                matrices, this.textRenderer,
                arrow + " " + cardinal + " (" + dist + "m)",
                x + w / 2, y + 4, target.getCategory().getColorArgb()
        );
        DrawableHelper.drawCenteredString(
                matrices, this.textRenderer,
                "Y: " + target.getY(),
                x + w / 2, y + 16, 0xFFCFD8DC
        );
    }

    private void renderStructureList(
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
        DrawableHelper.fill(matrices, x, y, x + w, y + h, 0xFF0B1016);
        drawBorder(matrices, x, y, w, h, 0xFF243447);

        int rowH = 20;
        int maxRows = (h - 4) / rowH;

        if (structures.isEmpty()) {
            DrawableHelper.drawCenteredString(
                    matrices, this.textRenderer,
                    "No structures in Render Distance",
                    x + w / 2, y + h / 2 - 4, 0xFF607D8B
            );
            return;
        }

        scrollOffset = MathHelper.clamp(scrollOffset, 0, Math.max(0, structures.size() - maxRows));

        double px = mc.player.getX();
        double pz = mc.player.getZ();
        float yaw = mc.player.yaw;

        for (int i = 0; i < maxRows; i++) {
            int idx = scrollOffset + i;
            if (idx >= structures.size()) break;

            StructureInfo s = structures.get(idx);
            int ry = y + 2 + i * rowH;
            boolean hovered = mouseX >= x + 2 && mouseX <= x + w - 2 && mouseY >= ry && mouseY < ry + rowH - 1;
            boolean isTracked = s.equals(activeTarget);

            int bg = isTracked ? 0xFF1D3144 : (hovered ? 0xFF152331 : 0xFF10171F);
            DrawableHelper.fill(matrices, x + 2, ry, x + w - 2, ry + rowH - 1, bg);
            DrawableHelper.fill(matrices, x + 2, ry, x + 4, ry + rowH - 1, s.getCategory().getColorArgb());

            String arrow = s.getRelativeDirectionArrow(px, pz, yaw);
            String cardinal = s.getWorldCardinalFromPlayer(px, pz);
            int dist = (int) Math.round(s.getHorizontalDistanceTo(px, pz));

            String line1 = arrow + " " + s.getCategory().getDisplayName() + " \u00a77(" + cardinal + ")";
            String distLabel = dist + "m";
            int distW = this.textRenderer.getWidth(distLabel);

            DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, line1, x + 7, ry + 2, s.getCategory().getColorArgb());
            DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, distLabel, x + w - distW - 5, ry + 2, 0xFFFFFFFF);
            DrawableHelper.drawStringWithShadow(matrices, this.textRenderer, s.getCompactCoords(), x + 7, ry + 11, 0xFFB0BEC5);
        }
    }

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
        if (super.mouseClicked(mouseX, mouseY, button)) return true;

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null) return false;

        int listX = winX + 6 + RADAR_SIZE + 6;
        int listY = winY + 20 + 2;
        int listW = WINDOW_WIDTH - RADAR_SIZE - 18;
        int listH = 104;
        int rowH = 20;

        if (mouseX >= listX && mouseX <= listX + listW && mouseY >= listY && mouseY <= listY + listH) {
            int clickedRow = ((int) mouseY - listY) / rowH;
            int rd = Math.max(2, mc.options.viewDistance);
            List<StructureInfo> structures = scanner.getStructuresInRenderDistanceSorted(
                    mc.player.getX(), mc.player.getZ(), rd, getActiveCategoryFilter()
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
        int delta = amount > 0 ? -1 : (amount < 0 ? 1 : 0);
        scrollOffset = Math.max(0, scrollOffset + delta);
        return true;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
