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
 * Registers:
 * - Keybind 'R' to open StructureRadarScreen
 * - Keybind 'O' to quick-toggle background structure scanning
 * - Client tick hook for render-distance chunk scanning
 * - Top-screen HUD notification banner & mini status overlay using 1.16.5 MatrixStack
 */
public class StructureRadarClient implements ClientModInitializer {

    public static final String MOD_ID = "structureradar";

    private static final ChunkScanner CHUNK_SCANNER = new ChunkScanner();

    private static KeyBinding openRadarKey;
    private static KeyBinding toggleScanKey;

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
                        String state = cfg.scanningEnabled ? "\u00a7aENABLED" : "\u00a7cDISABLED";
                        client.player.sendMessage(new LiteralText("\u00a7b[StructureRadar] \u00a7fScanning " + state), true);
                    }
                }
            }
        });

        HudRenderCallback.EVENT.register(new HudRenderCallback() {
            @Override
            public void onHudRender(MatrixStack matrixStack, float tickDelta) {
                renderHudNotifications(matrixStack);
            }
        });
    }

    private void renderHudNotifications(MatrixStack matrices) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc == null || mc.player == null || mc.world == null || mc.options.hudHidden) {
            return;
        }

        ModConfig config = ModConfig.get();
        if (!config.hudBannerEnabled) {
            return;
        }

        List<ChunkScanner.HudNotification> notifications = CHUNK_SCANNER.getActiveHudNotifications();
        if (notifications.isEmpty()) {
            return;
        }

        int screenWidth = mc.getWindow().getScaledWidth();
        int bannerWidth = 250;
        int bannerHeight = 24;
        int x = (screenWidth - bannerWidth) / 2;
        int y = 6;

        for (int i = 0; i < notifications.size(); i++) {
            ChunkScanner.HudNotification notification = notifications.get(i);
            StructureInfo info = notification.structure;
            int dist = (int) Math.round(info.getHorizontalDistanceTo(mc.player.getX(), mc.player.getZ()));

            DrawableHelper.fill(matrices, x, y, x + bannerWidth, y + bannerHeight, 0xCC0D131A);
            int borderColor = info.getCategory().getColorArgb();
            DrawableHelper.fill(matrices, x, y, x + bannerWidth, y + 1, borderColor);
            DrawableHelper.fill(matrices, x, y + bannerHeight - 1, x + bannerWidth, y + bannerHeight, borderColor);
            DrawableHelper.fill(matrices, x, y, x + 2, y + bannerHeight, borderColor);
            DrawableHelper.fill(matrices, x + bannerWidth - 2, y, x + bannerWidth, y + bannerHeight, borderColor);

            String header = "\u00a7bStructure Detected: \u00a7e" + info.getCategory().getDisplayName();
            String coords = String.format("X: %d, Y: %d, Z: %d (%dm away) [Press R]", info.getX(), info.getY(), info.getZ(), dist);

            DrawableHelper.drawCenteredString(matrices, mc.textRenderer, header, screenWidth / 2, y + 4, 0xFFFFFFFF);
            DrawableHelper.drawCenteredString(matrices, mc.textRenderer, coords, screenWidth / 2, y + 13, 0xFFD5D8DC);

            y += bannerHeight + 4;
        }
    }

    public static ChunkScanner getChunkScanner() {
        return CHUNK_SCANNER;
    }
}
