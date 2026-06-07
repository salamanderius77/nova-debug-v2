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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.MobSpawnerBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GoofyDebug extends Module {
    public enum RenderStyle { Pillar, Flat }

    private final SettingGroup sgSpawner  = settings.createGroup("Spawners");
    private final SettingGroup sgActivity = settings.createGroup("Player Activity");
    private final SettingGroup sgPlayers  = settings.createGroup("Players");
    private final SettingGroup sgGeneral  = settings.createGroup("General");

    // Spawner settings
    private final Setting<Boolean>      detectSpawners       = sgSpawner.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> spawnerFill          = sgSpawner.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> spawnerLine          = sgSpawner.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(0, 100, 255, 200)).build());
    private final Setting<RenderStyle>  spawnerStyle         = sgSpawner.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      spawnerToast         = sgSpawner.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());
    private final Setting<Boolean>      spawnerBedrockPillar = sgSpawner.add(new BoolSetting.Builder()
        .name("bedrock-pillar")
        .description("Extend spawner pillar from bedrock (-64) to sky (320). Enable when DonutSMP anti-cheat is off.")
        .defaultValue(false).build());

    // Activity settings
    private final Setting<Boolean>      detectActivity = sgActivity.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> activityFill   = sgActivity.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(255, 0, 0, 40)).build());
    private final Setting<SettingColor> activityLine   = sgActivity.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(255, 0, 0, 200)).build());
    private final Setting<RenderStyle>  activityStyle  = sgActivity.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      activityToast  = sgActivity.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());

    // Player settings
    private final Setting<Boolean>      detectPlayers       = sgPlayers.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> playerFill          = sgPlayers.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(180, 0, 255, 40)).build());
    private final Setting<SettingColor> playerLine          = sgPlayers.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(180, 0, 255, 200)).build());
    private final Setting<RenderStyle>  playerStyle         = sgPlayers.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      playerToast         = sgPlayers.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());
    private final Setting<Boolean>      playerBedrockPillar = sgPlayers.add(new BoolSetting.Builder()
        .name("bedrock-pillar")
        .description("Extend player pillar from bedrock (-64) to sky (320). Enable when DonutSMP anti-cheat is off.")
        .defaultValue(false).build());

    // General settings
    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .defaultValue(26).min(1).sliderMax(32).build());

    private final Setting<Integer> chunksToScan = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-to-scan")
        .description("How many chunks to scan per activation")
        .defaultValue(50).min(1).sliderMax(200).build());

    private final Setting<Integer> clearDistance = sgGeneral.add(new IntSetting.Builder()
        .name("clear-distance")
        .description("How many chunks you must move before spawner/activity highlights clear. 0 = never clear.")
        .defaultValue(26).min(0).sliderMax(32).build());

    // Internal state
    private enum ChunkType { PLAYER, SPAWNER, ACTIVITY }

    // FIX 1: Use ConcurrentHashMap for both maps so render thread and tick thread never clash
    private final ConcurrentHashMap<ChunkPos, ChunkType> trackedChunks  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean>   notifiedChunks = new ConcurrentHashMap<>();

    // Scan queue — only touched on tick thread, no concurrent access needed
    private final List<ChunkPos> scanQueue   = new ArrayList<>();
    private int      scanIndex      = 0;
    private boolean  scanDone       = false;
    private ChunkPos lastPlayerChunk  = null;
    private ChunkPos scanOriginChunk  = null;

    // FIX 2: Snapshot for render thread — built on tick thread, read on render thread, never mutated by render
    private volatile Map<ChunkPos, ChunkType> renderSnapshot = Collections.emptyMap();

    public GoofyDebug() {
        super(NovaDebugAddon.CATEGORY, "Nova Debug", "Highlights chunks with suspicious underground activity.");
    }

    @Override
    public void onActivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
        scanQueue.clear();
        scanIndex        = 0;
        scanDone         = false;
        lastPlayerChunk  = null;
        scanOriginChunk  = null;
        renderSnapshot   = Collections.emptyMap();
        buildScanQueue();
        info("Nova Debug v2 by Saint - active.");
    }

    @Override
    public void onDeactivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
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
        lastPlayerChunk = playerChunk;
        scanOriginChunk = playerChunk;
        int radius = Math.min(renderDistance.get(), 32);

        // FIX 3: Use chunksToScan as the actual scan radius budget, not a hard cap on queue size
        // Build the full radius list sorted by distance, then limit to chunksToScan
        List<ChunkPos> all = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                all.add(new ChunkPos(playerChunk.x + dx, playerChunk.z + dz));
            }
        }
        all.sort(Comparator.comparingInt(p ->
            Math.abs(p.x - playerChunk.x) + Math.abs(p.z - playerChunk.z)));

        int limit = Math.min(chunksToScan.get(), all.size());
        for (int i = 0; i < limit; i++) {
            scanQueue.add(all.get(i));
        }
    }

    // FIX 4: getSpawnerType — guard against null chunk/BE and avoid calling createNbt on render thread
    private String getSpawnerType(WorldChunk chunk, BlockPos spawnerPos) {
        if (chunk == null || spawnerPos == null) return "Unknown";
        try {
            BlockEntity be = chunk.getBlockEntity(spawnerPos);
            if (be instanceof MobSpawnerBlockEntity spawner) {
                // createNbt is safe here — we are always on the tick/main thread when scanChunk runs
                NbtCompound nbt = spawner.createNbt(mc.world.getRegistryManager());
                if (nbt != null && nbt.contains("SpawnData")) {
                    NbtCompound spawnData = nbt.getCompound("SpawnData");
                    if (spawnData != null && spawnData.contains("entity")) {
                        NbtCompound entity = spawnData.getCompound("entity");
                        String entityId = entity != null ? entity.getString("id") : "";
                        if (entityId != null && entityId.contains(":"))
                            entityId = entityId.split(":")[1];
                        if (entityId != null && !entityId.isEmpty())
                            return entityId.substring(0, 1).toUpperCase() + entityId.substring(1).replace("_", " ");
                    }
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private void scanChunk(ChunkPos pos) {
        try {
            if (mc.world == null || mc.player == null) return;
            if (!mc.world.isChunkLoaded(pos.x, pos.z)) return;

            // FIX 5: Null-check the chunk itself — getChunk can return null or an empty chunk
            WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
            if (chunk == null || chunk.isEmpty()) return;

            // Players — highest priority
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

            // Spawners — full height scan
            if (detectSpawners.get()) {
                BlockPos foundPos = null;
                int spawnerCount  = 0;
                int bottomY = mc.world.getBottomY();
                int topY    = mc.world.getTopY();

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int y = bottomY; y < topY; y++) {
                            BlockPos bp = new BlockPos(pos.getStartX() + lx, y, pos.getStartZ() + lz);
                            // FIX 6: Guard getBlockState — can throw on partially-loaded chunks
                            try {
                                if (chunk.getBlockState(bp).isOf(Blocks.SPAWNER)) {
                                    if (foundPos == null) foundPos = bp;
                                    spawnerCount++;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                if (foundPos != null) {
                    boolean isNew = !ChunkType.SPAWNER.equals(trackedChunks.get(pos));
                    trackedChunks.put(pos, ChunkType.SPAWNER);
                    if (isNew && spawnerToast.get() && !notifiedChunks.containsKey(pos)) {
                        String spawnerType = getSpawnerType(chunk, foundPos);
                        if (spawnerCount == 1) {
                            info("§9[Nova Debug] §f" + spawnerType + " §9Spawner found!");
                        } else {
                            info("§9[Nova Debug] §f" + spawnerCount + " §9Spawners found! (first: §f" + spawnerType + "§9)");
                        }
                        notifiedChunks.put(pos, Boolean.TRUE);
                    }
                    return;
                }
            }

            // Activity
            if (detectActivity.get()) {
                boolean isNew = !ChunkType.ACTIVITY.equals(trackedChunks.get(pos));
                trackedChunks.put(pos, ChunkType.ACTIVITY);
                if (isNew && activityToast.get() && !notifiedChunks.containsKey(pos)) {
                    info("§c[Nova Debug] Player Activity §ffound!");
                    notifiedChunks.put(pos, Boolean.TRUE);
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

            // Live player tracking
            if (detectPlayers.get()) {
                ChunkPos playerChunk = mc.player.getChunkPos();
                int radius = Math.min(renderDistance.get(), 32);

                // FIX 7: Collect keys to remove first, then remove — avoids removeIf + concurrent read clash
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
                }

                // Add new player positions
                List<? extends PlayerEntity> players = mc.world.getPlayers();
                if (players != null) {
                    for (PlayerEntity p : players) {
                        if (p == null || p == mc.player) continue;
                        ChunkPos pos = p.getChunkPos();
                        if (Math.abs(pos.x - playerChunk.x) > radius) continue;
                        if (Math.abs(pos.z - playerChunk.z) > radius) continue;
                        boolean isNew = !ChunkType.PLAYER.equals(trackedChunks.get(pos));
                        trackedChunks.put(pos, ChunkType.PLAYER);
                        if (isNew && playerToast.get() && !notifiedChunks.containsKey(pos)) {
                            info("§d[Nova Debug] Player §f" + p.getName().getString() + " §dfound!");
                            notifiedChunks.put(pos, Boolean.TRUE);
                        }
                    }
                }
            }

            // Clear spawner/activity data when player moves far enough
            ChunkPos currentChunk = mc.player.getChunkPos();
            int cd = clearDistance.get();
            boolean movedPastClearDistance = scanOriginChunk != null && cd > 0 && (
                Math.abs(currentChunk.x - scanOriginChunk.x) > cd ||
                Math.abs(currentChunk.z - scanOriginChunk.z) > cd
            );

            if (movedPastClearDistance || scanOriginChunk == null) {
                // FIX 8: Collect non-PLAYER keys first, then remove — no concurrent modification
                List<ChunkPos> stale = new ArrayList<>();
                for (Map.Entry<ChunkPos, ChunkType> e : trackedChunks.entrySet()) {
                    if (e.getValue() != ChunkType.PLAYER) stale.add(e.getKey());
                }
                for (ChunkPos pos : stale) {
                    trackedChunks.remove(pos);
                    notifiedChunks.remove(pos);
                }
                buildScanQueue();
            } else {
                lastPlayerChunk = currentChunk;
            }

            // Scan one chunk per tick from the queue
            if (!scanDone && scanIndex < scanQueue.size()) {
                scanChunk(scanQueue.get(scanIndex));
                scanIndex++;
                if (scanIndex >= scanQueue.size()) scanDone = true;
            }

            // FIX 9: Publish a snapshot for the render thread — immutable copy, no shared mutable state
            renderSnapshot = new HashMap<>(trackedChunks);

        } catch (Exception e) {
            error("Tick error: " + e.getMessage());
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        try {
            if (mc.world == null || mc.player == null) return;

            // FIX 10: Read from the snapshot, NOT trackedChunks — render thread never touches live map
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
                    case PLAYER -> {
                        fill  = c(playerFill.get());
                        line  = c(playerLine.get());
                        style = playerStyle.get();
                        yMax  = playerBedrockPillar.get() ? 320 : 64;
                    }
                    default -> {
                        fill  = c(activityFill.get());
                        line  = c(activityLine.get());
                        style = activityStyle.get();
                        yMax  = 64;
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
