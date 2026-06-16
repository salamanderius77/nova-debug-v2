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
import dev.nova.novadebug.SaintToast;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SpawnerBeam extends Module {

    public enum RenderStyle { Pillar, Flat, Beam }

    private final SettingGroup sgSpawner = settings.createGroup("Spawners");
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> spawnerFill = sgSpawner.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> spawnerLine = sgSpawner.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(0, 100, 255, 200)).build());
    private final Setting<RenderStyle> spawnerStyle = sgSpawner.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean> saintNotifier = sgSpawner.add(new BoolSetting.Builder().name("saint-notifier").description("Shows a HUD toast notification when a spawner is detected.").defaultValue(false).build());
    private final Setting<Boolean> spawnerBedrockPillar = sgSpawner.add(new BoolSetting.Builder().name("bedrock-pillar").defaultValue(true).build());
    private final Setting<Integer> alpha = sgSpawner.add(new IntSetting.Builder().name("alpha").defaultValue(40).min(1).max(255).sliderMin(1).sliderMax(255).build());

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder().name("render-distance").defaultValue(20).min(1).sliderMax(32).build());
    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder().name("chunks-per-tick").defaultValue(1).min(1).sliderMax(4).build());
    private final Setting<Integer> clearDistance = sgGeneral.add(new IntSetting.Builder().name("clear-distance").defaultValue(20).min(0).sliderMax(32).build());

    private final ConcurrentHashMap<ChunkPos, BlockPos> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean> notifiedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean> scannedChunks = new ConcurrentHashMap<>();
    private final List<ChunkPos> scanQueue = new ArrayList<>();
    private int scanIndex = 0;
    private boolean scanDone = false;
    private ChunkPos scanOriginChunk = null;
    private volatile Map<ChunkPos, BlockPos> renderSnapshot = Collections.emptyMap();

    public SpawnerBeam() {
        super(NovaDebugAddon.CATEGORY, "Spawner Beam", "Highlights chunks containing spawners.");
    }

    @Override
    public void onActivate() {
        trackedChunks.clear(); notifiedChunks.clear(); scannedChunks.clear();
        scanQueue.clear(); scanIndex = 0; scanDone = false;
        scanOriginChunk = null; renderSnapshot = Collections.emptyMap();
        buildScanQueue();
    }

    @Override
    public void onDeactivate() {
        trackedChunks.clear(); notifiedChunks.clear(); scannedChunks.clear();
        scanQueue.clear(); renderSnapshot = Collections.emptyMap();
        scanDone = false;
    }

    private void buildScanQueue() {
        scanQueue.clear();
        if (mc.player == null) return;
        scanOriginChunk = mc.player.getChunkPos();
        int r = renderDistance.get();
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                scanQueue.add(new ChunkPos(scanOriginChunk.x + x, scanOriginChunk.z + z));
            }
        }
    }

    private void scanChunk(ChunkPos pos) {
        if (scannedChunks.containsKey(pos)) return;
        WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = -64; y < 320; y++) {  // full height
                    BlockPos bp = new BlockPos(pos.getStartX() + x, y, pos.getStartZ() + z);
                    if (chunk.getBlockState(bp).getBlock() == Blocks.SPAWNER) {
                        trackedChunks.put(pos, bp);
                        if (saintNotifier.get() && !notifiedChunks.containsKey(pos)) {
                            SaintToast.get().show("Spawner Beam Found!", "X: " + bp.getX() + " Y: " + bp.getY() + " Z: " + bp.getZ());
                            notifiedChunks.put(pos, true);
                        }
                        return;
                    }
                }
            }
        }
        scannedChunks.put(pos, true);
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // Scan a few chunks per tick
        for (int i = 0; i < chunksPerTick.get() && scanIndex < scanQueue.size(); i++) {
            if (scanIndex < scanQueue.size()) {
                scanChunk(scanQueue.get(scanIndex));
                scanIndex++;
            }
        }

        if (scanIndex >= scanQueue.size()) {
            scanDone = true;
        }

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
