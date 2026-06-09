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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class ActivityBypass extends Module {

    public enum RenderStyle { Pillar, Flat }

    // ── Settings ──────────────────────────────────────────────────────────────

    private final SettingGroup sgActivity = settings.createGroup("Activity Bypass");
    private final SettingGroup sgScan     = settings.createGroup("Scan");

    private final Setting<SettingColor> fillColor = sgActivity.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Fill color for detected activity chunks.")
        .defaultValue(new SettingColor(255, 60, 0, 35))
        .build());

    private final Setting<SettingColor> lineColor = sgActivity.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color for detected activity chunks.")
        .defaultValue(new SettingColor(255, 60, 0, 210))
        .build());

    private final Setting<Integer> fillAlpha = sgActivity.add(new IntSetting.Builder()
        .name("fill-alpha")
        .defaultValue(35)
        .min(0).sliderMax(255)
        .build());

    private final Setting<Integer> lineAlpha = sgActivity.add(new IntSetting.Builder()
        .name("line-alpha")
        .defaultValue(210)
        .min(0).sliderMax(255)
        .build());

    // Default changed to Flat
    private final Setting<RenderStyle> renderStyle = sgActivity.add(new EnumSetting.Builder<RenderStyle>()
        .name("render-style")
        .description("Pillar = full vertical column. Flat = thin slab at y=9.")
        .defaultValue(RenderStyle.Flat)
        .build());

    private final Setting<Boolean> bedrockPillar = sgActivity.add(new BoolSetting.Builder()
        .name("bedrock-pillar")
        .description("Only applies when render-style is Pillar.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> toastNotify = sgActivity.add(new BoolSetting.Builder()
        .name("toast-notify")
        .description("Chat message when a high-activity chunk is found.")
        .defaultValue(true)
        .build());

    // Scan-sweep flash settings
    private final Setting<SettingColor> sweepFill = sgScan.add(new ColorSetting.Builder()
        .name("sweep-fill-color")
        .defaultValue(new SettingColor(255, 255, 255, 18))
        .build());

    private final Setting<SettingColor> sweepLine = sgScan.add(new ColorSetting.Builder()
        .name("sweep-line-color")
        .defaultValue(new SettingColor(255, 255, 255, 120))
        .build());

    // Increased default from 3 → 10 for faster scanning
    private final Setting<Integer> chunksPerTick = sgScan.add(new IntSetting.Builder()
        .name("chunks-per-tick")
        .description("Chunks scanned per tick. Higher = faster but more lag.")
        .defaultValue(10).min(1).sliderMax(32)
        .build());

    private final Setting<Integer> renderDistance = sgScan.add(new IntSetting.Builder()
        .name("render-distance")
        .defaultValue(26).min(1).sliderMax(32)
        .build());

    // ── Tier-1: absolute giveaway blocks (3 pts each) ─────────────────────────
    private static final Set<Block> TIER1 = new HashSet<>(Arrays.asList(
        Blocks.ENDER_CHEST,
        Blocks.ENCHANTING_TABLE,
        Blocks.BREWING_STAND,
        Blocks.BLAST_FURNACE,
        Blocks.SMOKER,
        Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL,
        Blocks.SHULKER_BOX,
        Blocks.WHITE_SHULKER_BOX, Blocks.ORANGE_SHULKER_BOX,
        Blocks.MAGENTA_SHULKER_BOX, Blocks.LIGHT_BLUE_SHULKER_BOX,
        Blocks.YELLOW_SHULKER_BOX, Blocks.LIME_SHULKER_BOX,
        Blocks.PINK_SHULKER_BOX, Blocks.GRAY_SHULKER_BOX,
        Blocks.LIGHT_GRAY_SHULKER_BOX, Blocks.CYAN_SHULKER_BOX,
        Blocks.PURPLE_SHULKER_BOX, Blocks.BLUE_SHULKER_BOX,
        Blocks.BROWN_SHULKER_BOX, Blocks.GREEN_SHULKER_BOX,
        Blocks.RED_SHULKER_BOX, Blocks.BLACK_SHULKER_BOX
    ));

    // ── Tier-2: strong base indicators (2 pts each) ───────────────────────────
    // Bumped chests/furnaces/hoppers to 2 pts (from 1) for better accuracy.
    // Removed generic blocks like planks/wool/torches that appear naturally.
    private static final Set<Block> TIER2 = new HashSet<>(Arrays.asList(
        Blocks.CHEST, Blocks.TRAPPED_CHEST,
        Blocks.FURNACE,
        Blocks.CRAFTING_TABLE,
        Blocks.HOPPER, Blocks.DROPPER, Blocks.DISPENSER,
        Blocks.PISTON, Blocks.STICKY_PISTON,
        Blocks.OBSERVER,
        Blocks.REPEATER, Blocks.COMPARATOR,
        Blocks.REDSTONE_LAMP,
        Blocks.SEA_LANTERN,
        Blocks.BARREL, Blocks.LECTERN, Blocks.BOOKSHELF,
        Blocks.IRON_DOOR,
        Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE,
        Blocks.TNT,
        Blocks.TARGET,
        Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL,
        Blocks.REDSTONE_WIRE, Blocks.REDSTONE_TORCH,
        Blocks.GLOWSTONE
    ));

    // Threshold raised slightly to reduce false positives from natural structures
    private static final int SCORE_THRESHOLD = 8;

    // ── State ─────────────────────────────────────────────────────────────────

    private static final int  TOTAL_PASSES  = 3;
    private static final long FLASH_MILLIS  = 1000; // reduced from 1500ms for snappier feel

    private final ConcurrentHashMap<ChunkPos, Boolean> confirmedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean> notifiedChunks  = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Long>    sweepFlash      = new ConcurrentHashMap<>();

    private final List<ChunkPos> scanQueue = new ArrayList<>();
    private int     scanIndex  = 0;
    private int     passNumber = 0;
    private boolean scanning   = false;

    private volatile Set<ChunkPos>       confirmedSnapshot = Collections.emptySet();
    private volatile Map<ChunkPos, Long> flashSnapshot     = Collections.emptyMap();

    // ── Constructor ───────────────────────────────────────────────────────────

    public ActivityBypass() {
        super(NovaDebugAddon.CATEGORY, "Activity Bypass",
            "Scans all rendered chunks 3 times for high-confidence underground base activity.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        confirmedChunks.clear();
        notifiedChunks.clear();
        sweepFlash.clear();
        scanQueue.clear();
        scanIndex  = 0;
        passNumber = 0;
        scanning   = false;
        confirmedSnapshot = Collections.emptySet();
        flashSnapshot     = Collections.emptyMap();

        buildScanQueue();
        passNumber = 1;
        scanning   = true;
        info("§c[Activity Bypass] §fStarting scan pass §c1/3§f...");
    }

    @Override
    public void onDeactivate() {
        confirmedChunks.clear();
        notifiedChunks.clear();
        sweepFlash.clear();
        scanQueue.clear();
        scanning = false;
    }

    // ── Scan queue ────────────────────────────────────────────────────────────

    private void buildScanQueue() {
        if (mc.world == null || mc.player == null) return;
        scanQueue.clear();
        scanIndex = 0;

        ChunkPos playerChunk = mc.player.getChunkPos();
        int radius = Math.min(renderDistance.get(), 32);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                scanQueue.add(new ChunkPos(playerChunk.x + dx, playerChunk.z + dz));
            }
        }
        scanQueue.sort(Comparator.comparingInt(p ->
            Math.abs(p.x - playerChunk.x) + Math.abs(p.z - playerChunk.z)));
    }

    // ── Detection ─────────────────────────────────────────────────────────────

    private int scoreChunk(ChunkPos pos) {
        if (mc.world == null) return 0;
        if (!mc.world.isChunkLoaded(pos.x, pos.z)) return 0;

        WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
        if (chunk == null || chunk.isEmpty()) return 0;

        int score   = 0;
        int bottomY = mc.world.getBottomY();

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int y = bottomY; y < 64; y++) {
                    try {
                        BlockPos bp = new BlockPos(pos.getStartX() + lx, y, pos.getStartZ() + lz);
                        Block block = chunk.getBlockState(bp).getBlock();
                        if (TIER1.contains(block)) {
                            score += 3;
                        } else if (TIER2.contains(block)) {
                            score += 2;
                        }
                        if (score >= SCORE_THRESHOLD * 4) return score;
                    } catch (Exception ignored) {}
                }
            }
        }
        return score;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            if (mc.world == null || mc.player == null) return;
            if (!scanning) {
                confirmedSnapshot = new HashSet<>(confirmedChunks.keySet());
                flashSnapshot     = Collections.emptyMap();
                return;
            }

            long now = System.currentTimeMillis();
            sweepFlash.entrySet().removeIf(e -> now - e.getValue() > FLASH_MILLIS);

            int toProcess = chunksPerTick.get();
            while (toProcess > 0 && scanIndex < scanQueue.size()) {
                ChunkPos pos = scanQueue.get(scanIndex);
                scanIndex++;
                toProcess--;

                sweepFlash.put(pos, now);

                int score = scoreChunk(pos);
                if (score >= SCORE_THRESHOLD) {
                    boolean isNew = !confirmedChunks.containsKey(pos);
                    confirmedChunks.put(pos, Boolean.TRUE);
                    if (isNew && toastNotify.get() && !notifiedChunks.containsKey(pos)) {
                        info("§c[Activity Bypass] §fHigh-activity chunk at §c"
                            + pos.x + ", " + pos.z
                            + " §f(score: §c" + score + "§f) — likely base!");
                        notifiedChunks.put(pos, Boolean.TRUE);
                    }
                }
            }

            if (scanIndex >= scanQueue.size()) {
                if (passNumber < TOTAL_PASSES) {
                    passNumber++;
                    info("§c[Activity Bypass] §fStarting scan pass §c" + passNumber + "/3§f...");
                    buildScanQueue();
                } else {
                    scanning = false;
                    info("§c[Activity Bypass] §fAll 3 passes complete. §c"
                        + confirmedChunks.size() + " §fhigh-activity chunk"
                        + (confirmedChunks.size() == 1 ? "" : "s") + " found.");
                }
            }

            confirmedSnapshot = new HashSet<>(confirmedChunks.keySet());
            flashSnapshot     = new HashMap<>(sweepFlash);

        } catch (Exception e) {
            error("Tick error: " + e.getMessage());
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        try {
            if (mc.world == null || mc.player == null) return;

            double px      = mc.player.getX();
            double pz      = mc.player.getZ();
            int distBlocks = renderDistance.get() * 16;
            double distSq  = (double) distBlocks * distBlocks;

            int  yMin = -64;
            int  yMax = bedrockPillar.get() ? 320 : 64;
            long now  = System.currentTimeMillis();

            Set<ChunkPos>       confirmed = confirmedSnapshot;
            Map<ChunkPos, Long> flashes   = flashSnapshot;

            if (confirmed != null) {
                Color fill = new Color(fillColor.get().r, fillColor.get().g, fillColor.get().b, Math.min(255, fillAlpha.get()));
                Color line = new Color(lineColor.get().r, lineColor.get().g, lineColor.get().b, Math.min(255, lineAlpha.get()));

                for (ChunkPos pos : confirmed) {
                    if (!inRange(pos, px, pz, distSq)) continue;
                    renderChunk(event, pos, fill, line, yMin, yMax);
                }
            }

            if (flashes != null && scanning) {
                SettingColor sf = sweepFill.get();
                SettingColor sl = sweepLine.get();

                for (Map.Entry<ChunkPos, Long> entry : flashes.entrySet()) {
                    ChunkPos pos = entry.getKey();
                    if (!inRange(pos, px, pz, distSq)) continue;
                    long  age = now - entry.getValue();
                    float t   = Math.min(1f, age / (float) FLASH_MILLIS);
                    int   fa  = (int) (sf.a * (1f - t));
                    int   la  = (int) (sl.a * (1f - t));
                    if (fa < 2 && la < 2) continue;
                    // Sweep flash is always flat so it doesn't clutter the screen
                    renderFlat(event, pos, new Color(sf.r, sf.g, sf.b, fa), new Color(sl.r, sl.g, sl.b, la));
                }
            }

        } catch (Exception e) {
            error("Render error: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean inRange(ChunkPos pos, double px, double pz, double distSq) {
        double ddx = pos.getCenterX() - px;
        double ddz = pos.getCenterZ() - pz;
        return ddx * ddx + ddz * ddz <= distSq;
    }

    private void renderChunk(Render3DEvent event, ChunkPos pos,
                              Color fill, Color line, int yMin, int yMax) {
        if (renderStyle.get() == RenderStyle.Pillar) {
            renderPillar(event, pos, fill, line, yMin, yMax);
        } else {
            renderFlat(event, pos, fill, line);
        }
    }

    private void renderPillar(Render3DEvent event, ChunkPos pos,
                               Color fill, Color line, int yMin, int yMax) {
        int x1 = pos.getStartX(), z1 = pos.getStartZ();
        event.renderer.box(x1, yMin, z1, x1 + 16, yMax, z1 + 16, fill, line, ShapeMode.Both, 0);
    }

    private void renderFlat(Render3DEvent event, ChunkPos pos, Color fill, Color line) {
        int x1 = pos.getStartX(), z1 = pos.getStartZ();
        event.renderer.box(x1, 9, z1, x1 + 16, 10, z1 + 16, fill, line, ShapeMode.Both, 0);
    }
}
