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
import net.minecraft.util.math.ChunkPos;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerSignal extends Module {

    public enum RenderStyle { Pillar, Flat }

    private final SettingGroup sgPlayers = settings.createGroup("Players");
    private final SettingGroup sgGeneral = settings.createGroup("General");

    private final Setting<SettingColor> playerFill = sgPlayers.add(new ColorSetting.Builder().name("fill-color").defaultValue(new SettingColor(180, 0, 255, 40)).build());
    private final Setting<SettingColor> playerLine = sgPlayers.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(180, 0, 255, 200)).build());
    private final Setting<RenderStyle> playerStyle = sgPlayers.add(new EnumSetting.Builder<RenderStyle>().name("render-style").defaultValue(RenderStyle.Pillar).build());
    private final Setting<Boolean> playerToast = sgPlayers.add(new BoolSetting.Builder().name("toast-notify").defaultValue(false).build());
    private final Setting<Boolean> playerBedrockPillar = sgPlayers.add(new BoolSetting.Builder().name("bedrock-pillar").defaultValue(true).build());

    private final Setting<Integer> renderDistance = sgGeneral.add(new IntSetting.Builder().name("render-distance").defaultValue(18).min(1).sliderMax(32).build());

    private final ConcurrentHashMap<ChunkPos, Boolean> trackedChunks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<ChunkPos, Boolean> notifiedChunks = new ConcurrentHashMap<>();
    private volatile Set<ChunkPos> renderSnapshot = Collections.emptySet();

    public PlayerSignal() {
        super(NovaDebugAddon.CATEGORY, "Player Signal", "Highlights chunks containing other players.");
    }

    @Override
    public void onActivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
        renderSnapshot = Collections.emptySet();
    }

    @Override
    public void onDeactivate() {
        trackedChunks.clear();
        notifiedChunks.clear();
        renderSnapshot = Collections.emptySet();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            if (mc.world == null || mc.player == null) return;

            ChunkPos current = mc.player.getChunkPos();
            int radius = Math.min(renderDistance.get(), 20);

            trackedChunks.keySet().removeIf(pos -> {
                for (PlayerEntity p : mc.world.getPlayers()) {
                    if (p != mc.player && p.getChunkPos().equals(pos)) return false;
                }
                return true;
            });

            for (PlayerEntity p : mc.world.getPlayers()) {
                if (p == mc.player) continue;
                ChunkPos pos = p.getChunkPos();
                if (Math.abs(pos.x - current.x) > radius || Math.abs(pos.z - current.z) > radius) continue;

                if (!trackedChunks.containsKey(pos)) {
                    trackedChunks.put(pos, true);
                    if (playerToast.get() && !notifiedChunks.containsKey(pos)) {
                        info("§d[Player Signal] Player found");
                        notifiedChunks.put(pos, true);
                    }
                }
            }

            renderSnapshot = new HashSet<>(trackedChunks.keySet());
        } catch (Exception ignored) {}
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        try {
            if (mc.world == null || mc.player == null) return;
            Set<ChunkPos> snapshot = renderSnapshot;
            if (snapshot == null || snapshot.isEmpty()) return;

            double px = mc.player.getX();
            double pz = mc.player.getZ();
            int distBlocks = renderDistance.get() * 16;
            double distSq = distBlocks * distBlocks;

            Color fill = c(playerFill.get());
            Color line = c(playerLine.get());
            RenderStyle style = playerStyle.get();
            int yMin = -64;
            int yMax = playerBedrockPillar.get() ? 320 : 64;

            for (ChunkPos pos : snapshot) {
                double cx = pos.getCenterX();
                double cz = pos.getCenterZ();
                if ((cx - px)*(cx - px) + (cz - pz)*(cz - pz) > distSq) continue;

                int x1 = pos.getStartX(), z1 = pos.getStartZ();
                int x2 = x1 + 16, z2 = z1 + 16;

                if (style == RenderStyle.Pillar) {
                    event.renderer.box(x1, yMin, z1, x2, yMax, z2, fill, line, ShapeMode.Both, 0);
                } else {
                    event.renderer.box(x1, 9, z1, x2, 10, z2, fill, line, ShapeMode.Both, 0);
                }
            }
        } catch (Exception ignored) {}
    }

    private Color c(SettingColor sc) { return new Color(sc.r, sc.g, sc.b, sc.a); }
}
