// ============================================================
// File: src/main/java/dev/nova/novadebug/modules/GoofyDebug.java
//
// Main Meteor module for Nova Debug v2.
//
// This module ties together:
//   - ChunkTracker  (analysis engine)
//   - ChunkRenderer (drawing)
//   - Meteor's Setting<> system (all user-configurable options)
//   - Event subscriptions (onTick, onRender3D)
//
// Registration:
//   GoofyDebug is instantiated by NovaDebugAddon.onInitialize() and
//   passed to Modules.get().add(). Meteor then registers it, shows it
//   in the GUI, and dispatches subscribed events to it.
// ============================================================

package dev.nova.novadebug.modules;

import dev.nova.novadebug.NovaDebugAddon;
import dev.nova.novadebug.rendering.ChunkRenderer;
import dev.nova.novadebug.util.ChunkActivityData;
import dev.nova.novadebug.util.ChunkPos2D;
import dev.nova.novadebug.util.ChunkTracker;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import java.util.Map;

/**
 * GoofyDebug — Chunk-based underground activity analysis.
 *
 * <p>Scans loaded chunks for suspicious underground signals and
 * draws color-coded highlights directly in the 3D world.</p>
 *
 * <p><b>No per-block scanning every tick</b> — analysis is batched
 * and gated behind a configurable update interval to stay smooth.</p>
 */
public class GoofyDebug extends Module {

    // ────────────────────────────────────────────────────────────────────────
    // Setting groups
    // ────────────────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral     = settings.getDefaultGroup();
    private final SettingGroup sgDetection   = settings.createGroup("Detection");
    private final SettingGroup sgRender      = settings.createGroup("Render");
    private final SettingGroup sgPerformance = settings.createGroup("Performance");

    // ── General: Sensitivity ─────────────────────────────────────────────────

    private final Setting<Sensitivity> sensitivity = sgGeneral.add(
        new EnumSetting.Builder<Sensitivity>()
            .name("sensitivity")
            .description("Overall detection sensitivity. Higher = more chunks flagged, " +
                         "more false positives. Lower = stricter, fewer results.")
            .defaultValue(Sensitivity.Medium)
            .build()
    );

    // ── Detection Toggles ────────────────────────────────────────────────────

