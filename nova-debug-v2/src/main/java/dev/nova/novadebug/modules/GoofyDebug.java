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
import net.minecraft.block.Block;
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
        .description("Extend spawner pillar from bedrock (-64) to sky (320).")
        .defaultValue(true).build());

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

    // Activity blocks — things players place underground
    private static final Set<Block> ACTIVITY_BLOCKS = new HashSet<>(Arrays.asList(
        Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.FURNACE, Blocks.CRAFTING_TABLE,
        Blocks.TORCH, Blocks.WALL_TORCH, Blocks.LADDER, Blocks.OAK_PLANKS,
        Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS,
        Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.MANGROVE_PLANKS,
        Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.STONE_BRICKS,
        Blocks.CRACKED_STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS,
        Blocks.OAK_LOG, Blocks.SPRUCE_LOG, Blocks.BIRCH_LOG,
        Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
        Blocks.IRON_DOOR, Blocks.OAK_DOOR, Blocks.SPRUCE_DOOR,
        Blocks.OAK_FENCE, Blocks.SPRUCE_FENCE, Blocks.COBBLESTONE_WALL,
        Blocks.GLOWSTONE, Blocks.SEA_LANTERN, Blocks.LANTERN,
        Blocks.SOUL_LANTERN, Blocks.SHROOMLIGHT, Blocks.JACK_O_LANTERN,
        Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER, Blocks.OBSERVER,
        Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.TNT,
        Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.CHIPPED_ANVIL,
        Blocks.BREWING_STAND, Blocks.CAULDRON, Blocks.BARREL,
        Blocks.BLAST_FURNACE, Blocks.SMOKER, Blocks.CAMPFIRE,
        Blocks.SOUL_CAMPFIRE, Blocks.LOOM, Blocks.CARTOGRAPHY_TABLE,
        Blocks.FLETCHING_TABLE, Blocks.SMITHING_TABLE, Blocks.GRINDSTONE,
        Blocks.STONECUTTER, Blocks.COMPOSTER, Blocks.BEE_NEST,
        Blocks.BEEHIVE, Blocks.BOOKSHELF, Blocks.LECTERN,
        Blocks.NOTE_BLOCK, Blocks.JUKEBOX, Blocks.DAYLIGHT_DETECTOR,
        Blocks.TRIPWIRE_HOOK, Blocks.LEVER, Blocks.STONE_BUTTON,
        Blocks.OAK_BUTTON, Blocks.STONE_PRESSURE_PLATE,
        Blocks.OAK_PRESSURE_PLATE, Blocks.REDSTONE_WIRE,
        Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH,
        Blocks.REPEATER, Blocks.COMPARATOR, Blocks.REDSTONE_LAMP,
        Blocks.TARGET, Blocks.RAIL, Blocks.POWERED_RAIL,
        Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL,
        Blocks.WHITE_WOOL, Blocks.ORANGE_WOOL, Blocks.MAGENTA_WOOL,
        Blocks.LIGHT_BLUE_WOOL, Blocks.YELLOW_WOOL, Blocks.LIME_WOOL,
        Blocks.PINK_WOOL, Blocks.GRAY_WOOL, Blocks.LIGHT_GRAY_WOOL,
        Blocks.CYAN_WOOL, Blocks.PURPLE_WOOL, Blocks.BLUE_WOOL,
        Blocks.BROWN_WOOL, Blocks.GREEN_WOOL, Blocks.RED_WOOL, Blocks.BLACK_WOOL,
        Blocks.WHITE_CARPET, Blocks.ORANGE_CARPET, Blocks.YELLOW_CARPET,
        Blocks.WHITE_BED, Blocks.ORANGE_BED, Blocks.RED_BED,
        Blocks.ENDER_CHEST, Blocks.SHULKER_BOX,
        Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX,
        Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX,
        Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX,
        Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX,
        Blocks.LIGHT_GRAY_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX,
        Blocks.PURPLE_SHULKER_BOX, Blocks.BLUE_SHULKER_BOX,
        Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX,
        Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX
    ));

    private enum ChunkType { PLAYER, SPAWNER, ACTIVITY }

    private final ConcurrentHashMap<ChunkPos, ChunkType> trackedChunks  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean>   notifiedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean>   scannedChunks  = new ConcurrentHashMap<>();

    private final List<ChunkPos> scanQueue  = new ArrayList<>();
    private int      scanIndex     = 0;
    private boolean  scanDone      = false;
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
        scanIndex        = 0;
        scanDone         = false;
        scanOriginChunk  = null;
        renderSnapshot   = Collections.emptyMap();
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
                // Only queue chunks we haven't scanned yet
                if (!scannedChunks.containsKey(cp)) {
                    all.add(cp);
                }
            }
        }
        all.sort(Comparator.comparingInt(p ->
            Math.abs(p.x - playerChunk.x) + Math.abs(p.z - playerChunk.z)));

        scanQueue.addAll(all);
    }

    private String getSpawnerType(WorldChunk chunk, BlockPos spawnerPos) {
        if (chunk == null || spawnerPos == null) return "Unknown";
        try {
            BlockEntity be = chunk.getBlockEntity(spawnerPos);
            if (be instanceof MobSpawnerBlockEntity spawner) {
                NbtCompound nbt = spawner.createNbt(mc.world.getRegistryManager());
                if (nbt == null || !nbt.contains("SpawnData", 10)) return "Unknown";
                NbtCompound spawnData = (NbtCompound) nbt.get("SpawnData");
                if (spawnData == null || !spawnData.contains("entity", 10)) return "Unknown";
                NbtCompound entity = (NbtCompound) spawnData.get("entity");
                if (entity == null || !entity.contains("id", 8)) return "Unknown";
                String entityId = entity.get("id").asString();
                if (entityId == null || entityId.isEmpty()) return "Unknown";
                if (entityId.contains(":")) entityId = entityId.split(":")[1];
                return entityId.substring(0, 1).toUpperCase() + entityId.substring(1).replace("_", " ");
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    // Returns true if the block is below the surface (underground scan only y < 64)
    private boolean isUnderground(int y) {
        return y < 64;
    }

    private void scanChunk(ChunkPos pos) {
        try {
            if (mc.world == null || mc.player == null) return;
            if (!mc.world.isChunkLoaded(pos.x, pos.z)) return;

            WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
            if (chunk == null || chunk.isEmpty()) return;

            // Mark as scanned regardless of result
            scannedChunks.put(pos, Boolean.TRUE);

            // --- PLAYERS (highest priority, always wins) ---
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

                outer:
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
                    // Only update if not already tracked as PLAYER (player > spawner priority)
                    ChunkType existing = trackedChunks.get(pos);
                    if (existing != ChunkType.PLAYER) {
                        boolean isNew = existing != ChunkType.SPAWNER;
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
                    }
                    return;
                }
            }

            // --- ACTIVITY (lowest priority — only if no spawner found, and only underground) ---
            // BUG FIX: Only tag as ACTIVITY if we actually find player-placed blocks underground.
            // Previously this tagged EVERY chunk as ACTIVITY, overwriting real spawner finds.
            if (detectActivity.get()) {
                boolean activityFound = false;
                int bottomY = mc.world.getBottomY();

                activityScan:
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        // Only scan underground (y < 64)
                        for (int y = bottomY; y < 64; y++) {
                            try {
                                BlockPos bp = new BlockPos(pos.getStartX() + lx, y, pos.getStartZ() + lz);
                                Block block = chunk.getBlockState(bp).getBlock();
                                if (ACTIVITY_BLOCKS.contains(block)) {
                                    activityFound = true;
                                    break activityScan;
                                }
                            } catch (Exception ignored) {}
                        }
                    }
                }

                if (activityFound) {
                    ChunkType existing = trackedChunks.get(pos);
                    if (existing != ChunkType.PLAYER && existing != ChunkType.SPAWNER) {
                        boolean isNew = existing != ChunkType.ACTIVITY;
                        trackedChunks.put(pos, ChunkType.ACTIVITY);
                        if (isNew && activityToast.get() && !notifiedChunks.containsKey(pos)) {
                            info("§c[Nova Debug] Player Activity §ffound!");
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
                // Remove chunks where player has left
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
                    // Re-queue for rescan so spawner/activity gets re-evaluated
                    scannedChunks.remove(pos);
                    if (!scanQueue.contains(pos)) scanQueue.add(pos);
                    scanDone = false;
                }

                // Add new player positions
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

            // --- Clear data if player moved past clearDistance ---
            int cd = clearDistance.get();
            boolean movedFar = scanOriginChunk != null && cd > 0 && (
                Math.abs(currentChunk.x - scanOriginChunk.x) > cd ||
                Math.abs(currentChunk.z - scanOriginChunk.z) > cd
            );

            if (movedFar || scanOriginChunk == null) {
                // Clear non-player entries
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
                // Check for newly loaded chunks that need scanning
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

            // Publish snapshot for render thread
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
