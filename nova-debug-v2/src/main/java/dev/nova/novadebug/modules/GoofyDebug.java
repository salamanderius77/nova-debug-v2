package dev.nova.novadebug.modules;
import dev.nova.novadebug.NovaDebugAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
public class GoofyDebug extends Module {
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");
    private final Setting<Boolean> detectSpawners = sgDetection.add(new BoolSetting.Builder().name("spawners").description("Highlight chunks with spawners in blue").defaultValue(true).build());
    private final Setting<Boolean> detectPlayers = sgDetection.add(new BoolSetting.Builder().name("players").description("Highlight chunks with players in purple").defaultValue(true).build());
    private final Setting<Boolean> detectActivity = sgDetection.add(new BoolSetting.Builder().name("chunk-activity").description("Highlight chunks by activity level").defaultValue(true).build());
    private final Setting<Integer> renderDistance = sgDetection.add(new IntSetting.Builder().name("render-distance").description("Render distance in chunks").defaultValue(8).min(1).sliderMax(20).build());
    private final Setting<Integer> updateInterval = sgPerformance.add(new IntSetting.Builder().name("update-interval").description("Ticks between scans").defaultValue(20).min(5).sliderMax(100).build());
    private final Setting<Integer> maxChunks = sgPerformance.add(new IntSetting.Builder().name("max-chunks").description("Max tracked chunks").defaultValue(512).min(64).sliderMax(2048).build());
    private static final Color PURPLE = new Color(180, 0, 255, 200);
    private static final Color BLUE = new Color(0, 100, 255, 200);
    private static final Color GREEN = new Color(0, 255, 0, 200);
    private static final Color YELLOW = new Color(255, 220, 0, 200);
    private static final Color RED = new Color(255, 0, 0, 200);
    private enum ChunkType { PLAYER, SPAWNER, LOW, MEDIUM, HIGH }
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
            for (ChunkPos pos : activityScores.keySet()) {
                activityScores.merge(pos, 0f, (old, v) -> Math.max(0, old - 0.3f));
            }
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    ChunkPos pos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    if (!mc.world.isChunkLoaded(pos.x, pos.z)) continue;
                    if (detectPlayers.get()) {
                        boolean hasPlayer = false;
                        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                            if (p == mc.player) continue;
                            if (p.getChunkPos().x == pos.x && p.getChunkPos().z == pos.z) {
                                hasPlayer = true;
                                break;
                            }
                        }
                        if (hasPlayer) {
                            trackedChunks.put(pos, ChunkType.PLAYER);
                            continue;
                        }
                    }
                    if (detectSpawners.get()) {
                        WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
                        boolean hasSpawner = false;
                        outer:
                        for (int lx = 0; lx < 16; lx += 4) {
                            for (int lz = 0; lz < 16; lz += 4) {
                                for (int y = -64; y < 64; y++) {
                                    BlockPos bp = new BlockPos(pos.getStartX() + lx, y, pos.getStartZ() + lz);
                                    if (chunk.getBlockState(bp).isOf(Blocks.SPAWNER)) {
                                        hasSpawner = true;
                                        break outer;
                                    }
                                }
                            }
                        }
                        if (hasSpawner) {
                            trackedChunks.put(pos, ChunkType.SPAWNER);
                            continue;
                        }
                    }
                    if (detectActivity.get()) {
                        activityScores.merge(pos, 1.5f, Float::sum);
                        float score = activityScores.getOrDefault(pos, 0f);
                        if (score > 100f) activityScores.put(pos, 100f);
                        if (score >= 5f) {
                            ChunkType type;
                            if (score < 20f) type = ChunkType.LOW;
                            else if (score < 55f) type = ChunkType.MEDIUM;
                            else type = ChunkType.HIGH;
                            trackedChunks.put(pos, type);
                        } else {
                            trackedChunks.remove(pos);
                        }
                    }
                }
            }
            if (trackedChunks.size() > maxChunks.get()) {
                List<ChunkPos> keys = new ArrayList<>(trackedChunks.keySet());
                for (int i = 0; i < keys.size() - maxChunks.get(); i++) {
                    trackedChunks.remove(keys.get(i));
                    activityScores.remove(keys.get(i));
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
                double dx = cx - px;
                double dz = cz - pz;
                if (dx * dx + dz * dz > distSq) continue;
                Color color = switch (type) {
                    case PLAYER -> PURPLE;
                    case SPAWNER -> BLUE;
                    case LOW -> GREEN;
                    case MEDIUM -> YELLOW;
                    case HIGH -> RED;
                };
                int x1 = pos.getStartX();
                int z1 = pos.getStartZ();
                int x2 = x1 + 16;
                int z2 = z1 + 16;
                Color fillColor = new Color(color.r, color.g, color.b, 40);
                Color lineColor = new Color(color.r, color.g, color.b, 200);
                event.renderer.box(x1, 250, z1, x2, 255, z2, fillColor, lineColor, ShapeMode.Both, 0);
            }
        } catch (Exception e) {
            error("Render error: " + e.getMessage());
        }
    }
}
