package dev.nova.novadebug.modules;

import dev.nova.novadebug.NovaDebugAddon;
import dev.nova.novadebug.hud.ToastManager;
import dev.nova.novadebug.rendering.ChunkRenderer;
import dev.nova.novadebug.util.ChunkActivityData;
import dev.nova.novadebug.util.ChunkPos2D;
import dev.nova.novadebug.util.ChunkTracker;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.List;
import java.util.Map;

public class GoofyDebug extends Module {

    public enum RenderStyle { Pillar, Flat }

    private final SettingGroup sgSpawner  = settings.createGroup("Spawners");
    private final SettingGroup sgActivity = settings.createGroup("Player Activity");
    private final SettingGroup sgPlayers  = settings.createGroup("Players");
    private final SettingGroup sgGeneral  = settings.createGroup("General");

    // Spawner settings
    private final Setting<Boolean> detectSpawners = sgSpawner.add(
        new BoolSetting.Builder()
            .name("enabled")
            .description("Detect underground spawners.")
            .defaultValue(true)
            .build()
    );
    private final Setting<SettingColor> spawnerFill = sgSpawner.add(
        new ColorSetting.Builder()
            .name("fill-color")
            .description("Spawner chunk fill color.")
            .defaultValue(new SettingColor(0, 100, 255, 40))
            .build()
    );
    private final Setting<SettingColor> spawnerLine = sgSpawner.add(
        new ColorSetting.Builder()
            .name("line-color")
            .description("Spawner chunk outline color.")
            .defaultValue(new SettingColor(0, 100, 255, 200))
            .build()
    );
    private final Setting<RenderStyle> spawnerStyle = sgSpawner.add(
        new EnumSetting.Builder<RenderStyle>()
            .name("render-style")
            .description("Pillar = full height column, Flat = underground slab.")
            .defaultValue(RenderStyle.Pillar)
            .build()
    );

    // Player Activity settings
    private final Setting<Boolean> detectActivity = sgActivity.add(
        new BoolSetting.Builder()
            .name("enabled")
            .description("Track chunk revisit / update activity.")
            .defaultValue(true)
            .build()
    );
    private final Setting<SettingColor> activityFill = sgActivity.add(
        new ColorSetting.Builder()
            .name("fill-color")
            .description("Activity chunk fill color.")
            .defaultValue(new SettingColor(255, 0, 0, 40))
            .build()
    );
    private final Setting<SettingColor> activityLine = sgActivity.add(
        new ColorSetting.Builder()
            .name("line-color")
            .description("Activity chunk outline color.")
            .defaultValue(new SettingColor(255, 0, 0, 200))
            .build()
    );
    private final Setting<RenderStyle> activityStyle = sgActivity.add(
        new EnumSetting.Builder<RenderStyle>()
            .name("render-style")
            .description("Pillar = full height column, Flat = underground slab.")
            .defaultValue(RenderStyle.Pillar)
            .build()
    );

    // Players settings
    private final Setting<Boolean> detectPlayers = sgPlayers.add(
        new BoolSetting.Builder()
            .name("enabled")
            .description("Detect other players. Detection is instant (every tick).")
            .defaultValue(true)
            .build()
    );
    private final Setting<SettingColor> playerFill = sgPlayers.add(
        new ColorSetting.Builder()
            .name("fill-color")
            .description("Player chunk fill color.")
            .defaultValue(new SettingColor(180, 0, 255, 40))
            .build()
    );
    private final Setting<SettingColor> playerLine = sgPlayers.add(
        new ColorSetting.Builder()
            .name("line-color")
            .description("Player chunk outline color.")
            .defaultValue(new SettingColor(180, 0, 255, 200))
            .build()
    );
    private final Setting<RenderStyle> playerStyle = sgPlayers.add(
        new EnumSetting.Builder<RenderStyle>()
            .name("render-style")
            .description("Pillar = full height column, Flat = underground slab.")
            .defaultValue(RenderStyle.Pillar)
            .build()
    );

