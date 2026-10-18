package com.cavemapplus.gui;

import com.cavemapplus.map.MapChunkData;
import com.cavemapplus.map.MapEngine;
import com.cavemapplus.scanner.DetectedStructure;
import com.cavemapplus.scanner.DetectedStructure.StructureType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Full-screen interactive pan/zoom World & Cave Map GUI for Minecraft 1.21.11 using DrawContext.
 *
 * Includes:
 * - Click & drag panning and scroll-wheel zooming anchored around the map viewport
 * - Surface Mode / Cave Mode toggle button
 * - Interactive Cave Depth SliderWidget (-64 to +128)
 * - Per-structure filter toggle buttons (Trial Chamber, Ancient City, Fortress, Monument, End City)
 * - Colored structure markers with hover tooltips (Name, X/Y/Z, Distance, Block Breakdown)
 */
public class WorldMapScreen extends Screen {

    private static final int TOP_BAR_HEIGHT = 32;
    private static final int BOTTOM_BAR_HEIGHT = 22;
    private static final int MIN_SLICE_Y = -64;
    private static final int MAX_SLICE_Y = 128;

    private final MapEngine mapEngine;

    private double cameraWorldX;
    private double cameraWorldZ;
    private double zoom = 2.0D; // screen pixels per world block
    private boolean draggingMap = false;

    private ButtonWidget modeToggleButton;
    private SliderWidget caveDepthSlider;

    public WorldMapScreen(MapEngine mapEngine) {
        super(Text.literal("CaveMapPlus World Map"));
        this.mapEngine = mapEngine;
    }

    @Override
    protected void init() {
        super.init();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null && cameraWorldX == 0.0D && cameraWorldZ == 0.0D) {
            this.cameraWorldX = mc.player.getX();
            this.cameraWorldZ = mc.player.getZ();
        }

        int xCursor = 8;
        int topY = 6;

        // 1. Surface / Cave Mode Toggle Button
        this.modeToggleButton = this.addDrawableChild(
                ButtonWidget.builder(getModeButtonText(), button -> {
                    mapEngine.toggleCaveMode();
                    button.setMessage(getModeButtonText());
                }).dimensions(xCursor, topY, 112, 20).build()
        );
        xCursor += 116;

        // 2. Cave Depth Slider (-64 to 128)
        double initialNormalized = MathHelper.clamp(
                (double) (mapEngine.getCaveSliceY() - MIN_SLICE_Y) / (double) (MAX_SLICE_Y - MIN_SLICE_Y),
                0.0D, 1.0D
        );
        this.caveDepthSlider = new SliderWidget(
                xCursor, topY, 118, 20,
                Text.literal("Cave Y: " + mapEngine.getCaveSliceY()),
                initialNormalized
        ) {
            @Override
            protected void updateMessage() {
                int sliceY = MIN_SLICE_Y + (int) Math.round(this.value * (MAX_SLICE_Y - MIN_SLICE_Y));
                this.setMessage(Text.literal("Cave Y-Slice: " + sliceY));
            }

            @Override
            protected void applyValue() {
                int sliceY = MIN_SLICE_Y + (int) Math.round(this.value * (MAX_SLICE_Y - MIN_SLICE_Y));
                mapEngine.setCaveSliceY(sliceY);
            }
        };
        this.addDrawableChild(this.caveDepthSlider);
        xCursor += 124;

