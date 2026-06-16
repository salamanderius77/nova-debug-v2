package dev.nova.novadebug.modules;

import dev.nova.novadebug.NovaDebugAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
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

    private final Setting<SettingColor> spawnerFill = sgSpawner.add(new ColorSetting.Builder()
        .name("fill-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> spawnerLine = sgSpawner.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(0, 100, 255, 200)).build());
    private final Setting<RenderStyle> spawnerStyle = sgSpawner.add(new EnumSetting.Builder<RenderStyle>()
        .name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean> spawnerBedrockPillar = sgSpawner.add(new BoolSetting.Builder()
        .name("bedrock-pillar").defaultValue(true).build());
    private final Setting<Integer> alpha = sgSpawner.add(new IntSetting.Builder()
        .name("alpha").defaultValue(40).min(1).max(255).sliderMin(1).sliderMax(255).build());

    // FIX: beam-width setting so users can tune the beam size (0.5 = half a block wide)
    private final Setting<Double> beamWidth = sgSpawner.add(new DoubleSetting.Builder()
        .name("beam-width").description("Width of the beam in blocks (Beam style only).")
        .defaultValue(0.5).min(0.1).max(2.0).sliderMin(0.1).sliderMax(2.0).build());

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance").defaultValue(20).min(1).sliderMax(32).build());
    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("Chunks fully scanned per tick. Higher = faster detection, more CPU per tick.")
        .defaultValue(4).min(1).sliderMax(16).build());
    private final Setting<Integer> clearDistance = sgGeneral.add(new IntSetting.Builder()
        .name("clear-distance").defaultValue(20).min(0).sliderMax(32).build());

    // FIX: use a single lock object so scanQueue reassignment and queue operations
    // are always done under the same monitor, eliminating the data-race crash.
    private final Object queueLock = new Object();

    private final ConcurrentHashMap<ChunkPos, BlockPos> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean> scannedChunks = new ConcurrentHashMap<>();
    private PriorityQueue<ChunkPos> scanQueue = new PriorityQueue<>(Comparator.comparingInt(p -> 0));
    private final Set<ChunkPos> queued = ConcurrentHashMap.newKeySet();
    private volatile boolean scanDone = false;
    private volatile ChunkPos scanOriginChunk = null;
    private volatile Map<ChunkPos, BlockPos> renderSnapshot = Collections.emptyMap();

    public SpawnerBeam() {
        super(NovaDebugAddon.CATEGORY, "Spawner Beam", "Highlights chunks containing spawners.");
    }

    @Override
    public void onActivate() {
        // FIX: clear everything under the lock so no stale queue entry can survive
        // a rapid toggle-off / toggle-on that previously caused NPE crashes.
        synchronized (queueLock) {
            trackedChunks.clear();
            scannedChunks.clear();
            queued.clear();
            scanQueue = new PriorityQueue<>(Comparator.comparingInt(p -> 0));
            scanDone = false;
            scanOriginChunk = null;
            renderSnapshot = Collections.emptyMap();
        }
        buildScanQueue();
    }

    @Override
    public void onDeactivate() {
        synchronized (queueLock) {
            trackedChunks.clear();
            scannedChunks.clear();
            queued.clear();
            scanQueue = new PriorityQueue<>();
            scanDone = false;
        }
    }

    private void buildScanQueue() {
        if (mc.player == null) return;
        ChunkPos origin = mc.player.getChunkPos();
        synchronized (queueLock) {
            scanOriginChunk = origin;
            scanQueue = new PriorityQueue<>(Comparator.comparingLong(this::distSqToOrigin));
            queued.clear();
            int r = renderDistance.get();
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    ChunkPos p = new ChunkPos(origin.x + x, origin.z + z);
                    enqueueUnsafe(p); // already inside lock
                }
            }
        }
    }

    private long distSqToOrigin(ChunkPos p) {
        ChunkPos origin = scanOriginChunk;
        if (origin == null) return 0;
        long dx = p.x - origin.x;
        long dz = p.z - origin.z;
        return dx * dx + dz * dz;
    }

    // FIX: split enqueue into a lock-free internal version (called when already holding
    // queueLock) and a public version that acquires the lock. This avoids nested locking
    // and the IllegalMonitorStateException that caused some of the crashes.
    private void enqueueUnsafe(ChunkPos p) {
        if (scannedChunks.containsKey(p)) return;
        if (!queued.add(p)) return;
        scanQueue.add(p);
    }

    private void enqueue(ChunkPos p) {
        synchronized (queueLock) {
            enqueueUnsafe(p);
        }
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null || scanOriginChunk == null) return;
        ChunkPos pos = new ChunkPos(event.chunk().getPos().x, event.chunk().getPos().z);
        int r = renderDistance.get();
        ChunkPos origin = scanOriginChunk;
        if (origin != null
                && Math.abs(pos.x - origin.x) <= r
                && Math.abs(pos.z - origin.z) <= r) {
            enqueue(pos);
        }
    }

    private void scanChunk(ChunkPos pos) {
        // FIX: remove from queued set first so enqueue() can re-add it later if needed.
        queued.remove(pos);
        if (scannedChunks.containsKey(pos)) return;
        if (mc.world == null || !mc.world.isChunkLoaded(pos.x, pos.z)) return;

        WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
        if (chunk == null) return;

        BlockPos found = findSpawnerInChunk(chunk, pos);
        if (found != null) {
            trackedChunks.put(pos, found);
        }
        scannedChunks.put(pos, true);
    }

    private BlockPos findSpawnerInChunk(WorldChunk chunk, ChunkPos pos) {
        if (mc.world == null) return null;
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
            return false;
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // FIX: poll inside the lock so the queue is never accessed while buildScanQueue()
        // is replacing it on another code path.
        List<ChunkPos> toScan = new ArrayList<>();
        synchronized (queueLock) {
            int limit = chunksPerTick.get();
            for (int i = 0; i < limit && !scanQueue.isEmpty(); i++) {
                ChunkPos next = scanQueue.poll();
                if (next != null) toScan.add(next);
            }
            scanDone = scanQueue.isEmpty();
        }
        for (ChunkPos pos : toScan) scanChunk(pos);

        // Snapshot outside the lock - trackedChunks is a ConcurrentHashMap so this is safe.
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
            BlockPos spawnerPos = entry.getValue();

            if (style == RenderStyle.Pillar) {
                // Full-chunk column from bedrock to sky (or y=64)
                int x1 = pos.getStartX(), z1 = pos.getStartZ();
                int x2 = x1 + 16, z2 = z1 + 16;
                int yMax = spawnerBedrockPillar.get() ? 320 : 64;
                event.renderer.box(x1, -64, z1, x2, yMax, z2, fill, line, ShapeMode.Both, 0);

            } else if (style == RenderStyle.Flat) {
                // Single flat slice at y=9-10 across the whole chunk
                int x1 = pos.getStartX(), z1 = pos.getStartZ();
                int x2 = x1 + 16, z2 = z1 + 16;
                event.renderer.box(x1, 9, z1, x2, 10, z2, fill, line, ShapeMode.Both, 0);

            } else if (style == RenderStyle.Beam) {
                // FIX: narrow beacon-style beam rising from the spawner block upward.
                // Uses the exact spawner X/Z position (centre of block) so the beam
                // sits on top of the block rather than spanning the whole chunk.
                if (spawnerPos != null) {
                    double hw = beamWidth.get() / 2.0; // half-width
                    double cx = spawnerPos.getX() + 0.5; // centre of the block
                    double cz = spawnerPos.getZ() + 0.5;
                    int beamTop = spawnerBedrockPillar.get() ? 320 : 128;
                    event.renderer.box(
                        cx - hw, spawnerPos.getY(), cz - hw,
                        cx + hw, beamTop,             cz + hw,
                        fill, line, ShapeMode.Both, 0
                    );
                }
            }
        }
    }

    private Color c(SettingColor sc) { return new Color(sc.r, sc.g, sc.b, sc.a); }
    private Color cWithAlpha(SettingColor sc, int a) { return new Color(sc.r, sc.g, sc.b, a); }
}