    // General settings
    private final Setting<Integer> renderDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("render-distance")
            .description("Render distance in chunks.")
            .defaultValue(8)
            .min(1)
            .sliderMax(20)
            .build()
    );
    private final Setting<Integer> updateInterval = sgGeneral.add(
        new IntSetting.Builder()
            .name("update-interval")
            .description("Ticks between spawner/activity scans. Players are always instant.")
            .defaultValue(20)
            .min(5)
            .sliderMax(100)
            .build()
    );
    private final Setting<Boolean> toastNotify = sgGeneral.add(
        new BoolSetting.Builder()
            .name("toast-notify")
            .description(
                "Show a toast notification in the top-right corner when a " +
                "player or spawner is detected. Shows player name / spawner " +
                "count and exact coordinates. Fades out after 4s."
            )
            .defaultValue(true)
            .build()
    );

    private final ChunkTracker tracker = new ChunkTracker();
    private final ToastManager toasts  = new ToastManager();

    public GoofyDebug() {
        super(NovaDebugAddon.CATEGORY, "Nova Debug",
              "Highlights chunks with suspicious underground activity.");
    }

    @Override
    public void onActivate() {
        tracker.reset();
        toasts.clear();
        info("Nova Debug v2 by Saint - active.");
    }

    @Override
    public void onDeactivate() {
        tracker.reset();
        toasts.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        try {
            List<ChunkTracker.DetectionEvent> playerEvents =
                tracker.tickPlayers(buildSettings());

            List<ChunkTracker.DetectionEvent> analysisEvents =
                tracker.tickAnalysis(buildSettings());

            if (toastNotify.get()) {
                for (ChunkTracker.DetectionEvent ev : playerEvents) {
                    toasts.add(ToastManager.Toast.forPlayer(
                        ev.playerName, ev.x, ev.y, ev.z));
                }
                for (ChunkTracker.DetectionEvent ev : analysisEvents) {
                    if (ev.kind == ChunkTracker.DetectionEvent.Kind.SPAWNER) {
                        toasts.add(ToastManager.Toast.forSpawners(
                            ev.spawnerCount, ev.x, ev.z, ev.z));
                    }
                }
            }
        } catch (Exception e) {
            error("Tick error: " + e.getMessage());
        }
    }

    @EventHandler
    private void onRender2D(Render2DEvent event) {
        try {
            toasts.render(event.drawContext);
        } catch (Exception e) {
            error("Toast render error: " + e.getMessage());
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        try {
            if (mc.world == null || mc.player == null) return;
            Map<ChunkPos2D, ChunkActivityData> chunks = tracker.getTrackedChunks();
            if (chunks.isEmpty()) return;
            ChunkRenderer.render(event.renderer, chunks, buildRenderSettings());
        } catch (Exception e) {
            error("Render error: " + e.getMessage());
        }
    }

    private ChunkTracker.Settings buildSettings() {
        return new ChunkTracker.Settings(
            updateInterval.get(),
            200,
            0.5f,
            1.0f,
            detectSpawners.get(),
            detectActivity.get(),
            detectPlayers.get(),
            10f
        );
    }

    private ChunkRenderer.RenderSettings buildRenderSettings() {
        return new ChunkRenderer.RenderSettings(
            c(activityFill.get()),
            c(activityLine.get()),
            c(activityLine.get()),
            renderDistance.get(),
            true,
            200f,
            false,
            10f,
            c(spawnerFill.get()), c(spawnerLine.get()), spawnerStyle.get(),
            c(activityFill.get()), c(activityLine.get()), activityStyle.get(),
            c(playerFill.get()),  c(playerLine.get()),  playerStyle.get()
        );
    }

    private Color c(SettingColor sc) {
        return new Color(sc.r, sc.g, sc.b, sc.a);
    }
}
