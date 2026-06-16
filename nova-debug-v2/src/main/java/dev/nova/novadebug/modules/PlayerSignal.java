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
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerSignal extends Module {

    public enum RenderStyle { Pillar, Flat, Beam }

    private final SettingGroup sgPlayers = settings.createGroup("Players");
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> playerFill = sgPlayers.add(new ColorSetting.Builder()
        .name("fill-color").defaultValue(new SettingColor(180, 0, 255, 40)).build());
    private final Setting<SettingColor> playerLine = sgPlayers.add(new ColorSetting.Builder()
        .name("line-color").defaultValue(new SettingColor(180, 0, 255, 200)).build());
    private final Setting<RenderStyle> playerStyle = sgPlayers.add(new EnumSetting.Builder<RenderStyle>()
        .name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean> playerBedrockPillar = sgPlayers.add(new BoolSetting.Builder()
        .name("bedrock-pillar").defaultValue(true).build());

    // FIX: beam-width setting so users can tune how wide the beam is (Beam style only)
    private final Setting<Double> beamWidth = sgPlayers.add(new DoubleSetting.Builder()
        .name("beam-width").description("Width of the beam in blocks (Beam style only).")
        .defaultValue(0.5).min(0.1).max(2.0).sliderMin(0.1).sliderMax(2.0).build());

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance").defaultValue(18).min(1).sliderMax(32).build());

    // FIX: store the player's exact Y level alongside their chunk so the Beam knows
    // where to start rendering. ChunkPos alone has no Y info.
    private final ConcurrentHashMap<ChunkPos, Double> trackedChunks = new ConcurrentHashMap<>();

    // FIX: volatile + immutable snapshot eliminates the iterator-vs-clear race that
    // caused the ConcurrentModificationException crashes on rapid toggle.
    private volatile Map<ChunkPos, Double> renderSnapshot = Collections.emptyMap();

    public PlayerSignal() {
        super(NovaDebugAddon.CATEGORY, "Player Signal", "Highlights chunks containing other players.");
    }

    @Override
    public void onActivate() {
        trackedChunks.clear();
        renderSnapshot = Collections.emptyMap();
    }

    @Override
    public void onDeactivate() {
        trackedChunks.clear();
        renderSnapshot = Collections.emptyMap();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        try {
            ChunkPos current = mc.player.getChunkPos();
            int radius = Math.min(renderDistance.get(), 20);

            // Collect all chunk positions currently occupied by other players
            // and snapshot player Y so Beam has a start height.
            // FIX: build a fresh map each tick rather than mutating the shared one
            // while iterating it (old code called removeIf + put in separate loops,
            // which could race with the render thread reading the same map).
            Map<ChunkPos, Double> fresh = new HashMap<>();
            List<? extends PlayerEntity> players = mc.world.getPlayers();
            for (PlayerEntity p : players) {
                if (p == mc.player) continue;
                ChunkPos pos = p.getChunkPos();
                if (Math.abs(pos.x - current.x) > radius
                        || Math.abs(pos.z - current.z) > radius) continue;
                // If multiple players share a chunk, keep the highest Y
                // so the beam starts above ground rather than underground.
                fresh.merge(pos, p.getY(), Math::max);
            }

            // Publish atomically - render thread always sees a complete consistent map.
            trackedChunks.clear();
            trackedChunks.putAll(fresh);
            renderSnapshot = new HashMap<>(trackedChunks);

        } catch (Exception ignored) {
            // Swallow any remaining edge-case exceptions so the module never crashes the game.
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        try {
            Map<ChunkPos, Double> snapshot = renderSnapshot;
            if (snapshot == null || snapshot.isEmpty()) return;

            double px = mc.player.getX();
            double pz = mc.player.getZ();
            int distBlocks = renderDistance.get() * 16;
            double distSq = (double) distBlocks * distBlocks;

            Color fill = c(playerFill.get());
            Color line = c(playerLine.get());
            RenderStyle style = playerStyle.get();

            int yMin = -64;
            int yMax = playerBedrockPillar.get() ? 320 : 64;

            for (Map.Entry<ChunkPos, Double> entry : snapshot.entrySet()) {
                ChunkPos pos = entry.getKey();
                double playerY = entry.getValue();

                double cx = pos.getCenterX();
                double cz = pos.getCenterZ();
                if ((cx - px) * (cx - px) + (cz - pz) * (cz - pz) > distSq) continue;

                int x1 = pos.getStartX(), z1 = pos.getStartZ();
                int x2 = x1 + 16, z2 = z1 + 16;

                if (style == RenderStyle.Pillar) {
                    // Full-chunk column from bedrock (or -64) to sky (or y=64)
                    event.renderer.box(x1, yMin, z1, x2, yMax, z2, fill, line, ShapeMode.Both, 0);

                } else if (style == RenderStyle.Flat) {
                    // Single flat slice across the chunk at y=9-10
                    event.renderer.box(x1, 9, z1, x2, 10, z2, fill, line, ShapeMode.Both, 0);

                } else if (style == RenderStyle.Beam) {
                    // FIX: narrow beacon-style beam centred on the middle of the chunk,
                    // starting at the player's feet and rising to sky (or y=64).
                    // This is visually a thin pillar/beacon, NOT a full chunk column.
                    double hw = beamWidth.get() / 2.0; // half-width
                    double bcx = pos.getCenterX();      // beam centre X
                    double bcz = pos.getCenterZ();      // beam centre Z
                    int beamBottom = (int) Math.floor(playerY); // starts at player feet
                    int beamTop = playerBedrockPillar.get() ? 320 : 128;

                    event.renderer.box(
                        bcx - hw, beamBottom, bcz - hw,
                        bcx + hw, beamTop,    bcz + hw,
                        fill, line, ShapeMode.Both, 0
                    );
                }
            }
        } catch (Exception ignored) {}
    }

    private Color c(SettingColor sc) { return new Color(sc.r, sc.g, sc.b, sc.a); }
}
