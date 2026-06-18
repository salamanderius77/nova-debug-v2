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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnerBeam extends Module {

    public enum RenderStyle { Pillar, Flat, Beam }

    private final SettingGroup sgSpawner = settings.createGroup("Spawners");
    private final SettingGroup sgGeneral  = settings.createGroup("General");

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
    private final Setting<Double> beamWidth = sgSpawner.add(new DoubleSetting.Builder()
        .name("beam-width").description("Width of the beam in blocks (Beam style only).")
        .defaultValue(0.5).min(0.1).max(2.0).sliderMin(0.1).sliderMax(2.0).build());

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance").defaultValue(20).min(1).sliderMax(32).build());
    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("Chunks scanned per tick. Higher = faster but more CPU.")
        .defaultValue(4).min(1).sliderMax(16).build());

    private final Setting<Boolean> deepslateBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("deepslate-bypass").description("Bypass 40 height limit - scan under deepslate").defaultValue(true).build());
    private final Setting<Integer> extraYScan = sgGeneral.add(new IntSetting.Builder()
        .name("extra-y-scan").description("Extra blocks deeper to scan").defaultValue(120).min(0).max(200).build());

    private final Object queueLock = new Object();

    private final ConcurrentHashMap<ChunkPos, List<BlockPos>> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean> scannedChunks = new ConcurrentHashMap<>();
    private PriorityQueue<ChunkPos> scanQueue = new PriorityQueue<>(Comparator.comparingInt(p -> 0));
    private final Set<ChunkPos> queued = ConcurrentHashMap.newKeySet();
    private volatile ChunkPos scanOriginChunk = null;
    private volatile Map<ChunkPos, List<BlockPos>> renderSnapshot = Collections.emptyMap();

    public SpawnerBeam() {
        super(NovaDebugAddon.CATEGORY, "Spawner Beam", "Highlights spawners under deepslate");
    }

    @Override
    public void onActivate() {
        synchronized (queueLock) {
            trackedChunks.clear();
            scannedChunks.clear();
            queued.clear();
            scanQueue       = new PriorityQueue<>(Comparator.comparingInt(p -> 0));
            scanOriginChunk = null;
            renderSnapshot  = Collections.emptyMap();
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
            for (int x = -r; x <= r; x++)
                for (int z = -r; z <= r; z++)
                    enqueueUnsafe(new ChunkPos(origin.x + x, origin.z + z));
        }
    }

    private long distSqToOrigin(ChunkPos p) {
        ChunkPos o = scanOriginChunk;
        if (o == null) return 0;
        long dx = p.x - o.x, dz = p.z - o.z;
        return dx * dx + dz * dz;
    }

    private void enqueueUnsafe(ChunkPos p) {
        if (scannedChunks.containsKey(p)) return;
        if (!queued.add(p)) return;
        scanQueue.add(p);
    }

    private void scanChunk(ChunkPos pos) {
        queued.remove(pos);
        if (scannedChunks.containsKey(pos)) return;
        if (mc.world == null || !mc.world.isChunkLoaded(pos.x, pos.z)) return;

        WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
        if (chunk == null) return;

        List<BlockPos> found = findAllSpawnersInChunk(chunk, pos);
        if (!found.isEmpty()) trackedChunks.put(pos, found);
        scannedChunks.put(pos, true);
    }

    private List<BlockPos> findAllSpawnersInChunk(WorldChunk chunk, ChunkPos pos) {
        List<BlockPos> result = new ArrayList<>();
        if (mc.world == null) return result;

        int minY = mc.world.getBottomY() - (deepslateBypass.get() ? extraYScan.get() : 0);
        int maxY = mc.world.getTopY();

        for (int sY = minY; sY < maxY; sY += 16) {
            if (isSectionEmpty(chunk, sY)) continue;
            int yEnd = Math.min(sY + 16, maxY);
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    for (int y = Math.max(sY, minY); y < yEnd; y++) {
                        BlockPos bp = new BlockPos(pos.getStartX() + x, y, pos.getStartZ() + z);
                        if (chunk.getBlockState(bp).getBlock() == Blocks.SPAWNER)
                            result.add(bp);
                    }
        }
        return result;
    }

    private boolean isSectionEmpty(WorldChunk chunk, int sY) {
        try { return chunk.getSection(chunk.getSectionIndex(sY)).isEmpty(); }
        catch (Exception e) { return false; }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        ChunkPos origin = scanOriginChunk;
        if (origin != null) {
            int r = renderDistance.get();
            synchronized (queueLock) {
                for (int x = -r; x <= r; x++)
                    for (int z = -r; z <= r; z++) {
                        ChunkPos p = new ChunkPos(origin.x + x, origin.z + z);
                        if (!scannedChunks.containsKey(p) && mc.world.isChunkLoaded(p.x, p.z))
                            enqueueUnsafe(p);
                    }
            }
        }

        List<ChunkPos> toScan = new ArrayList<>();
        synchronized (queueLock) {
            int limit = chunksPerTick.get();
            for (int i = 0; i < limit && !scanQueue.isEmpty(); i++) {
                ChunkPos next = scanQueue.poll();
                if (next != null) toScan.add(next);
            }
        }
        for (ChunkPos pos : toScan) scanChunk(pos);

        renderSnapshot = new HashMap<>(trackedChunks);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        Map<ChunkPos, List<BlockPos>> snapshot = renderSnapshot;
        if (snapshot.isEmpty()) return;

        Color fill  = cWithAlpha(spawnerFill.get(), alpha.get());
        Color line  = c(spawnerLine.get());
        RenderStyle style = spawnerStyle.get();

        for (Map.Entry<ChunkPos, List<BlockPos>> entry : snapshot.entrySet()) {
            ChunkPos pos          = entry.getKey();
            List<BlockPos> spawners = entry.getValue();
            if (spawners == null || spawners.isEmpty()) continue;

            if (style == RenderStyle.Pillar) {
                int x1 = pos.getStartX(), z1 = pos.getStartZ();
                int x2 = x1 + 16,        z2 = z1 + 16;
                int yMax = spawnerBedrockPillar.get() ? 320 : 64;
                event.renderer.box(x1, -64, z1, x2, yMax, z2, fill, line, ShapeMode.Both, 0);

            } else if (style == RenderStyle.Flat) {
                int x1 = pos.getStartX(), z1 = pos.getStartZ();
                int x2 = x1 + 16,        z2 = z1 + 16;
                event.renderer.box(x1, 9, z1, x2, 10, z2, fill, line, ShapeMode.Both, 0);

            } else { // Beam
                double hw = beamWidth.get() / 2.0;
                int beamTop = spawnerBedrockPillar.get() ? 320 : 128;
                for (BlockPos spawnerPos : spawners) {
                    double cx = spawnerPos.getX() + 0.5;
                    double cz = spawnerPos.getZ() + 0.5;
                    event.renderer.box(
                        cx - hw, spawnerPos.getY(), cz - hw,
                        cx + hw, beamTop,           cz + hw,
                        fill, line, ShapeMode.Both, 0
                    );
                }
            }
        }
    }

    private Color c(SettingColor sc)                 { return new Color(sc.r, sc.g, sc.b, sc.a); }
    private Color cWithAlpha(SettingColor sc, int a) { return new Color(sc.r, sc.g, sc.b, a); }
}
