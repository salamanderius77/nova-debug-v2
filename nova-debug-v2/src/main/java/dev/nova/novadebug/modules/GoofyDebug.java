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
import net.minecraft.class_1923;
import net.minecraft.class_2246;
import net.minecraft.class_2338;
import net.minecraft.class_2487;
import net.minecraft.class_2586;
import net.minecraft.class_2636;
import net.minecraft.class_2818;
import net.minecraft.class_742;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class GoofyDebug extends Module {
    public enum RenderStyle { Pillar, Flat }

    private final SettingGroup sgSpawner  = settings.createGroup("Spawners");
    private final SettingGroup sgActivity = settings.createGroup("Player Activity");
    private final SettingGroup sgPlayers  = settings.createGroup("Players");
    private final SettingGroup sgGeneral  = settings.createGroup("General");

    // Spawner settings
    private final Setting<Boolean>      detectSpawners = sgSpawner.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> spawnerFill    = sgSpawner.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(0, 100, 255, 40)).build());
    private final Setting<SettingColor> spawnerLine    = sgSpawner.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(0, 100, 255, 200)).build());
    private final Setting<RenderStyle>  spawnerStyle   = sgSpawner.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      spawnerToast   = sgSpawner.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());

    // Activity settings
    private final Setting<Boolean>      detectActivity = sgActivity.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> activityFill   = sgActivity.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(255, 0, 0, 40)).build());
    private final Setting<SettingColor> activityLine   = sgActivity.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(255, 0, 0, 200)).build());
    private final Setting<RenderStyle>  activityStyle  = sgActivity.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      activityToast  = sgActivity.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());

    // Player settings
    private final Setting<Boolean>      detectPlayers = sgPlayers.add(new BoolSetting.Builder().name("enabled").defaultValue(true).build());
    private final Setting<SettingColor> playerFill    = sgPlayers.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(180, 0, 255, 40)).build());
    private final Setting<SettingColor> playerLine    = sgPlayers.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(180, 0, 255, 200)).build());
    private final Setting<RenderStyle>  playerStyle   = sgPlayers.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean>      playerToast   = sgPlayers.add(new BoolSetting.Builder().name("toast-notify").defaultValue(true).build());

    // General settings
    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance")
        .defaultValue(26).min(1).sliderMax(32).build());

    private final Setting<Integer> chunksToScan = sgGeneral.add(new IntSetting.Builder()
        .name("chunks-to-scan")
        .description("How many chunks to scan per activation")
        .defaultValue(4).min(1).sliderMax(20).build());

    // FIX 1: Player must move this many chunks before spawner/activity highlights clear
    private final Setting<Integer> clearDistance = sgGeneral.add(new IntSetting.Builder()
        .name("clear-distance")
        .description("How many chunks you must move before spawner/activity highlights clear. 0 = never clear.")
        .defaultValue(26).min(0).sliderMax(32).build());

    // FIX 2: Extend pillars to full bedrock-to-sky height when anti-cheat is off
    private final Setting<Boolean> bedrockPillars = sgGeneral.add(new BoolSetting.Builder()
        .name("bedrock-pillars")
        .description("Extend player and spawner pillars from bedrock (-64) to sky (320). Enable when DonutSMP anti-cheat is off.")
        .defaultValue(false).build());

    // Internal state
    private enum ChunkType { PLAYER, SPAWNER, ACTIVITY }

    private final ConcurrentHashMap<class_1923, ChunkType> trackedChunks = new ConcurrentHashMap<>();
    private final Set<class_1923>  notifiedChunks = Collections.synchronizedSet(new HashSet<>());
    private final List<class_1923> scanQueue      = new ArrayList<>();
    private int     scanIndex   = 0;
    private int     tickCounter = 0;
    private boolean scanDone    = false;
    private class_1923 lastPlayerChunk    = null;
    // FIX 1: remember where the scan started so we measure distance from there
    private class_1923 scanOriginChunk    = null;

    public GoofyDebug() {
        super(NovaDebugAddon.CATEGORY, "Nova Debug", "Highlights chunks with suspicious underground activity.");
    }

    @Override
    public void onActivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
        scanQueue.clear();
        scanIndex   = 0;
        tickCounter = 0;
        scanDone    = false;
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
        if (mc.field_1687 == null || mc.field_1724 == null) return;
        scanQueue.clear();
        scanIndex = 0;
        scanDone  = false;
        class_1923 playerChunk = mc.field_1724.method_31476();
        lastPlayerChunk = playerChunk;
        scanOriginChunk = playerChunk; // remember where we started scanning
        int radius = Math.min(renderDistance.get(), 32);
        List<class_1923> all = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                all.add(new class_1923(playerChunk.field_9181 + dx, playerChunk.field_9180 + dz));
            }
        }
        all.sort(Comparator.comparingInt(p ->
            Math.abs(p.field_9181 - playerChunk.field_9181) + Math.abs(p.field_9180 - playerChunk.field_9180)));
        int limit = chunksToScan.get();
        for (int i = 0; i < Math.min(limit, all.size()); i++) {
            scanQueue.add(all.get(i));
        }
    }

    private String getSpawnerType(class_2818 chunk, class_2338 spawnerPos) {
        try {
            class_2586 be = chunk.method_8321(spawnerPos);
            if (be instanceof class_2636 spawner) {
                var nbt = spawner.method_16887(mc.field_1687.method_30349());
                if (nbt.method_10545("SpawnData")) {
                    String entityId = nbt.method_10562("SpawnData").method_10562("entity").method_10558("id");
                    if (entityId.contains(":")) entityId = entityId.split(":")[1];
                    if (!entityId.isEmpty()) return entityId.substring(0, 1).toUpperCase() + entityId.substring(1).replace("_", " ");
                }
            }
        } catch (Exception ignored) {}
        return "Unknown";
    }

    private void scanChunk(class_1923 pos) {
        try {
            if (mc.field_1687 == null || mc.field_1724 == null) return;
            if (!mc.field_1687.method_8393(pos.field_9181, pos.field_9180)) return;

            // Players
            if (detectPlayers.get()) {
                for (class_742 p : mc.field_1687.method_18456()) {
                    if (p == mc.field_1724) continue;
                    if (p.method_31476().field_9181 == pos.field_9181 && p.method_31476().field_9180 == pos.field_9180) {
                        boolean isNew = !ChunkType.PLAYER.equals(trackedChunks.get(pos));
                        trackedChunks.put(pos, ChunkType.PLAYER);
                        if (isNew && playerToast.get() && !notifiedChunks.contains(pos)) {
                            info("§d[Nova Debug] Player §f" + p.method_5477().getString() + " §dfound!");
                            notifiedChunks.add(pos);
                        }
                        return;
                    }
                }
            }

            // Spawners
            if (detectSpawners.get()) {
                class_2818 chunk = mc.field_1687.method_8497(pos.field_9181, pos.field_9180);
                class_2338 foundPos = null;
                outer:
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        for (int y = -64; y < 64; y++) {
                            class_2338 bp = new class_2338(pos.method_8326() + lx, y, pos.method_8328() + lz);
                            if (chunk.method_8320(bp).method_27852(class_2246.field_10260)) {
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
            if (mc.field_1687 == null || mc.field_1724 == null) return;

            // Live player tracking every tick
            if (detectPlayers.get()) {
                class_1923 playerChunk = mc.field_1724.method_31476();
                int radius = Math.min(renderDistance.get(), 32);

                // Remove chunks where the tracked player has left
                trackedChunks.entrySet().removeIf(e -> {
                    if (e.getValue() != ChunkType.PLAYER) return false;
                    class_1923 pos = e.getKey();
                    for (class_742 p : mc.field_1687.method_18456()) {
                        if (p == mc.field_1724) continue;
                        if (p.method_31476().field_9181 == pos.field_9181 && p.method_31476().field_9180 == pos.field_9180) return false;
                    }
                    notifiedChunks.remove(pos);
                    return true;
                });

                // Add new player positions
                for (class_742 p : mc.field_1687.method_18456()) {
                    if (p == mc.field_1724) continue;
                    class_1923 pos = p.method_31476();
                    if (Math.abs(pos.field_9181 - playerChunk.field_9181) > radius) continue;
                    if (Math.abs(pos.field_9180 - playerChunk.field_9180) > radius) continue;
                    boolean isNew = !ChunkType.PLAYER.equals(trackedChunks.get(pos));
                    trackedChunks.put(pos, ChunkType.PLAYER);
                    if (isNew && playerToast.get() && !notifiedChunks.contains(pos)) {
                        info("§d[Nova Debug] Player §f" + p.method_5477().getString() + " §dfound!");
                        notifiedChunks.add(pos);
                    }
                }
            }

            // FIX 1: Only wipe spawner/activity data when the player has moved
            // further than clearDistance chunks from where the scan started.
            // Moving just 1 chunk no longer clears anything.
            class_1923 currentChunk = mc.field_1724.method_31476();
            int cd = clearDistance.get();
            boolean movedPastClearDistance = scanOriginChunk != null && cd > 0 && (
                Math.abs(currentChunk.field_9181 - scanOriginChunk.field_9181) > cd ||
                Math.abs(currentChunk.field_9180 - scanOriginChunk.field_9180) > cd
            );

            if (movedPastClearDistance || scanOriginChunk == null) {
                // Far enough away — clear and re-scan from new position
                trackedChunks.entrySet().removeIf(e -> e.getValue() != ChunkType.PLAYER);
                notifiedChunks.clear();
                buildScanQueue();
            } else {
                // Just update the lastPlayerChunk reference (no data wipe)
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
            if (mc.field_1687 == null || mc.field_1724 == null) return;
            if (trackedChunks.isEmpty()) return;

            double px      = mc.field_1724.method_23317();
            double pz      = mc.field_1724.method_23321();
            int distBlocks = renderDistance.get() * 16;
            double distSq  = (double) distBlocks * distBlocks;

            for (Map.Entry<class_1923, ChunkType> entry : trackedChunks.entrySet()) {
                class_1923 pos  = entry.getKey();
                ChunkType  type = entry.getValue();

                double cx  = pos.method_33940();
                double cz  = pos.method_33942();
                double ddx = cx - px;
                double ddz = cz - pz;
                if (ddx * ddx + ddz * ddz > distSq) continue;

                Color       fill;
                Color       line;
                RenderStyle style;

                switch (type) {
                    case SPAWNER  -> { fill = c(spawnerFill.get());  line = c(spawnerLine.get());  style = spawnerStyle.get(); }
                    case ACTIVITY -> { fill = c(activityFill.get()); line = c(activityLine.get()); style = activityStyle.get(); }
                    default       -> { fill = c(playerFill.get());   line = c(playerLine.get());   style = playerStyle.get(); }
                }

                int x1 = pos.method_8326();
                int z1 = pos.method_8328();
                int x2 = x1 + 16;
                int z2 = z1 + 16;

                if (style == RenderStyle.Pillar) {
                    // FIX 2: When bedrockPillars is ON (anti-cheat off), pillars go
                    // all the way from bedrock (-64) to build limit (320).
                    // When OFF (anti-cheat on), pillars only show underground (-64 to 64).
                    int yMin = -64;
                    int yMax = bedrockPillars.get() ? 320 : 64;
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
