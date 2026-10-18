package com.cavemapplus;

import com.cavemapplus.gui.WorldMapScreen;
import com.cavemapplus.map.MapEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Main client entrypoint for CaveMapPlus (Minecraft 1.21.11 Fabric).
 *
 * Keybindings:
 * - 'M': Open Full-Screen World/Cave Map
 * - 'C': Quick-toggle Cave Mode on/off
 */
public class CaveMapClient implements ClientModInitializer {

    public static final String MOD_ID = "cavemapplus";
    public static final Identifier MAP_CHANNEL_ID = Identifier.of(MOD_ID, "world_map");

    private static final MapEngine MAP_ENGINE = new MapEngine();

    private static KeyBinding openMapKey;
    private static KeyBinding toggleCaveModeKey;

    @Override
    public void onInitializeClient() {
        openMapKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cavemapplus.open_map",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_M,
                "category.cavemapplus.general"
        ));

        toggleCaveModeKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cavemapplus.toggle_cave_mode",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_C,
                "category.cavemapplus.general"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            MAP_ENGINE.onClientTick(client);

            while (openMapKey.wasPressed()) {
                if (client.player != null && client.world != null) {
                    client.setScreen(new WorldMapScreen(MAP_ENGINE));
                }
            }

            while (toggleCaveModeKey.wasPressed()) {
                MAP_ENGINE.toggleCaveMode();
                if (client.player != null) {
                    String modeName = MAP_ENGINE.isCaveMode() ? "Cave Slice Mode" : "Surface Terrain Mode";
                    client.player.sendMessage(
                            Text.literal("[CaveMapPlus] Switched to " + modeName)
                                    .formatted(MAP_ENGINE.isCaveMode() ? Formatting.AQUA : Formatting.GREEN),
                            true
                    );
                }
            }
        });
    }

    public static MapEngine getMapEngine() {
        return MAP_ENGINE;
    }
}
