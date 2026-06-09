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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GoofyDebug extends Module {
    public enum RenderStyle { Pillar, Flat }

    private final SettingGroup sgSpawner = settings.createGroup("Spawners");
    private final SettingGroup sgCluster = settings.createGroup("Cluster Signal");
    private final SettingGroup sgPlayers = settings.createGroup("Players");
    private final SettingGroup sgGeneral = settings.createGroup("General");

    // Spawner settings
    private final Setting<Boolean>      detectSpawners       = sgSpawner.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> spawnerFill          = sgSpawner.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> spawnerLine          = sgSpawner.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(0, 100, 255, 200)).build());
    private final Setting<RenderStyle>  spawnerStyle         = sgSpawner.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      spawnerToast         = sgSpawner.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());
    private final Setting<Boolean>      spawnerBedrockPillar = sgSpawner.add(new BoolSetting.Builder()
        .name("bedrock-pillar")
        .description("Extend spawner pillar from bedrock (-64) to sky (320).")
        .defaultValue(true).build());

    // Cluster Signal settings
    private final Setting<Boolean>      detectClusters       = sgCluster.add(new BoolSetting.Builder()
        .name("enabled")
        .description("Detect chunks with 16+ fully grown amethyst clusters.")
        .defaultValue(true).build());
    private final Setting<SettingColor> clusterFill          = sgCluster.add(new ColorSetting.Builder()
        .name("fill-color").defaultValue(new SettingColor(180, 80, 255, 40)).build());
    private final Setting<SettingColor> clusterLine          = sgCluster.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(180, 80, 255, 200)).build());
    private final Setting<RenderStyle>  clusterStyle         = sgCluster.add(new EnumSetting.Builder<RenderStyle>()
        .name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      clusterToast         = sgCluster.add(new BoolSetting.Builder()
        .name("toast-notify").defaultValue(true).build());
    private final Setting<Boolean>      clusterBedrockPillar = sgCluster.add(new BoolSetting.Builder()
        .name("bedrock-pillar")
        .description("Extend cluster pillar from bedrock (-64) to sky (320).")
        .defaultValue(true).build());
    private final Setting<Integer>      clusterThreshold     = sgCluster.add(new IntSetting.Builder()
        .name("min-clusters")
        .description("Minimum number of fully grown amethyst clusters to trigger a pillar.")
        .defaultValue(16).min(1).sliderMax(64).build());

    // Player settings
    private final Setting<Boolean>      detectPlayers       = sgPlayers.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> playerFill          = sgPlayers.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(180, 0, 255, 40)).build());
    private final Setting<SettingColor> playerLine          = sgPlayers.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(180, 0, 255, 200)).build());
    private final Setting<RenderStyle>  playerStyle         = sgPlayers.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      playerToast         = sgPlayers.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());
    private final Setting<Boolean>      playerBedrockPillar = sgPlayers.add(new BoolSetting.Builder()
        .name("bedrock-pillar")
        .description("Extend player pillar from bedrock (-64) to sky (320).")
        .defaultValue(true).build());

    // General settings
    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .defaultValue(26).min(1).sliderMax(32).build());

    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("How many chunks to scan per tick. Higher = faster scan but more lag.")
        .defaultValue(2).min(1).sliderMax(10).build());

    private final Setting<Integer> clearDistance = sgGeneral.add(new IntSetting.Builder()
        .name("clear-distance")
        .description("Chunks you must move before highlights clear. 0 = never clear.")
        .defaultValue(26).min(0).sliderMax(32).build());

    private enum ChunkType { PLAYER, SPAWNER, CLUSTER }

    private final ConcurrentHashMap<ChunkPos, ChunkType> trackedChunks  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean>   notifiedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean>   scannedChunks  = new ConcurrentHashMap<>();

    private final List<ChunkPos> scanQueue  = new ArrayList<>();
    private int      scanIndex       = 0;
    private boolean  scanDone        = false;
    private ChunkPos scanOriginChunk = null;

    private volatile Map<ChunkPos, ChunkType> renderSnapshot = Collections.emptyMap();

    public GoofyDebug() {
        super(NovaDebugAddon.CATEGORY, "Nova Debug", "Highlights chunks with spawners and player activity.");
    }

    @Override
    public void onActivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
        scannedChunks.clear();
        scanQueue.clear();
        scanIndex       = 0;
        scanDone        = false;
        scanOriginChunk = null;
        renderSnapshot  = Collections.emptyMap();
        buildScanQueue();
        info("Nova Debug active.");
    }

    @Override
    public void onDeactivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
        scannedChunks.clear();
        scanQueue.clear();
        renderSnapshot = Collections.emptyMap();
        scanDone = false;
    }

    private void buildScanQueue() {
        if (mc.world == null || mc.player == null) return;
        scanQueue.clear();
        scanIndex = 0;
        scanDone  = false;
        ChunkPos playerChunk = mc.player.getChunkPos();
        scanOriginChunk = playerChunk;
        int radius = Math.min(renderDistance.get(), 32);

        List<ChunkPos> all = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos cp = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                if (!scannedChunks.containsKey(cp)) all.add(cp);
            }
        }
        all.sort(Comparator.comparingInt(p ->
            Math.abs(p.x - playerChunk.x) + Math.abs(p.z - playerChunk.z)));
        scanQueue.addAll(all);
    }

    private void scanChunk(ChunkPos pos) {
        try {
            if (mc.world == null || mc.player == null) return;
            if (!mc.world.isChunkLoaded(pos.x, pos.z)) return;

            WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
            if (chunk == null || chunk.isEmpty()) return;

            scannedChunks.put(pos, Boolean.TRUE);

            // --- PLAYERS (highest priority) ---
            if (detectPlayers.get()) {
                List<? extends PlayerEntity> players = mc.world.getPlayers();
                if (players != null) {
                    for (PlayerEntity p : players) {
                        if (p == null || p == mc.player) continue;
                        ChunkPos pChunk = p.getChunkPos();
                        if (pChunk.x == pos.x && pChunk.z == pos.z) {
                            boolean isNew = !ChunkType.PLAYER.equals(trackedChunks.get(pos));
                            trackedChunks.put(pos, ChunkType.PLAYER);
                            if (isNew && playerToast.get() && !notifiedChunks.containsKey(pos)) {
                                info("§d[Nova Debug] Player §f" + p.getName().getString() + " §dfound!");
                                notifiedChunks.put(pos, Boolean.TRUE);
                            }
                            return;
                        }
                    }
                }
            }

            // --- SPAWNERS (second priority) ---
            if (detectSpawners.get()) {
                BlockPos foundPos = null;
                int spawnerCount  = 0;
                int bottomY = mc.world.getBottomY();
                int topY    = mc.world.getTopY();

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int y = bottomY; y < topY; y++) {
                            try {
                                BlockPos bp = new BlockPos(pos.getStartX() + lx, y, pos.getStartZ() + lz);
                                if (chunk.getBlockState(bp).isOf(Blocks.SPAWNER)) {
                                    if (foundPos == null) foundPos = bp;
                                    spawnerCount++;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                if (foundPos != null) {
                    ChunkType existing = trackedChunks.get(pos);
                    if (existing != ChunkType.PLAYER) {
                        boolean isNew = existing != ChunkType.SPAWNER;
                        trackedChunks.put(pos, ChunkType.SPAWNER);
                        if (isNew && spawnerToast.get() && !notifiedChunks.containsKey(pos)) {
                            if (spawnerCount == 1) info("§9[Nova Debug] §fSpawner §9found!");
                            else info("§9[Nova Debug] §f" + spawnerCount + " §9Spawners found!");
                            notifiedChunks.put(pos, Boolean.TRUE);
                        }
                    }
                    return;
                }
            }

            // --- CLUSTER SIGNAL (third priority — fully grown amethyst clusters only) ---
            if (detectClusters.get()) {
                int clusterCount = 0;
                int bottomY = mc.world.getBottomY();
                int topY    = mc.world.getTopY();

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int y = bottomY; y < topY; y++) {
                            try {
                                BlockPos bp = new BlockPos(pos.getStartX() + lx, y, pos.getStartZ() + lz);
                                if (chunk.getBlockState(bp).isOf(Blocks.AMETHYST_CLUSTER)) {
                                    clusterCount++;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                if (clusterCount >= clusterThreshold.get()) {
                    ChunkType existing = trackedChunks.get(pos);
                    if (existing != ChunkType.PLAYER && existing != ChunkType.SPAWNER) {
                        boolean isNew = existing != ChunkType.CLUSTER;
                        trackedChunks.put(pos, ChunkType.CLUSTER);
                        if (isNew && clusterToast.get() && !notifiedChunks.containsKey(pos)) {
                            info("§5[Nova Debug] §f" + clusterCount + " §5Amethyst Clusters found!");
                            notifiedChunks.put(pos, Boolean.TRUE);
                        }
                    }
                }
            }

        } catch (Exception e) {
            error("Scan error at " + pos + ": " + e.getMessage());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            if (mc.world == null || mc.player == null) return;

            ChunkPos currentChunk = mc.player.getChunkPos();
            int radius = Math.min(renderDistance.get(), 32);

            // --- Live player tracking every tick ---
            if (detectPlayers.get()) {
                List<ChunkPos> toRemove = new ArrayList<>();
                for (Map.Entry<ChunkPos, ChunkType> e : trackedChunks.entrySet()) {
                    if (e.getValue() != ChunkType.PLAYER) continue;
                    ChunkPos pos = e.getKey();
                    boolean found = false;
                    List<? extends PlayerEntity> players = mc.world.getPlayers();
                    if (players != null) {
                        for (PlayerEntity p : players) {
                            if (p == null || p == mc.player) continue;
                            ChunkPos pc = p.getChunkPos();
                            if (pc.x == pos.x && pc.z == pos.z) { found = true; break; }
                        }
                    }
                    if (!found) toRemove.add(pos);
                }
                for (ChunkPos pos : toRemove) {
                    trackedChunks.remove(pos);
                    notifiedChunks.remove(pos);
                    scannedChunks.remove(pos);
                    if (!scanQueue.contains(pos)) scanQueue.add(pos);
                    scanDone = false;
                }

                List<? extends PlayerEntity> players = mc.world.getPlayers();
                if (players != null) {
                    for (PlayerEntity p : players) {
                        if (p == null || p == mc.player) continue;
                        ChunkPos pos = p.getChunkPos();
                        if (Math.abs(pos.x - currentChunk.x) > radius) continue;
                        if (Math.abs(pos.z - currentChunk.z) > radius) continue;
                        boolean isNew = !ChunkType.PLAYER.equals(trackedChunks.get(pos));
                        trackedChunks.put(pos, ChunkType.PLAYER);
                        if (isNew && playerToast.get() && !notifiedChunks.containsKey(pos)) {
                            info("§d[Nova Debug] Player §f" + p.getName().getString() + " §dfound!");
                            notifiedChunks.put(pos, Boolean.TRUE);
                        }
                    }
                }
            }

            // --- Clear if moved past clearDistance ---
            int cd = clearDistance.get();
            boolean movedFar = scanOriginChunk != null && cd > 0 && (
                Math.abs(currentChunk.x - scanOriginChunk.x) > cd ||
                Math.abs(currentChunk.z - scanOriginChunk.z) > cd
            );

            if (movedFar || scanOriginChunk == null) {
                List<ChunkPos> stale = new ArrayList<>();
                for (Map.Entry<ChunkPos, ChunkType> e : trackedChunks.entrySet()) {
                    if (e.getValue() != ChunkType.PLAYER) stale.add(e.getKey());
                }
                for (ChunkPos pos : stale) {
                    trackedChunks.remove(pos);
                    notifiedChunks.remove(pos);
                }
                scannedChunks.clear();
                buildScanQueue();
            } else {
                for (int dx = -radius; dx <= radius; dx++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        ChunkPos cp = new ChunkPos(currentChunk.x + dx, currentChunk.z + dz);
                        if (!scannedChunks.containsKey(cp) && !scanQueue.contains(cp)) {
                            scanQueue.add(cp);
                            scanDone = false;
                        }
                    }
                }
            }

            // --- Scan N chunks per tick ---
            int toProcess = chunksPerTick.get();
            while (toProcess > 0 && !scanDone && scanIndex < scanQueue.size()) {
                scanChunk(scanQueue.get(scanIndex));
                scanIndex++;
                toProcess--;
                if (scanIndex >= scanQueue.size()) scanDone = true;
            }

            renderSnapshot = new HashMap<>(trackedChunks);

        } catch (Exception e) {
            error("Tick error: " + e.getMessage());
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        try {
            if (mc.world == null || mc.player == null) return;

            Map<ChunkPos, ChunkType> snapshot = renderSnapshot;
            if (snapshot == null || snapshot.isEmpty()) return;

            double px      = mc.player.getX();
            double pz      = mc.player.getZ();
            int distBlocks = renderDistance.get() * 16;
            double distSq  = (double) distBlocks * distBlocks;

            for (Map.Entry<ChunkPos, ChunkType> entry : snapshot.entrySet()) {
                ChunkPos  pos  = entry.getKey();
                ChunkType type = entry.getValue();

                double cx  = pos.getCenterX();
                double cz  = pos.getCenterZ();
                double ddx = cx - px;
                double ddz = cz - pz;
                if (ddx * ddx + ddz * ddz > distSq) continue;

                Color       fill;
                Color       line;
                RenderStyle style;
                int         yMin = -64;
                int         yMax;

                switch (type) {
                    case SPAWNER -> {
                        fill  = c(spawnerFill.get());
                        line  = c(spawnerLine.get());
                        style = spawnerStyle.get();
                        yMax  = spawnerBedrockPillar.get() ? 320 : 64;
                    }
                    case CLUSTER -> {
                        fill  = c(clusterFill.get());
                        line  = c(clusterLine.get());
                        style = clusterStyle.get();
                        yMax  = clusterBedrockPillar.get() ? 320 : 64;
                    }
                    default -> { // PLAYER
                        fill  = c(playerFill.get());
                        line  = c(playerLine.get());
                        style = playerStyle.get();
                        yMax  = playerBedrockPillar.get() ? 320 : 64;
                    }
                }

                int x1 = pos.getStartX();
                int z1 = pos.getStartZ();
                int x2 = x1 + 16;
                int z2 = z1 + 16;

                if (style == RenderStyle.Pillar) {
                    event.renderer.box(x1, yMin, z1, x2, yMax, z2, fill, line, ShapeMode.Both, 0);
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
