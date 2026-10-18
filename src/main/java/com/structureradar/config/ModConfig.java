package com.structureradar.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.structureradar.scanner.StructureInfo;
import net.fabricmc.loader.api.FabricLoader;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/**
 * Persistent configuration for StructureRadar (Minecraft 1.16.5).
 */
public class ModConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File CONFIG_FILE = new File(
            FabricLoader.getInstance().getConfigDir().toFile(),
            "structureradar.json"
    );

    private static ModConfig INSTANCE;

    public boolean scanningEnabled = true;
    public boolean miniHudEnabled = true;
    public boolean chatAlertsEnabled = false;
    public int chunksPerTickBudget = 4;
    public Set<String> enabledStructureTypes = new HashSet<String>();

    public ModConfig() {
        for (StructureInfo.StructureCategory category : StructureInfo.StructureCategory.values()) {
            enabledStructureTypes.add(category.name());
        }
    }

    public static synchronized ModConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    public boolean isCategoryEnabled(StructureInfo.StructureCategory category) {
        return category != null && enabledStructureTypes.contains(category.name());
    }

    public static ModConfig load() {
        if (CONFIG_FILE.exists()) {
            FileReader reader = null;
            try {
                reader = new FileReader(CONFIG_FILE);
                ModConfig loaded = GSON.fromJson(reader, ModConfig.class);
                if (loaded != null) {
                    if (loaded.enabledStructureTypes == null || loaded.enabledStructureTypes.isEmpty()) {
                        loaded.enabledStructureTypes = new HashSet<String>();
                        for (StructureInfo.StructureCategory c : StructureInfo.StructureCategory.values()) {
                            loaded.enabledStructureTypes.add(c.name());
                        }
                    }
                    return loaded;
                }
            } catch (Exception ignored) {
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        ModConfig fresh = new ModConfig();
        fresh.save();
        return fresh;
    }

    public synchronized void save() {
        FileWriter writer = null;
        try {
            File parent = CONFIG_FILE.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            writer = new FileWriter(CONFIG_FILE);
            GSON.toJson(this, writer);
        } catch (IOException ignored) {
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
