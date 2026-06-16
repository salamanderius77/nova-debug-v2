package dev.nova.novadebug.modules;

import dev.nova.novadebug.NovaDebugAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent; // NOTE: verify this class exists in your Meteor version; if it doesn't compile, see comment in onActivate region below
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnerBeam extends Module {

    public enum RenderStyle { Pillar, Flat, Beam }

    private final SettingGroup sgSpawner = settings.createGroup("Spawners");
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> spawnerFill = sgSpawner.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> spawnerLine = sgSpawner.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(0, 100, 255, 200)).build());
    private final Setting<RenderStyle> spawnerStyle = sgSpawner.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean> spawnerBedrockPillar = sgSpawner.add(new BoolSetting.Builder().name("bedrock-pillar").defaultValue(true).build());
    private final Setting<Integer> alpha = sgSpawner.add(new IntSetting.Builder().name("alpha").defaultValue(40).min(1).max(255).sliderMin(1).sliderMax(255).build());

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder().name("render-distance").defaultValue(20).min(1).sliderMax(32).build());
    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder().name("chunks-per-tick").description("Chunks fully scanned per tick. Higher = faster detection, more CPU per tick.").defaultValue(4).min(1).sliderMax(16).build());
    private final Setting<Integer> clearDistance = sgGeneral.add(new IntSetting.Builder().name("clear-distance").defaultValue(20).min(0).sliderMax(32).build());

    private final ConcurrentHashMap<ChunkPos, BlockPos> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean> scannedChunks = new ConcurrentHashMap<>();
    // Priority queue ordered by distance from player so nearby spawners are found first.
    // Comparator is replaced with a real distance-based one in buildScanQueue(); this default
    // just prevents a crash if anything is ever inserted before that runs.
    private PriorityQueue<ChunkPos> scanQueue = new PriorityQueue<>(Comparator.comparingInt(p -> 0));
    // Tracks what's currently in scanQueue so we don't enqueue duplicates (O(1) check instead of scanning the list).
    private final Set<ChunkPos> queued = ConcurrentHashMap.newKeySet();
    private boolean scanDone = false;
    private ChunkPos scanOriginChunk = null;
    private volatile Map<ChunkPos, BlockPos> renderSnapshot = Collections.emptyMap();

    public SpawnerBeam() {
        super(NovaDebugAddon.CATEGORY, "Spawner Beam", "Highlights chunks containing spawners.");
    }

    @Override
    public void onActivate() {
        trackedChunks.clear(); scannedChunks.clear();
        queued.clear(); scanDone = false;
        scanOriginChunk = null; renderSnapshot = Collections.emptyMap();
        buildScanQueue();
    }

    @Override
    public void onDeactivate() {
        trackedChunks.clear(); scannedChunks.clear();
        queued.clear();
        scanQueue = new PriorityQueue<>();
        scanDone = false;
    }

    private void buildScanQueue() {
        if (mc.player == null) return;
        scanOriginChunk = mc.player.getChunkPos();
        // Order by squared distance from the player so close chunks (and therefore close
        // spawners) are discovered first instead of in raster order.
        scanQueue = new PriorityQueue<>(Comparator.comparingLong(this::distSqToOrigin));
        queued.clear();
        int r = renderDistance.get();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                ChunkPos p = new ChunkPos(scanOriginChunk.x + x, scanOriginChunk.z + z);
                enqueue(p);
            }
        }
    }

    private long distSqToOrigin(ChunkPos p) {
        if (scanOriginChunk == null) return 0;
        long dx = p.x - scanOriginChunk.x;
        long dz = p.z - scanOriginChunk.z;
        return dx * dx + dz * dz;
    }

    private void enqueue(ChunkPos p) {
        if (scannedChunks.containsKey(p)) return;
        if (!queued.add(p)) return; // already in queue
        scanQueue.add(p);
    }

    /**
     * Fires when a chunk's data is (re)sent to the client, i.e. it just became available/loaded.
     * NOTE: confirm this event class/method name matches your Meteor version - if ChunkDataEvent
     * doesn't exist, look for an equivalent world/chunk-load event in
     * meteordevelopment.meteorclient.events.world and swap the import + signature here.
     */
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null || scanOriginChunk == null) return;
        ChunkPos pos = new ChunkPos(event.chunk().getPos().x, event.chunk().getPos().z);
        int r = renderDistance.get();
        // Only auto-track chunks within our configured radius of where we started scanning from.
        if (Math.abs(pos.x - scanOriginChunk.x) <= r && Math.abs(pos.z - scanOriginChunk.z) <= r) {
            enqueue(pos);
        }
    }

    private void scanChunk(ChunkPos pos) {
        queued.remove(pos);
        if (scannedChunks.containsKey(pos)) return;
        if (!mc.world.isChunkLoaded(pos.x, pos.z)) {
            // Not loaded yet - don't mark scanned, don't requeue here either.
            // It'll be picked up automatically by onChunkData once it loads,
            // which is faster and avoids busy-looping on unloaded chunks.
            return;
        }
        WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
        if (chunk == null) return;

        BlockPos found = findSpawnerInChunk(chunk, pos);
        if (found != null) {
            trackedChunks.put(pos, found);
        }
        scannedChunks.put(pos, true);
    }

    /**
     * Scans a chunk for a spawner block. Walks bottom-up in 16-block sections and skips a
     * section immediately if it's empty (no blocks at all besides air), which is the common
     * case for most of the build height in a typical world and avoids 1-by-1 checks across
     * the full 384-block column for sections that can't contain anything.
     */
    private BlockPos findSpawnerInChunk(WorldChunk chunk, ChunkPos pos) {
        int minY = mc.world.getBottomY();
        int maxY = mc.world.getTopY();
        for (int sectionY = minY; sectionY < maxY; sectionY += 16) {
            if (isSectionEmpty(chunk, sectionY)) continue;
            int yEnd = Math.min(sectionY + 16, maxY);
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = sectionY; y < yEnd; y++) {
                        BlockPos bp = new BlockPos(pos.getStartX() + x, y, pos.getStartZ() + z);
                        if (chunk.getBlockState(bp).getBlock() == Blocks.SPAWNER) {
                            return bp;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isSectionEmpty(WorldChunk chunk, int sectionY) {
        try {
            return chunk.getSection(chunk.getSectionIndex(sectionY)).isEmpty();
        } catch (Exception e) {
            // If the API differs slightly across versions, fall back to "not empty"
            // so we still scan it correctly instead of silently skipping real blocks.
            return false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        for (int i = 0; i < chunksPerTick.get() && !scanQueue.isEmpty(); i++) {
            ChunkPos next = scanQueue.poll();
            if (next != null) scanChunk(next);
        }

        scanDone = scanQueue.isEmpty();

        // Update render snapshot
        renderSnapshot = new HashMap<>(trackedChunks);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        Map<ChunkPos, BlockPos> snapshot = renderSnapshot;
        if (snapshot.isEmpty()) return;

        Color fill = cWithAlpha(spawnerFill.get(), alpha.get());
        Color line = c(spawnerLine.get());
        RenderStyle style = spawnerStyle.get();

        for (Map.Entry<ChunkPos, BlockPos> entry : snapshot.entrySet()) {
            ChunkPos pos = entry.getKey();
            int x1 = pos.getStartX(), z1 = pos.getStartZ();
            int x2 = x1 + 16, z2 = z1 + 16;

            if (style == RenderStyle.Pillar || style == RenderStyle.Beam) {
                int yMax = spawnerBedrockPillar.get() ? 320 : 64;
                event.renderer.box(x1, -64, z1, x2, yMax, z2, fill, line, ShapeMode.Both, 0);
            } else {
                event.renderer.box(x1, 9, z1, x2, 10, z2, fill, line, ShapeMode.Both, 0);
            }
        }
    }

    private Color c(SettingColor sc) { return new Color(sc.r, sc.g, sc.b, sc.a); }
    private Color cWithAlpha(SettingColor sc, int a) { return new Color(sc.r, sc.g, sc.b, a); }
}