    private final Setting<Boolean> detectSpawners = sgDetection.add(
        new BoolSetting.Builder()
            .name("skeleton-spawners")
            .description("Flag chunks containing skeleton spawners underground.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> detectChunkActivity = sgDetection.add(
        new BoolSetting.Builder()
            .name("player-activity")
            .description("Score chunks based on repeated load events, suggesting " +
                         "sustained player presence or automated activity.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> detectPlayers = sgDetection.add(
        new BoolSetting.Builder()
            .name("players")
            .description("Immediately flag chunks that currently contain other players.")
            .defaultValue(true)
            .build()
    );

    // ── Render: Colors ────────────────────────────────────────────────────────

    private final Setting<SettingColor> lowColor = sgRender.add(
        new ColorSetting.Builder()
            .name("low-activity-color")
            .description("Color for chunks with low activity scores.")
            .defaultValue(new SettingColor(0, 255, 0, 180))
            .build()
    );

    private final Setting<SettingColor> medColor = sgRender.add(
        new ColorSetting.Builder()
            .name("medium-activity-color")
            .description("Color for chunks with medium activity scores.")
            .defaultValue(new SettingColor(255, 165, 0, 200))
            .build()
    );

    private final Setting<SettingColor> highColor = sgRender.add(
        new ColorSetting.Builder()
            .name("high-activity-color")
            .description("Color for chunks with high activity scores.")
            .defaultValue(new SettingColor(255, 0, 0, 220))
            .build()
    );

    // ── Render: Display options ───────────────────────────────────────────────

    private final Setting<Integer> renderDistance = sgRender.add(
        new IntSetting.Builder()
            .name("render-distance")
            .description("Maximum distance in chunks at which highlights are drawn.")
            .defaultValue(8)
            .min(2)
            .sliderMax(20)
            .build()
    );

    private final Setting<Boolean> filledChunks = sgRender.add(
        new BoolSetting.Builder()
            .name("filled-chunks")
            .description("Render a semi-transparent fill inside each flagged chunk " +
                         "in addition to the outline.")
            .defaultValue(false)
            .build()
    );

    private final Setting<Integer> outlineAlpha = sgRender.add(
        new IntSetting.Builder()
            .name("outline-alpha")
            .description("Alpha (opacity) of chunk outline lines. 255 = fully opaque.")
            .defaultValue(180)
            .min(10)
            .sliderMax(255)
            .build()
    );

    private final Setting<ChunkHeightMode> chunkHeightMode = sgRender.add(
        new EnumSetting.Builder<ChunkHeightMode>()
            .name("chunk-height-mode")
            .description("Controls the vertical extent of chunk highlights.")
            .defaultValue(ChunkHeightMode.UndergroundOnly)
            .build()
    );

    // ── Performance ───────────────────────────────────────────────────────────

    private final Setting<Integer> updateInterval = sgPerformance.add(
        new IntSetting.Builder()
            .name("update-interval")
            .description("How many ticks between analysis passes. " +
                         "Lower = more responsive, higher = better performance.")
            .defaultValue(20)
            .min(5)
            .sliderMax(100)
            .build()
    );

    private final Setting<Integer> maxTrackedChunks = sgPerformance.add(
        new IntSetting.Builder()
            .name("max-tracked-chunks")
            .description("Hard cap on how many chunks are tracked simultaneously " +
                         "to limit memory usage.")
            .defaultValue(512)
            .min(64)
            .sliderMax(2048)
            .build()
    );

    private final Setting<Double> decaySpeed = sgPerformance.add(
        new DoubleSetting.Builder()
            .name("activity-decay-speed")
            .description("How quickly chunk scores decrease per interval. " +
                         "Higher = faster fade-out of old data.")
            .defaultValue(0.5)
            .min(0.1)
            .sliderMax(5.0)
            .build()
    );

    // ────────────────────────────────────────────────────────────────────────
    // Internal state
    // ────────────────────────────────────────────────────────────────────────

    /** The analysis engine. Created fresh when the module is enabled. */
    private ChunkTracker tracker;

    // ────────────────────────────────────────────────────────────────────────
    // Enums (referenced by settings above)
    // ────────────────────────────────────────────────────────────────────────

    /** Overall detection sensitivity presets. */
    public enum Sensitivity {
        Low, Medium, High, Extreme;

        /** Returns the score multiplier for this sensitivity level. */
        public float multiplier() {
            return switch (this) {
                case Low     -> 0.5f;
                case Medium  -> 1.0f;
                case High    -> 1.75f;
                case Extreme -> 3.0f;
            };
        }

        /** Minimum score to display a chunk at this sensitivity. */
        public float minScore() {
            return switch (this) {
                case Low     -> 30f;
                case Medium  -> 15f;
                case High    -> 7f;
                case Extreme -> 2f;
            };
        }
    }

    /** Controls how tall the chunk highlight columns are. */
    public enum ChunkHeightMode {
        /** Highlights only Y=-64 to Y=63 (underground / cave layers). */
        UndergroundOnly,
        /** Highlights the full world height (Y=-64 to Y=320). */
        FullColumn
    }

    // ────────────────────────────────────────────────────────────────────────
    // Module lifecycle
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Construct the module.
     *
     * <p>The category "Nova Debug" groups this module in Meteor's GUI.
     * The module name and description appear in the search and tooltip.</p>
     */
    public GoofyDebug() {
        super(
            // Category — shown as a tab/group in Meteor's module list.
            meteordevelopment.meteorclient.systems.modules.Categories.World,
            // Module name — shown in Meteor's GUI.
            "GoofyDebug",
            // Short description shown in the module tooltip.
            "Analyzes chunk activity to detect suspicious underground areas, " +
            "player stashes, mob farms, and high-traffic locations."
        );
    }

    /** Called by Meteor when the user enables the module. */
    @Override
    public void onActivate() {
        tracker = new ChunkTracker();
        info("Nova Debug v2 — chunk analysis active.");
    }

    /** Called by Meteor when the user disables the module. */
    @Override
    public void onDeactivate() {
        if (tracker != null) {
            tracker.reset();
            tracker = null;
        }
        info("Nova Debug v2 — stopped.");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Event handlers
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Tick event — runs every game tick when the module is active.
     * Delegates analysis work to ChunkTracker.
     */
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (tracker == null) return;

        // Build settings snapshot and forward to the tracker.
        Sensitivity sens = sensitivity.get();
        ChunkTracker.Settings s = new ChunkTracker.Settings(
            updateInterval.get(),
            maxTrackedChunks.get(),
            decaySpeed.get().floatValue(),
            sens.multiplier(),
            detectSpawners.get(),
            detectChunkActivity.get(),
            detectPlayers.get(),
            sens.minScore()
        );
        tracker.tick(s);
    }

    /**
     * Render3D event — called each frame to draw chunk highlights.
     * We grab the current tracked chunk map and pass it to ChunkRenderer.
     */
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (tracker == null) return;

        Map<ChunkPos2D, ChunkActivityData> chunks = tracker.getTrackedChunks();
        if (chunks.isEmpty()) return;

        Sensitivity sens = sensitivity.get();

        ChunkRenderer.RenderSettings rs = new ChunkRenderer.RenderSettings(
            toColor(lowColor.get()),
            toColor(medColor.get()),
            toColor(highColor.get()),
            renderDistance.get(),
            filledChunks.get(),
            outlineAlpha.get(),
            chunkHeightMode.get() == ChunkHeightMode.UndergroundOnly,
            sens.minScore()
        );

        ChunkRenderer.render(event.renderer, chunks, rs);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    /** Convert a SettingColor (which extends Color) to a plain Color copy. */
    private Color toColor(SettingColor sc) {
        return new Color(sc.r, sc.g, sc.b, sc.a);
    }
}
