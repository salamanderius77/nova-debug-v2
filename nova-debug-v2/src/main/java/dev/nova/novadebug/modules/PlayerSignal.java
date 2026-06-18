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

    private final Setting<Double> beamWidth = sgPlayers.add(new DoubleSetting.Builder()
        .name("beam-width").description("Width of the beam in blocks (Beam style only).")
        .defaultValue(0.5).min(0.1).max(2.0).sliderMin(0.1).sliderMax(2.0).build());

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder()
        .name("render-distance").defaultValue(18).min(1).sliderMax(32).build());

    private final Setting<Boolean> deepPlayerBypass = sgGeneral.add(new BoolSetting.Builder()
        .name("deep-player-bypass").description("Detect players under deepslate").defaultValue(true).build());

    private final ConcurrentHashMap<ChunkPos, Double> trackedChunks = new ConcurrentHashMap<>();
    private volatile Map<ChunkPos, Double> renderSnapshot = Collections.emptyMap();

    public PlayerSignal() {
        super(NovaDebugAddon.CATEGORY, "Player Signal", "Highlights players under deepslate");
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

            Map<ChunkPos, Double> fresh = new HashMap<>();
            List<? extends PlayerEntity> players = mc.world.getPlayers();
            for (PlayerEntity p : players) {
                if (p == mc.player) continue;
                ChunkPos pos = p.getChunkPos();
                if (Math.abs(pos.x - current.x) > radius
                        || Math.abs(pos.z - current.z) > radius) continue;

                fresh.merge(pos, p.getY(), deepPlayerBypass.get() ? Math::min : Math::max);
            }

            trackedChunks.clear();
            trackedChunks.putAll(fresh);
            renderSnapshot = new HashMap<>(trackedChunks);

        } catch (Exception ignored) {}
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
                    event.renderer.box(x1, yMin, z1, x2, yMax, z2, fill, line, ShapeMode.Both, 0);

                } else if (style == RenderStyle.Flat) {
                    event.renderer.box(x1, 9, z1, x2, 10, z2, fill, line, ShapeMode.Both, 0);

                } else if (style == RenderStyle.Beam) {
                    double hw = beamWidth.get() / 2.0;
                    double bcx = pos.getCenterX();
                    double bcz = pos.getCenterZ();
                    int beamBottom = (int) Math.floor(playerY);
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
