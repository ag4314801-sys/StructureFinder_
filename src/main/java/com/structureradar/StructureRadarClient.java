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
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * Clean, lightweight client entrypoint for StructureRadar (Minecraft 1.16.5 Fabric).
 *
 * Keybinds:
 * - 'R': Open compact centered StructureRadarScreen modal
 * - 'O': Toggle structure scanning ON/OFF
 * - 'H': Toggle the compact top-right Mini Radar & Direction Compass HUD
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
                renderCompactCornerHud(matrices);
            }
        });
    }

    /**
     * Renders a small, sleek 146x42 corner widget in the top-right of the screen
     * with a player-oriented mini radar and an exact directional arrow + distance readout.
     */
    private void renderCompactCornerHud(MatrixStack matrices) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || mc.options.hudHidden || mc.options.debugEnabled) {
            return;
        }

        ModConfig config = ModConfig.get();
        if (!config.miniHudEnabled) {
            return;
        }

        double px = mc.player.getX();
        double pz = mc.player.getZ();
        float yaw = mc.player.yaw;

        StructureInfo target = CHUNK_SCANNER.getPrimaryTarget(px, pz);
        if (target == null) {
            return;
        }

        int screenW = mc.getWindow().getScaledWidth();
        int cardW = 148;
        int cardH = 42;
        int cardX = screenW - cardW - 6;
        int cardY = 6;

        // Sleek dark translucent card with 1px subtle border
        DrawableHelper.fill(matrices, cardX, cardY, cardX + cardW, cardY + cardH, 0xB80D131A);
        int accent = target.getCategory().getColorArgb();
        DrawableHelper.fill(matrices, cardX, cardY, cardX + cardW, cardY + 1, 0xFF263648);
        DrawableHelper.fill(matrices, cardX, cardY + cardH - 1, cardX + cardW, cardY + cardH, 0xFF263648);
        DrawableHelper.fill(matrices, cardX, cardY, cardX + 2, cardY + cardH, accent);

        // Mini 32x32 player-relative radar scope on left side of the card
        int radarX = cardX + 5;
        int radarY = cardY + 5;
        int radarSize = 32;
        int rcx = radarX + radarSize / 2;
        int rcy = radarY + radarSize / 2;
        int rRadius = 13;

        DrawableHelper.fill(matrices, radarX, radarY, radarX + radarSize, radarY + radarSize, 0xCC070A0E);
        DrawableHelper.fill(matrices, radarX, rcy, radarX + radarSize, rcy + 1, 0x2200E5FF);
        DrawableHelper.fill(matrices, rcx, radarY, rcx + 1, radarY + radarSize, 0x2200E5FF);

        // Plot nearby blips relative to player's facing direction (UP = player look direction)
        List<StructureInfo> nearby = CHUNK_SCANNER.getFilteredStructuresSorted(px, pz, null);
        double range = config.radarZoomRange;
        for (int i = 0; i < Math.min(6, nearby.size()); i++) {
            StructureInfo s = nearby.get(i);
            double dist = s.getHorizontalDistanceTo(px, pz);
            double relDeg = s.getRelativeAngleDegrees(px, pz, yaw);
            double rad = Math.toRadians(relDeg);
            double norm = Math.min(1.0D, dist / range);

            int bx = rcx + (int) Math.round(Math.sin(rad) * norm * rRadius);
            int by = rcy - (int) Math.round(Math.cos(rad) * norm * rRadius);
            DrawableHelper.fill(matrices, bx - 1, by - 1, bx + 2, by + 2, s.getCategory().getColorArgb());
        }

        // Player center dot
        DrawableHelper.fill(matrices, rcx - 1, rcy - 1, rcx + 2, rcy + 2, 0xFF00E676);

        // Right side text readout (Direction Arrow + Name + Distance + XYZ)
        int textX = radarX + radarSize + 6;
        String arrow = target.getRelativeDirectionArrow(px, pz, yaw);
        String cardinal = target.getWorldCardinalFromPlayer(px, pz);
        int dist = (int) Math.round(target.getHorizontalDistanceTo(px, pz));

        String title = arrow + " " + target.getCategory().getDisplayName();
        if (title.length() > 16) title = title.substring(0, 16);
        String distLine = dist + "m (" + cardinal + ") [R]";
        String xyzLine = target.getCompactCoords();

        DrawableHelper.drawStringWithShadow(matrices, mc.textRenderer, title, textX, cardY + 6, accent);
        DrawableHelper.drawStringWithShadow(matrices, mc.textRenderer, distLine, textX, cardY + 17, 0xFFFFFFFF);
        DrawableHelper.drawStringWithShadow(matrices, mc.textRenderer, xyzLine, textX, cardY + 28, 0xFF90A4AE);

        // Optional compact 1-line toast pill right below the corner card when a new structure is detected
        List<ChunkScanner.HudNotification> toasts = CHUNK_SCANNER.getActiveHudNotifications();
        if (!toasts.isEmpty()) {
            StructureInfo newest = toasts.get(toasts.size() - 1).structure;
            int toastY = cardY + cardH + 3;
            DrawableHelper.fill(matrices, cardX, toastY, cardX + cardW, toastY + 13, 0xCC101822);
            DrawableHelper.fill(matrices, cardX, toastY, cardX + 2, toastY + 13, newest.getCategory().getColorArgb());
            String toastText = "\u00a7a+ Found: \u00a7f" + newest.getCategory().getDisplayName();
            DrawableHelper.drawStringWithShadow(matrices, mc.textRenderer, toastText, cardX + 6, toastY + 3, 0xFFFFFFFF);
        }
    }

    public static ChunkScanner getChunkScanner() {
        return CHUNK_SCANNER;
    }
}
