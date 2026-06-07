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
import net.minecraft.client.network.AbstractClientPlayerEntity;
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
    private final Setting<Boolean> detectSpawners = sgSpawner.add(new BoolSetting.Builder().name("enabled").description("Detect spawners").defaultValue(true).build());
    private final Setting<SettingColor> spawnerFill  = sgSpawner.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> spawnerLine  = sgSpawner.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(0, 100, 255, 200)).build());
    private final Setting<RenderStyle>  spawnerStyle = sgSpawner.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean> spawnerToast = sgSpawner.add(new BoolSetting.Builder().name("toast-notify").description("Show chat message when spawner found").defaultValue(true).build());
    private final Setting<Boolean> detectActivity = sgActivity.add(new BoolSetting.Builder().name("enabled").description("Detect player activity").defaultValue(true).build());
    private final Setting<SettingColor> activityFill  = sgActivity.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(255, 0, 0, 40)).build());
    private final Setting<SettingColor> activityLine  = sgActivity.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(255, 0, 0, 200)).build());
    private final Setting<RenderStyle>  activityStyle = sgActivity.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean> activityToast = sgActivity.add(new BoolSetting.Builder().name("toast-notify").description("Show chat message when activity found").defaultValue(true).build());
    private final Setting<Boolean> detectPlayers = sgPlayers.add(new BoolSetting.Builder().name("enabled").description("Detect players").defaultValue(true).build());
    private final Setting<SettingColor> playerFill  = sgPlayers.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(180, 0, 255, 40)).build());
    private final Setting<SettingColor> playerLine  = sgPlayers.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(180, 0, 255, 200)).build());
    private final Setting<RenderStyle>  playerStyle = sgPlayers.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean> playerToast = sgPlayers.add(new BoolSetting.Builder().name("toast-notify").description("Show chat message when player found").defaultValue(true).build());
    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder().name("render-distance").defaultValue(8).min(1).sliderMax(20).build());
    private final Setting<Integer> updateInterval = sgGeneral.add(new IntSetting.Builder().name("update-interval").description("Spawner scan speed in ticks").defaultValue(1).min(1).sliderMax(20).build());
    private enum ChunkType { PLAYER, SPAWNER, ACTIVITY }
    private final ConcurrentHashMap<ChunkPos, ChunkType> trackedChunks = new ConcurrentHashMap<>();
    private final Set<ChunkPos> notifiedChunks = Collections.synchronizedSet(new HashSet<>());
    private int tickCounter = 0;
    private int activityTick = 0;
    public GoofyDebug() {
        super(NovaDebugAddon.CATEGORY, "Nova Debug", "Highlights chunks with suspicious underground activity.");
    }
    @Override
    public void onActivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
        tickCounter = 0;
        activityTick = 0;
        scanActivityAndPlayers();
        scanSpawners();
        info("Nova Debug v2 by Saint - active.");
    }
    @Override
    public void onDeactivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
    }
    private String getSpawnerType(WorldChunk chunk, BlockPos spawnerPos) {
        try {
            BlockEntity be = chunk.getBlockEntity(spawnerPos);
            if (be instanceof MobSpawnerBlockEntity spawner) {
                var nbt = spawner.toInitialChunkDataNbt(mc.world.getRegistryManager());
                if (nbt.contains("SpawnData")) {
                    String entityId = nbt.getCompound("SpawnData").getCompound("entity").getString("id");
                    if (entityId.contains(":")) entityId = entityId.split(":")[1];
                    if (!entityId.isEmpty()) return entityId.substring(0, 1).toUpperCase() + entityId.substring(1).replace("_", " ");
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }
    private void scanActivityAndPlayers() {
        try {
            if (mc.world == null || mc.player == null) return;
            ChunkPos playerChunk = mc.player.getChunkPos();
            int radius = Math.min(renderDistance.get(), 12);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    ChunkPos pos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    if (!mc.world.isChunkLoaded(pos.x, pos.z)) continue;
                    if (detectPlayers.get()) {
                        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                            if (p == mc.player) continue;
                            if (p.getChunkPos().x == pos.x && p.getChunkPos().z == pos.z) {
                                boolean isNew = !ChunkType.PLAYER.equals(trackedChunks.get(pos));
                                trackedChunks.put(pos, ChunkType.PLAYER);
                                if (isNew && playerToast.get() && !notifiedChunks.contains(pos)) {
                                    info("§d[Nova Debug] Player §f" + p.getName().getString() + " §dfound!");
                                    notifiedChunks.add(pos);
                                }
                                break;
                            }
                        }
                    }
                    if (detectActivity.get()) {
                        if (!trackedChunks.containsKey(pos) || trackedChunks.get(pos) == ChunkType.ACTIVITY) {
                            boolean isNew = !ChunkType.ACTIVITY.equals(trackedChunks.get(pos));
                            trackedChunks.put(pos, ChunkType.ACTIVITY);
                            if (isNew && activityToast.get() && !notifiedChunks.contains(pos)) {
                                info("§c[Nova Debug] Player Activity §ffound!");
                                notifiedChunks.add(pos);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            error("Activity scan error: " + e.getMessage());
        }
    }
    private void scanSpawners() {
        try {
            if (mc.world == null || mc.player == null) return;
            ChunkPos playerChunk = mc.player.getChunkPos();
            int radius = Math.min(renderDistance.get(), 12);
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    ChunkPos pos = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                    if (!mc.world.isChunkLoaded(pos.x, pos.z)) continue;
                    if (!detectSpawners.get()) continue;
                    WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
                    BlockPos foundPos = null;
                    outer:
                    for (int lx = 0; lx < 16; lx++) {
                        for (int lz = 0; lz < 16; lz++) {
                            for (int y = -64; y < 64; y++) {
                                BlockPos bp = new BlockPos(pos.getStartX() + lx, y, pos.getStartZ() + lz);
                                if (chunk.getBlockState(bp).isOf(Blocks.SPAWNER)) {
                                    foundPos = bp;
                                    break outer;
                                }
                            }
                        }
                    }
                    if (foundPos != null) {
                        boolean isNew = !ChunkType.SPAWNER.equals(trackedChunks.get(pos));
                        trackedChunks.put(pos, ChunkType.SPAWNER);
                        if (isNew && spawnerToast.get() && !notifiedChunks.contains(pos)) {
                            String spawnerType = getSpawnerType(chunk, foundPos);
                            info("§9[Nova Debug] " + spawnerType + " Spawner §ffound!");
                            notifiedChunks.add(pos);
                        }
                    }
                }
            }
        } catch (Exception e) {
            error("Spawner scan error: " + e.getMessage());
        }
    }
    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            if (mc.world == null || mc.player == null) return;
            tickCounter++;
            activityTick++;
            if (activityTick >= 1) {
                activityTick = 0;
                scanActivityAndPlayers();
            }
            if (tickCounter % Math.max(1, updateInterval.get()) == 0) {
                scanSpawners();
            }
            trackedChunks.entrySet().removeIf(e -> {
                if (e.getValue() != ChunkType.PLAYER) return false;
                ChunkPos pos = e.getKey();
                for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
                    if (p == mc.player) continue;
                    if (p.getChunkPos().x == pos.x && p.getChunkPos().z == pos.z) return false;
                }
                notifiedChunks.remove(pos);
                return true;
            });
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
                double ddx = cx - px;
                double ddz = cz - pz;
                if (ddx * ddx + ddz * ddz > distSq) continue;
                Color fill;
                Color line;
                RenderStyle style;
                switch (type) {
                    case SPAWNER  -> { fill = c(spawnerFill.get());  line = c(spawnerLine.get());  style = spawnerStyle.get(); }
                    case ACTIVITY -> { fill = c(activityFill.get()); line = c(activityLine.get()); style = activityStyle.get(); }
                    default       -> { fill = c(playerFill.get());   line = c(playerLine.get());   style = playerStyle.get(); }
                }
                int x1 = pos.getStartX();
                int z1 = pos.getStartZ();
                int x2 = x1 + 16;
                int z2 = z1 + 16;
                if (style == RenderStyle.Pillar) {
                    event.renderer.box(x1, -64, z1, x2, 320, z2, fill, line, ShapeMode.Both, 0);
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
