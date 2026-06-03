// ============================================================
// File: src/main/java/dev/nova/novadebug/rendering/ChunkRenderer.java
//
// Handles all OpenGL/Meteor render calls to draw chunk highlights.
//
// Design choices:
//   - Uses Meteor's Renderer3D / ShapeMode for compatibility.
//   - Renders chunk column outlines (and optional fills) at the
//     correct world-space positions.
//   - Distance culling skips chunks beyond the configured range.
//   - Chunk height mode can be "Full column" or "Underground only".
//   - Called from the module's onRender3D event handler.
// ============================================================

package dev.nova.novadebug.rendering;

import dev.nova.novadebug.util.ChunkActivityData;
import dev.nova.novadebug.util.ChunkPos2D;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.renderer.Renderer3D;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.Map;

/**
 * Stateless rendering helper for GoofyDebug.
 *
 * <p>Call {@link #render(Renderer3D, Map, RenderSettings)} from the
 * module's {@code onRender3D} event. This class has no mutable state;
 * all configuration is passed through {@link RenderSettings}.</p>
 */
public class ChunkRenderer {

    // ── Render settings bundle ───────────────────────────────────────────────

    /**
     * Snapshot of all rendering parameters gathered from the module settings.
     */
    public static class RenderSettings {
        public final Color   lowColor;
        public final Color   medColor;
        public final Color   highColor;
        public final int     renderDistance;   // in chunks
        public final boolean filled;
        public final float   outlineAlpha;     // 0–255 for outline
        public final boolean undergroundOnly;  // true = Y -64..63, false = full column
        public final float   minScoreThreshold;

        public RenderSettings(Color lowColor, Color medColor, Color highColor,
                              int renderDistance, boolean filled,
                              float outlineAlpha, boolean undergroundOnly,
                              float minScoreThreshold) {
            this.lowColor          = lowColor;
            this.medColor          = medColor;
            this.highColor         = highColor;
            this.renderDistance    = renderDistance;
            this.filled            = filled;
            this.outlineAlpha      = outlineAlpha;
            this.undergroundOnly   = undergroundOnly;
            this.minScoreThreshold = minScoreThreshold;
        }
    }

    // ── Main render entry point ──────────────────────────────────────────────

    /**
     * Draw chunk highlights for all tracked chunks.
     *
     * @param renderer    Meteor's Renderer3D (provided by onRender3D event).
     * @param trackedChunks Snapshot of chunk → activity data.
     * @param rs          Current render settings.
     */
    public static void render(Renderer3D renderer,
                              Map<ChunkPos2D, ChunkActivityData> trackedChunks,
                              RenderSettings rs) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;
        if (player == null) return;

        double playerX = player.getX();
        double playerZ = player.getZ();

        int distBlocks = rs.renderDistance * 16; // convert chunks → blocks
        double distSq  = (double) distBlocks * distBlocks;

        for (Map.Entry<ChunkPos2D, ChunkActivityData> entry : trackedChunks.entrySet()) {
            ChunkPos2D pos  = entry.getKey();
            ChunkActivityData data = entry.getValue();

            // ── Activity level check ──────────────────────────────────────────
            ChunkActivityData.ActivityLevel level =
                    data.getActivityLevel(rs.minScoreThreshold);
            if (level == null) continue; // below threshold — skip

            // ── Distance culling ──────────────────────────────────────────────
            double cx = pos.getCenterX();
            double cz = pos.getCenterZ();
            double dx = cx - playerX;
            double dz = cz - playerZ;
            if (dx * dx + dz * dz > distSq) continue;

            // ── Resolve color for this activity tier ──────────────────────────
            Color baseColor = resolveColor(level, rs);

            // ── Compute Y bounds ─────────────────────────────────────────────
            int yMin = rs.undergroundOnly ? -64 :  -64;
            int yMax = rs.undergroundOnly ?   63 :  320;

            // World-space corners of this chunk column.
            int x1 = pos.getMinBlockX();
            int z1 = pos.getMinBlockZ();
            int x2 = pos.getMaxBlockX();
            int z2 = pos.getMaxBlockZ();

            // ── Filled rendering ──────────────────────────────────────────────
            if (rs.filled) {
                // Fill color has reduced alpha for transparency.
                Color fillColor = withAlpha(baseColor, Math.max(20, baseColor.a / 4));
                renderFilledBox(renderer, x1, yMin, z1, x2, yMax, z2, fillColor);
            }

            // ── Outline rendering ─────────────────────────────────────────────
            Color outlineColor = withAlpha(baseColor, (int) rs.outlineAlpha);
            renderOutlineBox(renderer, x1, yMin, z1, x2, yMax, z2, outlineColor);
        }
    }

    // ── Rendering primitives ─────────────────────────────────────────────────

    /**
     * Renders a transparent filled box representing the chunk column.
     */
    private static void renderFilledBox(Renderer3D renderer,
                                        int x1, int y1, int z1,
                                        int x2, int y2, int z2,
                                        Color color) {
        // Meteor's Renderer3D.box draws a filled box with the given color.
        renderer.box(x1, y1, z1, x2, y2, z2, color, color, ShapeMode.Both, 0);
    }

    /**
     * Renders a wireframe outline box representing the chunk column.
     */
    private static void renderOutlineBox(Renderer3D renderer,
                                         int x1, int y1, int z1,
                                         int x2, int y2, int z2,
                                         Color color) {
        renderer.box(x1, y1, z1, x2, y2, z2, color, color, ShapeMode.Lines, 0);
    }

    // ── Color helpers ────────────────────────────────────────────────────────

    /**
     * Return the appropriate color for a given activity level.
     */
    private static Color resolveColor(ChunkActivityData.ActivityLevel level,
                                      RenderSettings rs) {
        return switch (level) {
            case LOW    -> rs.lowColor;
            case MEDIUM -> rs.medColor;
            case HIGH   -> rs.highColor;
        };
    }

    /**
     * Return a copy of {@code color} with the alpha channel replaced.
     */
    private static Color withAlpha(Color color, int alpha) {
        return new Color(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }
}
