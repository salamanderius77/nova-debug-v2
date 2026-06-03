// ============================================================
// File: src/main/java/dev/nova/novadebug/util/ChunkPos2D.java
//
// A lightweight, immutable chunk-coordinate pair used as the
// key in our chunk-tracking map. Wraps Minecraft's ChunkPos
// but avoids pulling in heavy world-state references.
// ============================================================

package dev.nova.novadebug.util;

import net.minecraft.util.math.ChunkPos;

/**
 * Immutable (chunkX, chunkZ) pair suitable for use as a HashMap key.
 *
 * <p>We define equals/hashCode ourselves so we get value equality
 * rather than reference equality when looking up entries.</p>
 */
public final class ChunkPos2D {

    public final int x;
    public final int z;

    // Precomputed hash for performance in hot paths.
    private final int hash;

    public ChunkPos2D(int x, int z) {
        this.x    = x;
        this.z    = z;
        this.hash = 31 * x + z;
    }

    /** Convenience constructor from Minecraft's ChunkPos. */
    public ChunkPos2D(ChunkPos pos) {
        this(pos.x, pos.z);
    }

    // ── World-coordinate helpers ─────────────────────────────────────────────

    /** World X coordinate of the western edge of this chunk (block 0). */
    public int getMinBlockX() { return x << 4; }

    /** World Z coordinate of the northern edge of this chunk (block 0). */
    public int getMinBlockZ() { return z << 4; }

    /** World X coordinate of the eastern edge (exclusive) of this chunk. */
    public int getMaxBlockX() { return (x << 4) + 16; }

    /** World Z coordinate of the southern edge (exclusive) of this chunk. */
    public int getMaxBlockZ() { return (z << 4) + 16; }

    /** Center X in world coordinates. */
    public double getCenterX() { return (x << 4) + 8.0; }

    /** Center Z in world coordinates. */
    public double getCenterZ() { return (z << 4) + 8.0; }

    // ── Comparison ───────────────────────────────────────────────────────────

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ChunkPos2D other)) return false;
        return x == other.x && z == other.z;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public String toString() {
        return "ChunkPos2D[" + x + ", " + z + "]";
    }
}
