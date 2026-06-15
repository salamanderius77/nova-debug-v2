package dev.nova.novadebug.modules;

import dev.nova.novadebug.NovaDebugAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
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
    private final Setting<Boolean> spawnerToast = sgSpawner.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());
    private final Setting<Boolean> spawnerBedrockPillar = sgSpawner.add(new BoolSetting.Builder()
        .name("bedrock-pillar").defaultValue(true).build());
    private final Setting<Integer> alpha = sgSpawner.add(new IntSetting.Builder()
        .name("alpha").defaultValue(40).min(1).max(255).sliderMin(1).sliderMax(255).build());

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance").defaultValue(20).min(1).sliderMax(32).build());

    private final Setting<Integer> chunksPerTick = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-per-tick").defaultValue(1).min(1).sliderMax(5).build());

    private final Setting<Integer> clearDistance = sgGeneral.add(new IntSetting.Builder()
        .name("clear-distance").defaultValue(20).min(0).sliderMax(32).build());

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
        trackedChunks.clear();
        notifiedChunks.clear();
        scannedChunks.clear();
        scanQueue.clear();
        scanIndex = 0;
        scanDone = false;
        scanOriginChunk = null;
        renderSnapshot = Collections.emptyMap();
        buildScanQueue();
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

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ChunkDeltaUpdateS2CPacket) {
            // Deepslate bypass - prevent server from hiding deep blocks
            // event.cancel();   // Uncomment this line for more aggressive bypass (higher ban risk)
        }
    }

    private void buildScanQueue() {
        if (mc.world == null || mc.player == null) return;
        scanQueue.clear();
        scanIndex = 0;
        scanDone = false;
        ChunkPos playerChunk = mc.player.getChunkPos();
        scanOriginChunk = playerChunk;

        int radius = Math.min(renderDistance.get(), 24);
        List<ChunkPos> all = new ArrayList<>();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                ChunkPos cp = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                if (!scannedChunks.containsKey(cp)) all.add(cp);
            }
        }

        all.sort(Comparator.comparingInt(p -> Math.abs(p.x - playerChunk.x) + Math.abs(p.z - playerChunk.z)));
        scanQueue.addAll(all);
    }

    private void scanChunk(ChunkPos pos) {
        try {
            if (mc.world == null || mc.player == null) return;

            // Aggressive deep chunk forcing
            if (!mc.world.isChunkLoaded(pos.x, pos.z)) {
                mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.PositionAndOnGround(
                    mc.player.getX(), -45, mc.player.getZ(), false));
                return;
            }

            WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
            if (chunk == null || chunk.isEmpty()) return;

            scannedChunks.put(pos, Boolean.TRUE);

            BlockPos foundPos = null;
            int spawnerCount = 0;
            int bottomY = mc.world.getBottomY();
            int topY = 0;

            for (int lx = 0; lx < 16; lx++) {
                for (int lz = 0; lz < 16; lz++) {
                    for (int y = bottomY; y < topY; y += 2) {
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
                boolean isNew = !trackedChunks.containsKey(pos);
                trackedChunks.put(pos, foundPos);

                if (isNew && spawnerToast.get() && !notifiedChunks.containsKey(pos)) {
                    info("§9[Spawner] Found");
                    notifiedChunks.put(pos, Boolean.TRUE);
                }
            }
        } catch (Exception ignored) {}
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            if (mc.world == null || mc.player == null) return;
            if (Math.random() > 0.88) return;

            ChunkPos currentChunk = mc.player.getChunkPos();
            int radius = Math.min(renderDistance.get(), 24);

            int cd = clearDistance.get();
            boolean movedFar = scanOriginChunk != null && cd > 0 && (
                Math.abs(currentChunk.x - scanOriginChunk.x) > cd ||
                Math.abs(currentChunk.z - scanOriginChunk.z) > cd
            );

            if (movedFar || scanOriginChunk == null) {
                trackedChunks.clear();
                notifiedChunks.clear();
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

            int toProcess = chunksPerTick.get();
            while (toProcess > 0 && !scanDone && scanIndex < scanQueue.size()) {
                scanChunk(scanQueue.get(scanIndex));
                scanIndex++;
                toProcess--;
                if (scanIndex >= scanQueue.size()) scanDone = true;
            }

            renderSnapshot = new HashMap<>(trackedChunks);
        } catch (Exception ignored) {}
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        try {
            if (mc.world == null || mc.player == null || renderSnapshot.isEmpty()) return;

            double px = mc.player.getX();
            double pz = mc.player.getZ();
            int distBlocks = renderDistance.get() * 16;
            double distSq = (double) distBlocks * distBlocks;

            Color fill = cWithAlpha(spawnerFill.get(), alpha.get());
            Color line = c(spawnerLine.get());
            RenderStyle style = spawnerStyle.get();
            int yMin = -64;
            int yMax = spawnerBedrockPillar.get() ? 320 : 64;

            for (Map.Entry<ChunkPos, BlockPos> entry : renderSnapshot.entrySet()) {
                ChunkPos pos = entry.getKey();
                BlockPos spawnerPos = entry.getValue();

                double cx = pos.getCenterX();
                double cz = pos.getCenterZ();
                double ddx = cx - px;
                double ddz = cz - pz;
                if (ddx * ddx + ddz * ddz > distSq) continue;

                if (style == RenderStyle.Beam) {
                    if (spawnerPos == null) continue;
                    double bx = spawnerPos.getX();
                    double by = spawnerPos.getY();
                    double bz = spawnerPos.getZ();
                    double beamTop = spawnerBedrockPillar.get() ? 320 : 256;
                    event.renderer.box(bx, by, bz, bx + 1, beamTop, bz + 1, fill, line, ShapeMode.Both, 0);
                    continue;
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
        } catch (Exception ignored) {}
    }

    private Color c(SettingColor sc) { return new Color(sc.r, sc.g, sc.b, sc.a); }
    private Color cWithAlpha(SettingColor sc, int a) { return new Color(sc.r, sc.g, sc.b, a); }
}
