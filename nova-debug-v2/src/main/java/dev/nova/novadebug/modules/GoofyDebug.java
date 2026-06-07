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
    private final Setting<Boolean>      detectSpawners      = sgSpawner.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> spawnerFill         = sgSpawner.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> spawnerLine         = sgSpawner.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(0, 100, 255, 200)).build());
    private final Setting<RenderStyle>  spawnerStyle        = sgSpawner.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      spawnerToast        = sgSpawner.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());
    // Separate bedrock pillar toggle for spawners
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
    // Activity never gets bedrock pillar — intentionally omitted

    // Player settings
    private final Setting<Boolean>      detectPlayers      = sgPlayers.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> playerFill         = sgPlayers.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(180, 0, 255, 40)).build());
    private final Setting<SettingColor> playerLine         = sgPlayers.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(180, 0, 255, 200)).build());
    private final Setting<RenderStyle>  playerStyle        = sgPlayers.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      playerToast        = sgPlayers.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());
    // Separate bedrock pillar toggle for players
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
        .defaultValue(4).min(1).sliderMax(20).build());

    private final Setting<Integer> clearDistance = sgGeneral.add(new IntSetting.Builder()
        .name("clear-distance")
        .description("How many chunks you must move before spawner/activity highlights clear. 0 = never clear.")
        .defaultValue(26).min(0).sliderMax(32).build());

    // Internal state
    private enum ChunkType { PLAYER, SPAWNER, ACTIVITY }

    private final ConcurrentHashMap<ChunkPos, ChunkType> trackedChunks  = new ConcurrentHashMap<>();
    private final Set<ChunkPos>                          notifiedChunks = Collections.synchronizedSet(new HashSet<>());
    private final List<ChunkPos>                         scanQueue      = new ArrayList<>();
    private int      scanIndex      = 0;
    private boolean  scanDone       = false;
    private ChunkPos lastPlayerChunk  = null;
    private ChunkPos scanOriginChunk  = null;

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
        buildScanQueue();
        info("Nova Debug v2 by Saint - active.");
    }

    @Override
    public void onDeactivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
        scanQueue.clear();
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
        List<ChunkPos> all = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                all.add(new ChunkPos(playerChunk.x + dx, playerChunk.z + dz));
            }
        }
        // Sort by closest first
        all.sort(Comparator.comparingInt(p ->
            Math.abs(p.x - playerChunk.x) + Math.abs(p.z - playerChunk.z)));
        int limit = chunksToScan.get();
        for (int i = 0; i < Math.min(limit, all.size()); i++) {
            scanQueue.add(all.get(i));
        }
    }

    private String getSpawnerType(WorldChunk chunk, BlockPos spawnerPos) {
        try {
            BlockEntity be = chunk.getBlockEntity(spawnerPos);
            if (be instanceof MobSpawnerBlockEntity spawner) {
                NbtCompound nbt = spawner.createNbt(mc.world.getRegistryManager());
                if (nbt.contains("SpawnData")) {
                    String entityId = nbt.getCompound("SpawnData").getCompound("entity").getString("id");
                    if (entityId.contains(":")) entityId = entityId.split(":")[1];
                    if (!entityId.isEmpty())
                        return entityId.substring(0, 1).toUpperCase() + entityId.substring(1).replace("_", " ");
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private void scanChunk(ChunkPos pos) {
        try {
            if (mc.world == null || mc.player == null) return;
            if (!mc.world.isChunkLoaded(pos.x, pos.z)) return;

            WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);

            // Players — check first, highest priority
            if (detectPlayers.get()) {
                for (PlayerEntity p : mc.world.getPlayers()) {
                    if (p == mc.player) continue;
                    if (p.getChunkPos().x == pos.x && p.getChunkPos().z == pos.z) {
                        boolean isNew = !ChunkType.PLAYER.equals(trackedChunks.get(pos));
                        trackedChunks.put(pos, ChunkType.PLAYER);
                        if (isNew && playerToast.get() && !notifiedChunks.contains(pos)) {
                            info("§d[Nova Debug] Player §f" + p.getName().getString() + " §dfound!");
                            notifiedChunks.add(pos);
                        }
                        return;
                    }
                }
            }

            // Spawners — scan the ENTIRE chunk height (-64 to 320) to catch all spawners
            if (detectSpawners.get()) {
                BlockPos foundPos = null;
                int spawnerCount  = 0;

                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        // Full world height scan: -64 to 320
                        for (int y = mc.world.getBottomY(); y < mc.world.getTopY(); y++) {
                            BlockPos bp = new BlockPos(pos.getStartX() + lx, y, pos.getStartZ() + lz);
                            if (chunk.getBlockState(bp).isOf(Blocks.SPAWNER)) {
                                if (foundPos == null) foundPos = bp;
                                spawnerCount++;
                            }
                        }
                    }
                }

                if (foundPos != null) {
                    boolean isNew = !ChunkType.SPAWNER.equals(trackedChunks.get(pos));
                    trackedChunks.put(pos, ChunkType.SPAWNER);
                    if (isNew && spawnerToast.get() && !notifiedChunks.contains(pos)) {
                        String spawnerType = getSpawnerType(chunk, foundPos);
                        if (spawnerCount == 1) {
                            info("§9[Nova Debug] §f" + spawnerType + " §9Spawner found!");
                        } else {
                            info("§9[Nova Debug] §f" + spawnerCount + " §9Spawners found! (first: §f" + spawnerType + "§9)");
                        }
                        notifiedChunks.add(pos);
                    }
                    return;
                }
            }

            // Activity
            if (detectActivity.get()) {
                boolean isNew = !ChunkType.ACTIVITY.equals(trackedChunks.get(pos));
                trackedChunks.put(pos, ChunkType.ACTIVITY);
                if (isNew && activityToast.get() && !notifiedChunks.contains(pos)) {
                    info("§c[Nova Debug] Player Activity §ffound!");
                    notifiedChunks.add(pos);
                }
            }
        } catch (Exception e) {
            error("Scan error: " + e.getMessage());
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            if (mc.world == null || mc.player == null) return;

            // Live player tracking every tick
            if (detectPlayers.get()) {
                ChunkPos playerChunk = mc.player.getChunkPos();
                int radius = Math.min(renderDistance.get(), 32);

                // Remove chunks where the tracked player has left
                trackedChunks.entrySet().removeIf(e -> {
                    if (e.getValue() != ChunkType.PLAYER) return false;
                    ChunkPos pos = e.getKey();
                    for (PlayerEntity p : mc.world.getPlayers()) {
                        if (p == mc.player) continue;
                        if (p.getChunkPos().x == pos.x && p.getChunkPos().z == pos.z) return false;
                    }
                    notifiedChunks.remove(pos);
                    return true;
                });

                // Add new player positions
                for (PlayerEntity p : mc.world.getPlayers()) {
                    if (p == mc.player) continue;
                    ChunkPos pos = p.getChunkPos();
                    if (Math.abs(pos.x - playerChunk.x) > radius) continue;
                    if (Math.abs(pos.z - playerChunk.z) > radius) continue;
                    boolean isNew = !ChunkType.PLAYER.equals(trackedChunks.get(pos));
                    trackedChunks.put(pos, ChunkType.PLAYER);
                    if (isNew && playerToast.get() && !notifiedChunks.contains(pos)) {
                        info("§d[Nova Debug] Player §f" + p.getName().getString() + " §dfound!");
                        notifiedChunks.add(pos);
                    }
                }
            }

            // Only clear spawner/activity data when player moves past clearDistance
            ChunkPos currentChunk = mc.player.getChunkPos();
            int cd = clearDistance.get();
            boolean movedPastClearDistance = scanOriginChunk != null && cd > 0 && (
                Math.abs(currentChunk.x - scanOriginChunk.x) > cd ||
                Math.abs(currentChunk.z - scanOriginChunk.z) > cd
            );

            if (movedPastClearDistance || scanOriginChunk == null) {
                trackedChunks.entrySet().removeIf(e -> e.getValue() != ChunkType.PLAYER);
                notifiedChunks.clear();
                buildScanQueue();
            } else {
                lastPlayerChunk = currentChunk;
            }

            // Scan one chunk per tick
            if (!scanDone && scanIndex < scanQueue.size()) {
                scanChunk(scanQueue.get(scanIndex));
                scanIndex++;
                if (scanIndex >= scanQueue.size()) scanDone = true;
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

            double px      = mc.player.getX();
            double pz      = mc.player.getZ();
            int distBlocks = renderDistance.get() * 16;
            double distSq  = (double) distBlocks * distBlocks;

            for (Map.Entry<ChunkPos, ChunkType> entry : trackedChunks.entrySet()) {
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
                        // Spawner has its own bedrock pillar toggle
                        yMax  = spawnerBedrockPillar.get() ? 320 : 64;
                    }
                    case PLAYER -> {
                        fill  = c(playerFill.get());
                        line  = c(playerLine.get());
                        style = playerStyle.get();
                        // Player has its own bedrock pillar toggle
                        yMax  = playerBedrockPillar.get() ? 320 : 64;
                    }
                    default -> {
                        // Activity — never bedrock pillar, always underground only
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
