package com.structureradar;

import com.structureradar.config.ModConfig;
import com.structureradar.gui.StructureRadarScreen;
import com.structureradar.scanner.ChunkScanner;
import com.structureradar.scanner.StructureInfo;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.LiteralText;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Client-side entrypoint for StructureRadar (Minecraft 1.16.5 Fabric).
 *
 * Features a tiny, unobtrusive 116x26 micro-HUD in the top-right corner
 * that only appears when a verified structure is inside the player's Render Distance.
 */
public class StructureRadarClient implements ClientModInitializer {

    public static final String MOD_ID = "structureradar";

    private static final ChunkScanner CHUNK_SCANNER = new ChunkScanner();

    private static KeyBinding openRadarKey;
    private static KeyBinding toggleScanKey;
    private static KeyBinding toggleHudKey;

    @Override
    public void onInitializeClient() {
        ModConfig.get();

        openRadarKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.structureradar.open_radar",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.structureradar.general"
        ));

        toggleScanKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.structureradar.toggle_scan",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.structureradar.general"
        ));

        toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.structureradar.toggle_hud",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                "category.structureradar.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(new ClientTickEvents.EndTick() {
            @Override
            public void onEndTick(MinecraftClient client) {
                CHUNK_SCANNER.tick(client);

                while (openRadarKey.wasPressed()) {
                    if (client.player != null && client.world != null) {
                        client.openScreen(new StructureRadarScreen(CHUNK_SCANNER));
                    }
                }

                while (toggleScanKey.wasPressed()) {
                    ModConfig cfg = ModConfig.get();
                    cfg.scanningEnabled = !cfg.scanningEnabled;
                    cfg.save();
                    if (client.player != null) {
                        String state = cfg.scanningEnabled ? "\u00a7aON" : "\u00a7cOFF";
                        client.player.sendMessage(new LiteralText("\u00a7b[Radar] \u00a7fScanning: " + state), true);
                    }
                }

                while (toggleHudKey.wasPressed()) {
                    ModConfig cfg = ModConfig.get();
                    cfg.miniHudEnabled = !cfg.miniHudEnabled;
                    cfg.save();
                }
            }
        });

        HudRenderCallback.EVENT.register(new HudRenderCallback() {
            @Override
            public void onHudRender(MatrixStack matrices, float tickDelta) {
                renderMicroCornerHud(matrices);
            }
        });
    }

    /**
     * Renders a tiny 116x26 micro-HUD pill in the top-right corner ONLY when a verified structure
     * is currently inside the player's Render Distance.
     */
    private void renderMicroCornerHud(MatrixStack matrices) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || mc.options.hudHidden || mc.options.debugEnabled) {
            return;
        }

        ModConfig config = ModConfig.get();
        if (!config.miniHudEnabled || !config.scanningEnabled) {
            return;
        }

        double px = mc.player.getX();
        double pz = mc.player.getZ();
        float yaw = mc.player.yaw;
        int rdChunks = Math.max(2, mc.options.viewDistance);

        StructureInfo target = CHUNK_SCANNER.getPrimaryTargetInRenderDistance(px, pz, rdChunks);
        if (target == null) {
            return; // Auto-hide HUD when no structure is in render distance so screen stays 100% clean
        }

        int screenW = mc.getWindow().getScaledWidth();
        int pillW = 118;
        int pillH = 26;
        int pillX = screenW - pillW - 4;
        int pillY = 4;

        int accent = target.getCategory().getColorArgb();
        DrawableHelper.fill(matrices, pillX, pillY, pillX + pillW, pillY + pillH, 0xB00C1117);
        DrawableHelper.fill(matrices, pillX, pillY, pillX + 2, pillY + pillH, accent);

        // Micro 20x20 radar scope inside the pill
        int rx = pillX + 5;
        int ry = pillY + 3;
        int rSize = 20;
        int rcx = rx + rSize / 2;
        int rcy = ry + rSize / 2;
        int rRad = 8;

        DrawableHelper.fill(matrices, rx, ry, rx + rSize, ry + rSize, 0xCC06090D);
        DrawableHelper.fill(matrices, rx, rcy, rx + rSize, rcy + 1, 0x2200E5FF);
        DrawableHelper.fill(matrices, rcx, ry, rcx + 1, ry + rSize, 0x2200E5FF);

        double rdBlocks = rdChunks * 16.0D;
        List<StructureInfo> inRd = CHUNK_SCANNER.getStructuresInRenderDistanceSorted(px, pz, rdChunks, null);
        for (int i = 0; i < Math.min(4, inRd.size()); i++) {
            StructureInfo s = inRd.get(i);
            double dist = s.getHorizontalDistanceTo(px, pz);
            double relRad = Math.toRadians(s.getRelativeAngleDegrees(px, pz, yaw));
            double norm = Math.min(1.0D, dist / rdBlocks);
            int bx = rcx + (int) Math.round(Math.sin(relRad) * norm * rRad);
            int by = rcy - (int) Math.round(Math.cos(relRad) * norm * rRad);
            DrawableHelper.fill(matrices, bx - 1, by - 1, bx + 2, by + 2, s.getCategory().getColorArgb());
        }
        DrawableHelper.fill(matrices, rcx, rcy, rcx + 1, rcy + 1, 0xFF00E676);

        // Compact 2-line text readout (Arrow + Name + Distance on Line 1, Exact XYZ on Line 2)
        int tx = rx + rSize + 5;
        String arrow = target.getRelativeDirectionArrow(px, pz, yaw);
        int dist = (int) Math.round(target.getHorizontalDistanceTo(px, pz));

        String line1 = arrow + " " + target.getCategory().getDisplayName() + " " + dist + "m";
        String line2 = target.getCompactCoords();

        DrawableHelper.drawStringWithShadow(matrices, mc.textRenderer, line1, tx, pillY + 4, accent);
        DrawableHelper.drawStringWithShadow(matrices, mc.textRenderer, line2, tx, pillY + 14, 0xFFE0E6ED);
    }

    public static ChunkScanner getChunkScanner() {
        return CHUNK_SCANNER;
    }
}
