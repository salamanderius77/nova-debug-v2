package dev.nova.novadebug.modules;
import dev.nova.novadebug.NovaDebugAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public class GoofyDebug extends Module {
    public enum RenderStyle { Pillar, Flat }
    private final SettingGroup sgSpawner = settings.createGroup("Spawners");
    private final SettingGroup sgActivity = settings.createGroup("Player Activity");
    private final SettingGroup sgPlayers = settings.createGroup("Players");
    private final SettingGroup sgGeneral = settings.createGroup("General");
    // Spawner settings
    private final Setting<Boolean> detectSpawners = sgSpawner.add(new BoolSetting.Builder().name("enabled").description("Detect spawners").defaultValue(true).build());
    private final Setting<SettingColor> spawnerFill = sgSpawner.add(new ColorSetting.Builder().name("fill-color").description("Spawner fill color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> spawnerLine = sgSpawner.add(new ColorSetting.Builder().name("line-color").description("Spawner line color").defaultValue(new SettingColor(0, 100, 255, 200)).build());
    private final Setting<RenderStyle> spawnerStyle = sgSpawner.add(new EnumSetting.Builder<RenderStyle>().name("render-style").description("Pillar or Flat").defaultValue(RenderStyle.Pillar).build());
    // Player Activity settings
    private final Setting<Boolean> detectActivity = sgActivity.add(new BoolSetting.Builder().name("enabled").description("Detect player activity").defaultValue(true).build());
    private final Setting<SettingColor> activityFill = sgActivity.add(new ColorSetting.Builder().name("fill-color").description("Activity fill color").defaultValue(new SettingColor(255, 0, 0, 40)).build());
    private final Setting<SettingColor> activityLine = sgActivity.add(new ColorSetting.Builder().name("line-color").description("Activity line color").defaultValue(new SettingColor(255, 0, 0, 200)).build());
    private final Setting<RenderStyle> activityStyle = sgActivity.add(new EnumSetting.Builder<RenderStyle>().name("render-style").description("Pillar or Flat").defaultValue(RenderStyle.Pillar).build());
    // Players settings
    private final Setting<Boolean> detectPlayers = sgPlayers.add(new BoolSetting.Builder().name("enabled").description("Detect players").defaultValue(true).build());
    private final Setting<SettingColor> playerFill = sgPlayers.add(new ColorSetting.Builder().name("fill-color").description("Player fill color").defaultValue(new SettingColor(180, 0, 255, 40)).build());
    private final Setting<SettingColor> playerLine = sgPlayers.add(new ColorSetting.Builder().name("line-color").description("Player line color").defaultValue(new SettingColor(180, 0, 255, 200)).build());
    private final Setting<RenderStyle> playerStyle = sgPlayers.add(new EnumSetting.Builder<RenderStyle>().name("render-style").description("Pillar or Flat").defaultValue(RenderStyle.Pillar).build());
    // General settings
    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder().name("render-distance").description("Render distance in chunks").defaultValue(8).min(1).sliderMax(20).build());
    private final Setting<Integer> updateInterval = sgGeneral.add(new IntSetting.Builder().name("update-interval").description("Ticks between scans").defaultValue(20).min(5).sliderMax(100).build());
    // Chunk tracking
    private enum ChunkType { PLAYER, SPAWNER, ACTIVITY }
    private final ConcurrentHashMap<ChunkPos, ChunkType> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Float> activityScores = new ConcurrentHashMap<>();
    private int tickCounter = 0;
    public GoofyDebug() {
        super(NovaDebugAddon.CATEGORY, "Nova Debug", "Highlights chunks with suspicious underground activity.");
    }
    @Override
    public void onActivate() {
        trackedChunks.clear();
        activityScores.clear();
        info("Nova Debug v2 by Saint - active.");
    }
    @Override
    public void onDeactivate() {
        trackedChunks.clear();
        activityScores.clear();
    }
    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            if (mc.world == null || mc.player == null) return;
            tickCounter++;
            if (tickCounter % updateInterval.get() != 0) return;
            ChunkPos playerChunk = mc.player.getChunkPos();
            int radius = Math.min(renderDistance.get(), 12);
            // Decay activity scores
            for (ChunkPos pos : new ArrayList<>(activityScores.keySet())) {
                float newScore = activityScores.getOrDefault(pos, 0f) - 0.3f;
                if (newScore <= 0f) {
                    activityScores.remove(pos);
                    trackedChunks.remove(pos);
                } else {
                    activityScores.put(pos, newScore);
                }
            }
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    ChunkPos pos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    if (!mc.world.isChunkLoaded(pos.x, pos.z)) continue;
                    // Players (highest priority)
                    if (detectPlayers.get()) {
                        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                            if (p == mc.player) continue;
                            if (p.getChunkPos().x == pos.x && p.getChunkPos().z == pos.z) {
                                trackedChunks.put(pos, ChunkType.PLAYER);
                                break;
                            }
                        }
                        if (trackedChunks.get(pos) == ChunkType.PLAYER) continue;
                    }
                    // Spawners
                    if (detectSpawners.get()) {
                        WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
                        boolean found = false;
                        outer:
                        for (int lx = 0; lx < 16; lx += 4) {
                            for (int lz = 0; lz < 16; lz += 4) {
                                for (int y = -64; y < 64; y++) {
                                    BlockPos bp = new BlockPos(pos.getStartX() + lx, y, pos.getStartZ() + lz);
                                    if (chunk.getBlockState(bp).isOf(Blocks.SPAWNER)) {
                                        found = true;
                                        break outer;
                                    }
                                }
                            }
                        }
                        if (found) {
                            trackedChunks.put(pos, ChunkType.SPAWNER);
                            continue;
                        }
                    }
                    // Activity
                    if (detectActivity.get()) {
                        activityScores.merge(pos, 1.5f, Float::sum);
                        float score = activityScores.getOrDefault(pos, 0f);
                        if (score > 100f) activityScores.put(pos, 100f);
                        if (score >= 55f) {
                            trackedChunks.put(pos, ChunkType.ACTIVITY);
                        }
                    }
                }
            }
        } catch (Exception e) {
            error("Tick error: " + e.getMessage());
        }
    }
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        try {
            if (mc.world == null || mc.player == null) return;
            if (trackedChunks.isEmpty()) return;
            double px = mc.player.getX();
            double pz = mc.player.getZ();
            int distBlocks = renderDistance.get() * 16;
            double distSq = (double) distBlocks * distBlocks;
            for (Map.Entry<ChunkPos, ChunkType> entry : trackedChunks.entrySet()) {
                ChunkPos pos = entry.getKey();
                ChunkType type = entry.getValue();
                double cx = pos.getCenterX();
                double cz = pos.getCenterZ();
                double ddx = cx - px;
                double ddz = cz - pz;
                if (ddx * ddx + ddz * ddz > distSq) continue;
                Color fill;
                Color line;
                RenderStyle style;
                switch (type) {
                    case SPAWNER -> {
                        fill = c(spawnerFill.get());
                        line = c(spawnerLine.get());
                        style = spawnerStyle.get();
                    }
                    case ACTIVITY -> {
                        fill = c(activityFill.get());
                        line = c(activityLine.get());
                        style = activityStyle.get();
                    }
                    default -> {
                        fill = c(playerFill.get());
                        line = c(playerLine.get());
                        style = playerStyle.get();
                    }
                }
                int x1 = pos.getStartX();
                int z1 = pos.getStartZ();
                int x2 = x1 + 16;
                int z2 = z1 + 16;
                if (style == RenderStyle.Pillar) {
                    event.renderer.box(x1, -64, z1, x2, 320, z2, fill, line, ShapeMode.Both, 0);
                } else {
                    event.renderer.box(x1, 9, z1, x2, 10, z2, fill, line, ShapeMode.Both, 0);
                }
            }
        } catch (Exception e) {
            error("Render error: " + e.getMessage());
        }
    }
    private Color c(SettingColor sc) { return new Color(sc.r, sc.g, sc.b, sc.a); }
}