        // 3. Structure Filter Toggle Buttons
        for (StructureType type : StructureType.values()) {
            this.addDrawableChild(
                    ButtonWidget.builder(getFilterButtonText(type), button -> {
                        mapEngine.getStructureDetector().toggleFilter(type);
                        button.setMessage(getFilterButtonText(type));
                    }).dimensions(xCursor, topY, 44, 20).build()
            );
            xCursor += 47;
        }
    }

    private Text getModeButtonText() {
        return mapEngine.isCaveMode()
                ? Text.literal("Mode: Cave Slice").formatted(Formatting.AQUA)
                : Text.literal("Mode: Surface").formatted(Formatting.GREEN);
    }

    private Text getFilterButtonText(StructureType type) {
        boolean on = mapEngine.getStructureDetector().isFilterEnabled(type);
        return Text.literal(type.shortCode() + (on ? ":ON" : ":OFF"))
                .formatted(on ? Formatting.GREEN : Formatting.GRAY);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.world == null) {
            super.render(context, mouseX, mouseY, delta);
            return;
        }

        int viewLeft = 0;
        int viewRight = this.width;
        int viewTop = TOP_BAR_HEIGHT;
        int viewBottom = this.height - BOTTOM_BAR_HEIGHT;
        int centerX = (viewLeft + viewRight) / 2;
        int centerY = (viewTop + viewBottom) / 2;

        // 1. Background canvas
        context.fill(0, 0, this.width, this.height, 0xFF0A0E14);

        // 2. Render sampled chunk terrain/cave pixels within active view distance
        int viewDistance = MinecraftClient.getInstance().options.getViewDistance().getValue();
        int playerChunkX = mc.player.getBlockX() >> 4;
        int playerChunkZ = mc.player.getBlockZ() >> 4;

        renderChunkTiles(context, viewLeft, viewTop, viewRight, viewBottom, centerX, centerY, playerChunkX, playerChunkZ, viewDistance);

        // 3. Render active Render Distance bounding box
        renderViewDistanceBounds(context, centerX, centerY, playerChunkX, playerChunkZ, viewDistance);

        // 4. Render Player Marker
        renderPlayerMarker(context, mc, centerX, centerY);

        // 5. Render Structure Pins & capture hovered structure for tooltip
        DetectedStructure hovered = renderStructureMarkers(
                context, mc, mouseX, mouseY, viewLeft, viewTop, viewRight, viewBottom, centerX, centerY,
                playerChunkX, playerChunkZ, viewDistance
        );

        // 6. Top & Bottom chrome bars
        context.fill(0, 0, this.width, TOP_BAR_HEIGHT, 0xE6101720);
        context.fill(0, TOP_BAR_HEIGHT - 1, this.width, TOP_BAR_HEIGHT, 0xFF263648);
        renderBottomStatusBar(context, mc, mouseX, mouseY, centerX, centerY, viewDistance);

        // 7. Render buttons & slider via super.render
        super.render(context, mouseX, mouseY, delta);

        // 8. Hover Tooltip for structure marker
        if (hovered != null) {
            int dist = (int) Math.round(hovered.horizontalDistanceTo(mc.player.getX(), mc.player.getZ()));
            List<Text> tooltip = new ArrayList<>();
            tooltip.add(Text.literal(hovered.type().displayName()).formatted(Formatting.GOLD, Formatting.BOLD));
            tooltip.add(Text.literal(hovered.formattedCoordinates()).formatted(Formatting.WHITE));
            tooltip.add(Text.literal("Distance: " + dist + " blocks").formatted(Formatting.AQUA));
            tooltip.add(Text.literal(hovered.signatureDetail()).formatted(Formatting.GRAY));
            tooltip.add(Text.literal("Left-click to copy coordinates").formatted(Formatting.DARK_GRAY));
            context.drawTooltip(this.textRenderer, tooltip, mouseX, mouseY);
        }
    }

    private void renderChunkTiles(
            DrawContext context,
            int left, int top, int right, int bottom,
            int centerX, int centerY,
            int playerChunkX, int playerChunkZ, int viewDistance
    ) {
        boolean caveMode = mapEngine.isCaveMode();
        int step = zoom < 0.75D ? 4 : (zoom < 1.5D ? 2 : 1);

        Map<Long, MapChunkData> cache = mapEngine.getChunkMapCache();
        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                int cx = playerChunkX + dx;
                int cz = playerChunkZ + dz;
                MapChunkData chunkData = cache.get(ChunkPos.toLong(cx, cz));
                if (chunkData == null) continue;

                int chunkStartX = cx << 4;
                int chunkStartZ = cz << 4;

                int chunkScreenX1 = worldToScreenX(chunkStartX, centerX);
                int chunkScreenX2 = worldToScreenX(chunkStartX + 16, centerX);
                int chunkScreenY1 = worldToScreenY(chunkStartZ, centerY);
                int chunkScreenY2 = worldToScreenY(chunkStartZ + 16, centerY);

                if (chunkScreenX2 < left || chunkScreenX1 > right || chunkScreenY2 < top || chunkScreenY1 > bottom) {
                    continue;
                }

                for (int lz = 0; lz < 16; lz += step) {
                    int sy1 = worldToScreenY(chunkStartZ + lz, centerY);
                    int sy2 = worldToScreenY(chunkStartZ + lz + step, centerY);
                    if (sy2 < top || sy1 > bottom) continue;

                    for (int lx = 0; lx < 16; lx += step) {
                        int sx1 = worldToScreenX(chunkStartX + lx, centerX);
                        int sx2 = worldToScreenX(chunkStartX + lx + step, centerX);
                        if (sx2 < left || sx1 > right) continue;

                        int color = chunkData.getPixelColor(lx, lz, caveMode);
                        context.fill(
                                Math.max(left, sx1),
                                Math.max(top, sy1),
                                Math.min(right, Math.max(sx1 + 1, sx2)),
                                Math.min(bottom, Math.max(sy1 + 1, sy2)),
                                color
                        );
                    }
                }
            }
        }
    }

    private void renderViewDistanceBounds(
            DrawContext context,
            int centerX, int centerY,
            int playerChunkX, int playerChunkZ, int viewDistance
    ) {
        int minBlockX = (playerChunkX - viewDistance) << 4;
        int maxBlockX = (playerChunkX + viewDistance + 1) << 4;
        int minBlockZ = (playerChunkZ - viewDistance) << 4;
        int maxBlockZ = (playerChunkZ + viewDistance + 1) << 4;

        int x1 = worldToScreenX(minBlockX, centerX);
        int y1 = worldToScreenY(minBlockZ, centerY);
        int x2 = worldToScreenX(maxBlockX, centerX);
        int y2 = worldToScreenY(maxBlockZ, centerY);

        drawOutline(context, x1, y1, Math.max(2, x2 - x1), Math.max(2, y2 - y1), 0x8800E5FF);
    }

    private void renderPlayerMarker(DrawContext context, MinecraftClient mc, int centerX, int centerY) {
        int px = worldToScreenX(mc.player.getX(), centerX);
        int pz = worldToScreenY(mc.player.getZ(), centerY);

        context.fill(px - 4, pz - 4, px + 4, pz + 4, 0xFFFFFFFF);
        context.fill(px - 2, pz - 2, px + 2, pz + 2, 0xFF00E676);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("YOU"), px, pz - 12, 0xFF00E676);
    }

    private DetectedStructure renderStructureMarkers(
            DrawContext context,
            MinecraftClient mc,
            int mouseX, int mouseY,
            int left, int top, int right, int bottom,
            int centerX, int centerY,
            int playerChunkX, int playerChunkZ, int viewDistance
    ) {
        DetectedStructure hovered = null;
        List<DetectedStructure> structures = mapEngine.getStructureDetector().getVisibleStructures(
                mapEngine.getActiveDimensionId(), playerChunkX, playerChunkZ, viewDistance
        );

        for (DetectedStructure s : structures) {
            int sx = worldToScreenX(s.pos().getX() + 0.5D, centerX);
            int sy = worldToScreenY(s.pos().getZ() + 0.5D, centerY);
            if (sx < left || sx > right || sy < top || sy > bottom) {
                continue;
            }

            boolean isMouseOver = Math.abs(mouseX - sx) <= 8 && Math.abs(mouseY - sy) <= 8;
            if (isMouseOver) {
                hovered = s;
            }

            int r = isMouseOver ? 8 : 6;
            int color = s.type().colorArgb();

            context.fill(sx - r, sy - r, sx + r, sy + r, 0xEE0D131A);
            drawOutline(context, sx - r, sy - r, r * 2, r * 2, color);
            context.drawCenteredTextWithShadow(
                    this.textRenderer,
                    Text.literal(s.type().shortCode()),
                    sx, sy - 4, color
            );
        }
        return hovered;
    }

    private void renderBottomStatusBar(
            DrawContext context,
            MinecraftClient mc,
            int mouseX, int mouseY,
            int centerX, int centerY,
            int viewDistance
    ) {
        int top = this.height - BOTTOM_BAR_HEIGHT;
        context.fill(0, top, this.width, this.height, 0xE6101720);
        context.fill(0, top, this.width, top + 1, 0xFF263648);

        int cursorX = (int) Math.floor(screenToWorldX(mouseX, centerX));
        int cursorZ = (int) Math.floor(screenToWorldZ(mouseY, centerY));
        String status = String.format(
                "Cursor: X=%d, Z=%d (Chunk %d, %d)  |  Zoom: %.2fx  |  View Dist: %d chunks  |  [C] Toggle Cave Mode  |  [Space] Center Player",
                cursorX, cursorZ, cursorX >> 4, cursorZ >> 4, zoom, viewDistance
        );
        context.drawTextWithShadow(this.textRenderer, Text.literal(status), 10, top + 7, 0xFFB0BEC5);
    }

    private void drawOutline(DrawContext context, int x, int y, int w, int h, int color) {
        context.fill(x, y, x + w, y + 1, color);
        context.fill(x, y + h - 1, x + w, y + h, color);
        context.fill(x, y + 1, x + 1, y + h - 1, color);
        context.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (mouseY <= TOP_BAR_HEIGHT || mouseY >= this.height - BOTTOM_BAR_HEIGHT) {
            return false;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && mc.player != null) {
            int centerX = this.width / 2;
            int centerY = (TOP_BAR_HEIGHT + (this.height - BOTTOM_BAR_HEIGHT)) / 2;
            int viewDist = MinecraftClient.getInstance().options.getViewDistance().getValue();
            List<DetectedStructure> visible = mapEngine.getStructureDetector().getVisibleStructures(
                    mapEngine.getActiveDimensionId(), mc.player.getBlockX() >> 4, mc.player.getBlockZ() >> 4, viewDist
            );
            for (DetectedStructure s : visible) {
                int sx = worldToScreenX(s.pos().getX() + 0.5D, centerX);
                int sy = worldToScreenY(s.pos().getZ() + 0.5D, centerY);
                if (Math.abs(mouseX - sx) <= 8 && Math.abs(mouseY - sy) <= 8) {
                    String coords = s.pos().getX() + " " + s.pos().getY() + " " + s.pos().getZ();
                    mc.keyboard.setClipboard(coords);
                    mc.player.sendMessage(Text.literal("[CaveMapPlus] Copied " + s.type().displayName() + " XYZ: " + coords)
                            .formatted(Formatting.AQUA), true);
                    return true;
                }
            }
            this.draggingMap = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            this.draggingMap = false;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (this.draggingMap && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            this.cameraWorldX -= deltaX / this.zoom;
            this.cameraWorldZ -= deltaY / this.zoom;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        if (verticalAmount != 0.0D) {
            double factor = verticalAmount > 0.0D ? 1.2D : (1.0D / 1.2D);
            this.zoom = MathHelper.clamp(this.zoom * factor, 0.35D, 8.0D);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (keyCode == GLFW.GLFW_KEY_M || keyCode == GLFW.GLFW_KEY_ESCAPE) {
            mc.setScreen(null);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_C) {
            mapEngine.toggleCaveMode();
            if (this.modeToggleButton != null) {
                this.modeToggleButton.setMessage(getModeButtonText());
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_SPACE && mc.player != null) {
            this.cameraWorldX = mc.player.getX();
            this.cameraWorldZ = mc.player.getZ();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }

    private int worldToScreenX(double worldX, int centerX) {
        return (int) Math.round(centerX + (worldX - cameraWorldX) * zoom);
    }

    private int worldToScreenY(double worldZ, int centerY) {
        return (int) Math.round(centerY + (worldZ - cameraWorldZ) * zoom);
    }

    private double screenToWorldX(double screenX, int centerX) {
        return cameraWorldX + (screenX - centerX) / zoom;
    }

    private double screenToWorldZ(double screenY, int centerY) {
        return cameraWorldZ + (screenY - centerY) / zoom;
    }
}
